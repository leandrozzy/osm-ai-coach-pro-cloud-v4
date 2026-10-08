from pathlib import Path

ROOT = Path(__file__).resolve().parent
MAIN = ROOT / 'android-collector/app/src/main/java/com/osmaicoach/collector/MainActivity.kt'
PROC = ROOT / 'android-collector/app/src/main/java/com/osmaicoach/collector/NativeSessionProcessor.kt'
LOCAL = ROOT / 'android-collector/app/src/main/java/com/osmaicoach/collector/LocalOcrExtractor.kt'
TRACK = ROOT / 'android-collector/app/src/main/java/com/osmaicoach/collector/SlotNavigationTracker.kt'
TRACK_SOURCE = ROOT / 'SlotNavigationTracker.V26.kt'

for f in (MAIN, PROC, LOCAL, TRACK, TRACK_SOURCE):
    if not f.exists():
        raise SystemExit(f'ERRO: arquivo ausente: {f}')

TRACK.write_text(TRACK_SOURCE.read_text(encoding='utf-8'), encoding='utf-8')

local = LOCAL.read_text(encoding='utf-8')
if 'suspend fun readHubCards(file: File)' not in local:
    marker = '    fun applyToSlot(slot: NativeSlotData, texts: List<String>) {'
    if marker not in local:
        raise SystemExit('ERRO: ponto de inserção LocalOcrExtractor não encontrado')
    method = '''    suspend fun readHubCards(file: File): List<String> {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return emptyList()
        return try {
            val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            data class HubLine(val text: String, val x: Int, val y: Int)
            val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                val value = line.text.trim()
                if (value.isBlank()) return@mapNotNull null
                HubLine(value, box.centerX(), box.centerY())
            }
            if (lines.isEmpty()) return emptyList()
            val roundRegex = Regex("""\\b\\d{1,2}\\s*/\\s*\\d{1,2}\\b""")
            val slotRegex = Regex("""\\bslot\\s*[1-4]\\b""", RegexOption.IGNORE_CASE)
            var anchors = lines.filter { line ->
                roundRegex.containsMatchIn(normalize(line.text)) || slotRegex.containsMatchIn(normalize(line.text))
            }
            anchors = anchors.sortedBy { it.y }.fold(mutableListOf()) { acc, item ->
                val duplicate = acc.any { old ->
                    kotlin.math.abs(old.x - item.x) < bitmap.width * 0.08 &&
                    kotlin.math.abs(old.y - item.y) < bitmap.height * 0.035
                }
                if (!duplicate) acc += item
                acc
            }
            if (anchors.size < 3) return emptyList()
            anchors = anchors.take(4)
            val rowTolerance = (bitmap.height * 0.075f).toInt().coerceAtLeast(50)
            val ordered = anchors.sortedWith(Comparator { a, b ->
                val dy = a.y - b.y
                if (kotlin.math.abs(dy) <= rowTolerance) a.x - b.x else dy
            })
            val groups = MutableList(ordered.size) { mutableListOf<HubLine>() }
            lines.forEach { line ->
                var best = 0
                var bestDistance = Double.MAX_VALUE
                ordered.forEachIndexed { i, anchor ->
                    val dx = (line.x - anchor.x).toDouble() / bitmap.width.coerceAtLeast(1)
                    val dy = (line.y - anchor.y).toDouble() / bitmap.height.coerceAtLeast(1)
                    val distance = dx * dx + dy * dy
                    if (distance < bestDistance) { bestDistance = distance; best = i }
                }
                groups[best] += line
            }
            groups.map { group ->
                group.sortedWith(compareBy<HubLine> { it.y }.thenBy { it.x })
                    .joinToString("\\n") { it.text }.take(5000)
            }
        } catch (_: Throwable) {
            emptyList()
        } finally {
            bitmap.recycle()
        }
    }

'''
    local = local.replace(marker, method + marker, 1)
LOCAL.write_text(local, encoding='utf-8')

proc = PROC.read_text(encoding='utf-8')
old_route = '''        val classifications = ocrByIndex.mapValues { (_, text) -> ScreenClassifier.classify(text) }

        val tracked = SlotNavigationTracker.assign(session, ocrByIndex, slots)'''
new_route = '''        val classifications = ocrByIndex.mapValues { (_, text) -> ScreenClassifier.classify(text) }

        var hubCards: List<String> = emptyList()
        val hubCandidates = ocrByIndex.entries
            .filter { (_, text) -> SlotNavigationTracker.looksLikeHub(text) }
            .map { it.key }

        for (index in hubCandidates.take(4)) {
            val file = repository.frameFile(session, index) ?: continue
            val cards = withTimeoutOrNull(12000L) { local.readHubCards(file) } ?: emptyList()
            if (cards.count { it.isNotBlank() } >= 3) {
                hubCards = cards
                break
            }
        }

        prefs.edit().putInt("hub_cards_detected", hubCards.count { it.isNotBlank() }).apply()

        val tracked = SlotNavigationTracker.assign(session, ocrByIndex, slots, hubCards)'''
if old_route not in proc:
    raise SystemExit('ERRO: bloco de roteamento não encontrado')
proc = proc.replace(old_route, new_route, 1)

