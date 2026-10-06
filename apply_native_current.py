from pathlib import Path

ROOT = Path(__file__).resolve().parent
MAIN = ROOT / "android-collector/app/src/main/java/com/osmaicoach/collector/MainActivity.kt"
PROC = ROOT / "android-collector/app/src/main/java/com/osmaicoach/collector/NativeSessionProcessor.kt"
TRACK = ROOT / "android-collector/app/src/main/java/com/osmaicoach/collector/SlotNavigationTracker.kt"
TRACK_SOURCE = ROOT / "SlotNavigationTracker.V25.kt"

for f in (MAIN, PROC, TRACK, TRACK_SOURCE):
    if not f.exists():
        raise SystemExit(f"ERRO: arquivo obrigatório ausente: {f}")

TRACK.write_text(TRACK_SOURCE.read_text(encoding="utf-8"), encoding="utf-8")
print("OK: tracker V25")

main = MAIN.read_text(encoding="utf-8")

main = main.replace(
'''        val servicePermission = isAccessibilityServiceEnabledInSettings()
        val serviceConnected = isAccessibilityServiceActuallyConnected()
        val serviceReady = servicePermission
        val recording = CollectorState.isRecording() || repo.current()?.state == "recording"''',
'''        val servicePermission = isAccessibilityServiceEnabledInSettings()
        val serviceConnected = isAccessibilityServiceActuallyConnected()
        val recording = CollectorState.isRecording() || repo.current()?.state == "recording"
        val serviceReady = servicePermission || serviceConnected'''
)

main = main.replace(
'''                            Surface(shape = RoundedCornerShape(999.dp), color = if (serviceReady) Color(0xFF20372A) else Color(0xFF3A2D20)) {
                                Text(
                                    if (serviceReady) "LEITURA ON" else "LEITURA OFF",
                                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    color = if (serviceReady) Color(0xFFB8E34D) else Color(0xFFFFC857),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }''',
'''                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = when {
                                    recording -> Color(0xFF4A3A1F)
                                    serviceReady -> Color(0xFF20372A)
                                    else -> Color(0xFF3A2D20)
                                }
                            ) {
                                Text(
                                    when {
                                        recording -> "SESSÃO ATIVA"
                                        serviceReady -> "LEITURA ON"
                                        else -> "LEITURA OFF"
                                    },
                                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    color = if (recording) Color(0xFFFFC857) else if (serviceReady) Color(0xFFB8E34D) else Color(0xFFFFC857),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }'''
)

main = main.replace(
'''                                enabled=serviceReady&&!processing.running,''',
'''                                enabled=serviceReady,''',
1
)

main = main.replace(
'''                                enabled=!processing.running,
                                modifier=Modifier.fillMaxWidth().height(48.dp)''',
'''                                enabled=true,
                                modifier=Modifier.fillMaxWidth().height(48.dp)''',
1
)

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

if old_completion not in main and new_completion not in main:
    raise SystemExit("ERRO: função completion esperada não encontrada")
main = main.replace(old_completion, new_completion, 1)

main = main.replace(
'DetailLine("Versão nativa","V23.2 · ${BuildConfig.VERSION_NAME}")',
'DetailLine("Versão nativa","V25.0 · ${BuildConfig.VERSION_NAME}")'
)
main = main.replace(
'DetailLine("Versão nativa","V24.0 · ${BuildConfig.VERSION_NAME}")',
'DetailLine("Versão nativa","V25.0 · ${BuildConfig.VERSION_NAME}")'
)

MAIN.write_text(main, encoding="utf-8")
print("OK: MainActivity V25")

proc = PROC.read_text(encoding="utf-8")

old_block = '''        if (segments.all { it.isEmpty() }) {
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

new_block = '''        repository.updateFrameAnalysis(session.id,
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
                        (frameToSlot[index] ?: 0) == 0 -> "OCR ✓ · aguardando identificação do slot"
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
            val message = "OCR concluído, mas nenhuma visita pôde ser ligada com segurança a S1-S4. Nada foi inventado e os dados anteriores foram mantidos."
            onProgress(
                Progress(
                    false,
                    session.frames.size,
                    session.frames.size,
                    "OCR concluído · roteamento de slot pendente",
                    0,
                    1,
                    "slot-map",
                    message
                )
            )
            return@withContext true
        }'''

if old_block not in proc and new_block not in proc:
    raise SystemExit("ERRO: bloco de roteamento esperado não encontrado")
proc = proc.replace(old_block, new_block, 1)

proc = proc.replace(
'''        if(v.length !in 3..50 || !v.any{it.isLetter()}) return false
        return listOf(''',
'''        if(v.length !in 3..50 || !v.any{it.isLetter()}) return false
        if(Regex("^slot\\\\s*[1-4]$", RegexOption.IGNORE_CASE).matches(v.trim())) return false
        return listOf(''',
1
)

PROC.write_text(proc, encoding="utf-8")
print("OK: Processor V25")

if "V25.0" not in MAIN.read_text(encoding="utf-8"):
    raise SystemExit("ERRO validação: V25.0")
if "OCR concluído · roteamento de slot pendente" not in PROC.read_text(encoding="utf-8"):
    raise SystemExit("ERRO validação: Processor")
if "bestScore >= 8" not in TRACK.read_text(encoding="utf-8"):
    raise SystemExit("ERRO validação: Tracker")

print("V25 APLICADA COM SUCESSO")