# V25-style persistence fix if source is still pre-V25.
old_early = '''        if (segments.all { it.isEmpty() }) {
            prefs.edit()
                .putString("slot_tracker_summary", tracked.summary)
                .putInt("slot_hub_frames", tracked.hubFrames.size)
                .putInt("slot_unassigned_frames", session.frames.size)
                .apply()

            val message = "Sessão preservada: nenhuma visita foi ligada com segurança a S1-S4. Os dados anteriores foram mantidos."
            onProgress(
                Progress(
                    false,
                    total,
                    total,
                    "Sessão preservada — rastreamento pendente",
                    0,
                    1,
                    "slot-map",
                    message
                )
            )
            return@withContext true
        }

        prefs.edit()
            .putString("slot_tracker_summary", tracked.summary)
            .putInt("slot_hub_frames", tracked.hubFrames.size)
            .putInt("slot_unassigned_frames", session.frames.size - frameToSlot.count { it.value in 1..4 })
            .apply()

        repository.updateFrameAnalysis(session.id,
            ocrByIndex.mapValues { (index,text) ->
                val c=classifications[index] ?: ScreenClassifier.Result("other","Tela do OSM")
                FrameAnalysisUpdate(
                    slotId=frameToSlot[index] ?: 0,
                    screenType=c.type,
                    screenTitle=c.title,
                    ocrText=text.take(500),
                    analysisState=when {
                        index in tracked.hubFrames -> "Central dos slots"
                        text.isBlank() -> "sem OCR"
                        (frameToSlot[index] ?: 0) == 0 -> "Aguardando identificação do slot"
                        else -> "OCR ✓"
                    },
                    extractedFields=0
                )
            }
        )'''
new_early = '''        repository.updateFrameAnalysis(session.id,
            ocrByIndex.mapValues { (index,text) ->
                val c=classifications[index] ?: ScreenClassifier.Result("other","Tela do OSM")
                FrameAnalysisUpdate(
                    slotId=frameToSlot[index] ?: 0,
                    screenType=c.type,
                    screenTitle=c.title,
                    ocrText=text.take(500),
                    analysisState=when {
                        index in tracked.hubFrames -> "Central dos slots"
                        text.isBlank() -> "sem OCR"
                        (frameToSlot[index] ?: 0) == 0 -> "OCR ✓ · slot ainda não confirmado"
                        else -> "OCR ✓"
                    },
                    extractedFields=0
                )
            }
        )

        prefs.edit()
            .putString("slot_tracker_summary", tracked.summary)
            .putInt("slot_hub_frames", tracked.hubFrames.size)
            .putInt("slot_unassigned_frames", session.frames.size - frameToSlot.count { it.value in 1..4 })
            .apply()

        if (segments.all { it.isEmpty() }) {
            val message = "OCR concluído; nenhuma visita teve identidade suficiente para S1-S4. Nenhum dado foi inventado."
            onProgress(Progress(false, session.frames.size, session.frames.size, "OCR concluído · slots não confirmados", 0, 1, "slot-map", message))
            return@withContext true
        }'''
if old_early in proc:
    proc = proc.replace(old_early, new_early, 1)
PROC.write_text(proc, encoding='utf-8')

main = MAIN.read_text(encoding='utf-8')
main = main.replace(
'''        val servicePermission = isAccessibilityServiceEnabledInSettings()
        val serviceConnected = isAccessibilityServiceActuallyConnected()
        val serviceReady = servicePermission
        val recording = CollectorState.isRecording() || repo.current()?.state == "recording"''',
'''        val servicePermission = isAccessibilityServiceEnabledInSettings()
        val serviceConnected = isAccessibilityServiceActuallyConnected()
        val recording = CollectorState.isRecording() || repo.current()?.state == "recording"
        val serviceReady = servicePermission || serviceConnected''')
old_completion = '''    private fun completion(s:NativeSlotData):Int {
        val values=listOf(
            s.team,s.competition,s.nextRival,s.matchDate,s.venue,s.referee,s.myStrength,s.rivalStrength,
            s.myValue,s.rivalValue,s.rivalFormation,s.rivalPlan,s.marking,s.offside,s.secretTraining,s.trainingCamp
        )
        val filled=values.count{it!="NI"&&it.isNotBlank()}
        return ((filled.toDouble()/values.size)*100).toInt()
    }'''
new_completion = '''    private fun completion(s:NativeSlotData):Int {
        if (s.team=="NI" || s.team.isBlank() || s.competition=="NI" || s.competition.isBlank()) return 0
        val values=listOf(
            s.team,s.competition,s.nextRival,s.matchDate,s.venue,s.referee,s.myStrength,s.rivalStrength,
            s.myValue,s.rivalValue,s.rivalFormation,s.rivalPlan,s.marking,s.offside,s.secretTraining,s.trainingCamp
        )
        val filled=values.count{it!="NI"&&it.isNotBlank()}
        return ((filled.toDouble()/values.size)*100).toInt()
    }'''
main = main.replace(old_completion, new_completion, 1)
for oldver in ('V23.2','V24.0','V25.0'):
    main = main.replace(f'DetailLine("Versão nativa","{oldver} · ${{BuildConfig.VERSION_NAME}}")', 'DetailLine("Versão nativa","V26.0 · ${BuildConfig.VERSION_NAME}")')
MAIN.write_text(main, encoding='utf-8')

checks = [(TRACK,'cards=${usableCards.count'), (LOCAL,'suspend fun readHubCards(file: File)'), (PROC,'hub_cards_detected'), (MAIN,'V26.0')]
for file, token in checks:
    if token not in file.read_text(encoding='utf-8'):
        raise SystemExit(f'ERRO validação {file.name}: {token}')
print('V26 APLICADA COM SUCESSO')
