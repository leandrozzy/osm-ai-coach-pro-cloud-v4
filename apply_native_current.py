from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent

def read(rel):
    p = ROOT / rel
    if not p.exists():
        raise SystemExit(f"Arquivo não encontrado: {rel}")
    return p, p.read_text(encoding="utf-8")

def replace_once(text, old, new, label):
    if old in text:
        return text.replace(old, new, 1)
    if new in text:
        return text
    raise SystemExit(f"PATCH ABORTADO: trecho não encontrado em {label}")

def write_if_changed(p, new):
    old = p.read_text(encoding="utf-8")
    if old == new:
        print("UNCHANGED:", p.relative_to(ROOT))
        return 0
    p.write_text(new, encoding="utf-8")
    print("PATCHED:", p.relative_to(ROOT))
    return 1

main_p, main = read("android-collector/app/src/main/java/com/osmaicoach/collector/MainActivity.kt")
svc_p, svc = read("android-collector/app/src/main/java/com/osmaicoach/collector/OsmCaptureAccessibilityService.kt")
proc_p, proc = read("android-collector/app/src/main/java/com/osmaicoach/collector/NativeSessionProcessor.kt")
tracker_p, tracker = read("android-collector/app/src/main/java/com/osmaicoach/collector/SlotNavigationTracker.kt")
gradle_p, gradle = read("android-collector/app/build.gradle.kts")

old_resume = '''    override fun onResume() {
        super.onResume()
        // Fallback: alguns aparelhos não emitem imediatamente um evento de
        // acessibilidade ao voltar para o Coach. Se o OSM foi visto depois do
        // último lançamento, finalizamos a sessão aqui também.
        android.os.Handler(mainLooper).postDelayed({
            val runtime=getSharedPreferences("collector_runtime",Context.MODE_PRIVATE)
            val launchAt=runtime.getLong("coach_launch_at",0L)
            val lastOsm=runtime.getLong("last_osm_seen_at",0L)
            if(launchAt>0L && lastOsm>=launchAt && runtime.getBoolean("osm_seen_in_session",false)){
                repo.finish()
                runtime.edit()
                    .putBoolean("osm_seen_in_session",false)
                    .putLong("coach_launch_at",0L)
                    .apply()
                CollectorState.setRecording(false)
                CollectorState.signalSessionReady()
            }
        },900L)
    }'''
new_resume = '''    override fun onResume() {
        super.onResume()
        // V22: voltar ao Coach NÃO encerra a sessão.
        // O usuário pode entrar e sair do OSM normalmente e só encerra quando
        // tocar em "Encerrar captura e atualizar".
        if (repo.current()?.state == "recording") {
            CollectorState.setRecording(true)
        }
    }'''
main = replace_once(main, old_resume, new_resume, "MainActivity.onResume")

main = replace_once(main,
'''        val latestForProcessing = repo.latestSession()
        val latestSessionId = latestForProcessing?.id ?: ""

        LaunchedEffect(latestSessionId, forceProcessToken) {''',
'''        val latestForProcessing = repo.latestSession()
        val latestSessionId = latestForProcessing?.id ?: ""
        val latestSessionState = latestForProcessing?.state ?: ""

        LaunchedEffect(latestSessionId, latestSessionState, forceProcessToken) {''',
"MainActivity processing key")

main = replace_once(main,
'        val serviceReady = servicePermission && serviceConnected\n        val recording = CollectorState.isRecording()',
'        val serviceReady = servicePermission\n        val recording = CollectorState.isRecording() || repo.current()?.state == "recording"',
"MainActivity service/recording state")

main = replace_once(main,
'''                            when {
                                recording -> "Lendo o OSM agora • você pode navegar normalmente"
                                serviceReady -> "Leitura automática pronta"
                                servicePermission -> "Leitura autorizada • reconectando serviço"
                                else -> "Ative a leitura automática uma única vez"
                            },''',
'''                            when {
                                recording -> "Sessão ativa • você pode voltar ao OSM quando quiser"
                                servicePermission && !serviceConnected -> "Leitura autorizada • Android reconectando o serviço"
                                serviceReady -> "Leitura automática pronta"
                                else -> "Ative a leitura automática uma única vez"
                            },''',
"MainActivity header status")

main = replace_once(main,
'''                        0 -> TodayScreen(serviceReady, recording, latest, processing, slots, onReprocess={ forceProcessToken++ }) { openOsm() }''',
'''                        0 -> TodayScreen(
                            serviceReady = serviceReady,
                            recording = recording,
                            latest = latest,
                            processing = processing,
                            slots = slots,
                            onReprocess = { forceProcessToken++ },
                            openOsm = { openOsm() },
                            stopCapture = {
                                finishCaptureSession()
                                forceProcessToken++
                            }
                        )''',
"MainActivity TodayScreen call")

main = replace_once(main,
'''        onReprocess:()->Unit,
        openOsm:()->Unit
    ) {''',
'''        onReprocess:()->Unit,
        openOsm:()->Unit,
        stopCapture:()->Unit
    ) {''',
"TodayScreen signature")

old_button = '''                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick=openOsm,
                            enabled=serviceReady&&!recording&&!processing.running,
                            modifier=Modifier.fillMaxWidth().height(52.dp),
                            colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFB8E34D),contentColor=Color(0xFF122630))
                        ){
                            Icon(Icons.Default.PlayArrow,null);Spacer(Modifier.width(8.dp));Text("Abrir OSM",fontWeight=FontWeight.Bold)
                        }'''
new_button = '''                        Spacer(Modifier.height(16.dp))
                        if(recording) {
                            Button(
                                onClick=openOsm,
                                enabled=serviceReady&&!processing.running,
                                modifier=Modifier.fillMaxWidth().height(52.dp),
                                colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFB8E34D),contentColor=Color(0xFF122630))
                            ){
                                Icon(Icons.Default.PlayArrow,null)
                                Spacer(Modifier.width(8.dp))
                                Text("Voltar ao OSM",fontWeight=FontWeight.Bold)
                            }
                            Spacer(Modifier.height(9.dp))
                            OutlinedButton(
                                onClick=stopCapture,
                                enabled=!processing.running,
                                modifier=Modifier.fillMaxWidth().height(48.dp)
                            ){
                                Icon(Icons.Default.StopCircle,null)
                                Spacer(Modifier.width(8.dp))
                                Text("Encerrar captura e atualizar",fontWeight=FontWeight.Bold)
                            }
                            Spacer(Modifier.height(7.dp))
                            Text(
                                "Jogue normalmente. Não espere entre telas. O app só encerra quando você tocar no botão acima.",
                                color=Color(0xFFB6C6CE),
                                fontSize=11.sp
                            )
                        } else {
                            Button(
                                onClick=openOsm,
                                enabled=serviceReady&&!processing.running,
                                modifier=Modifier.fillMaxWidth().height(52.dp),
                                colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFB8E34D),contentColor=Color(0xFF122630))
                            ){
                                Icon(Icons.Default.PlayArrow,null)
                                Spacer(Modifier.width(8.dp))
                                Text("Abrir OSM e iniciar captura",fontWeight=FontWeight.Bold)
                            }
                        }'''
main = replace_once(main, old_button, new_button, "TodayScreen open/stop buttons")

old_open = '''    private fun openOsm() {
        // Registra a intenção de iniciar uma sessão ANTES de abrir o OSM.
        // Activity e AccessibilityService usam o mesmo marcador; assim não há
        // perda da sessão mesmo quando o Android recria uma das duas.
        val now=System.currentTimeMillis()
        val session=repo.beginNewSession()
        getSharedPreferences("collector_runtime",Context.MODE_PRIVATE).edit()
            .putBoolean("osm_seen_in_session", false)
            .putBoolean("start_new_session_pending", true)
            .putLong("requested_session_at", now)
            .putLong("coach_launch_at", now)
            .putString("requested_session_id", session.id)
            .apply()
        packageManager.getLaunchIntentForPackage(OSM_PACKAGE)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(it)
        }
    }'''
new_open = '''    private fun openOsm() {
        val now=System.currentTimeMillis()
        val current=repo.current()
        val session=current ?: repo.beginNewSession()
        getSharedPreferences("collector_runtime",Context.MODE_PRIVATE).edit()
            .putBoolean("osm_seen_in_session", current != null)
            .putBoolean("start_new_session_pending", current == null)
            .putLong("requested_session_at", if(current==null) now else session.startedAt)
            .putLong("coach_launch_at", now)
            .putString("requested_session_id", session.id)
            .apply()
        CollectorState.setRecording(true)
        OsmLauncher.open(this)
    }

    private fun finishCaptureSession() {
        val finished=repo.finish()
        getSharedPreferences("collector_runtime",Context.MODE_PRIVATE).edit()
            .putBoolean("osm_seen_in_session",false)
            .putBoolean("start_new_session_pending",false)
            .putLong("requested_session_at",0L)
            .putLong("coach_launch_at",0L)
            .remove("requested_session_id")
            .putLong("session_finished_at",System.currentTimeMillis())
            .apply()
        CollectorState.setRecording(false)
        if(finished!=null) CollectorState.signalSessionReady()
    }'''
main = replace_once(main, old_open, new_open, "MainActivity openOsm/finishCaptureSession")
main = re.sub(r'DetailLine\("Versão nativa","V\d+\s*·\s*\$\{BuildConfig\.VERSION_NAME\}"\)', 'DetailLine("Versão nativa","V22 · ${BuildConfig.VERSION_NAME}")', main, count=1)

svc = replace_once(svc,
'''    private fun shouldKeepCapturing(): Boolean {
        if (isExplicitSessionArmed()) return true
        val pkg = detectForegroundPackage() ?: CollectorState.currentForegroundPackage
        return pkg == OSM_PACKAGE
    }''',
'''    private fun shouldKeepCapturing(): Boolean {
        val pkg = detectForegroundPackage() ?: CollectorState.currentForegroundPackage
        return pkg == OSM_PACKAGE
    }''',
"Accessibility shouldKeepCapturing")
svc = replace_once(svc,
'        val delay = maxOf(1900L - (System.currentTimeMillis() - lastCaptureAt), 120L)',
'        val delay = maxOf(900L - (System.currentTimeMillis() - lastCaptureAt), 90L)',
"Accessibility capture interval")
svc = replace_once(svc,
'                val forceSample = now - runtime.getLong("last_saved_frame_at", 0L) >= 12000L',
'                val forceSample = now - runtime.getLong("last_saved_frame_at", 0L) >= 7000L',
"Accessibility force sample")
svc = replace_once(svc,
'''    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::repository.isInitialized) repository.finish()
        osmWasForeground = false''',
'''    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        osmWasForeground = false''',
"Accessibility onDestroy")

proc = re.sub(r'            val maxImages\s*=\s*\d+', '            val maxImages=2', proc, count=1)
proc = replace_once(proc,
'''            val texts=indices.mapNotNull{ocrByIndex[it]}.filter{it.isNotBlank()}
            local.applyToSlot(slot,texts)
            localKnownAfter[slotIndex]=knownCount(slot)''',
'''            val texts=indices
                .filter { idx ->
                    classifications[idx]?.type in setOf("match","calendar","club","tactics","result")
                }
                .mapNotNull{ocrByIndex[it]}
                .filter{it.isNotBlank()}
            local.applyToSlot(slot,texts)
            sanitizeCorruptedSlot(slot)
            localKnownAfter[slotIndex]=knownCount(slot)''',
"Processor local OCR scope")

old_rows = '''        fun rowsForType(rows:List<Int>, type:String):List<Int>{
            val fallbackTypes=when(type){
                "match" -> setOf("club","tactics","result")
                "squad" -> setOf("training","market")
                "calendar" -> setOf("ranking")
                else -> emptySet()
            }
            // Não descarte telas classificadas como "Outra". Em páginas de jogador,
            // calendário e scout o Accessibility muitas vezes expõe apenas o nome/valor.
            // Enviamos TODO o OCR textual do slot, priorizando as telas do tipo pedido.
            return rows.sortedWith(compareBy<Int> { idx ->
                when {
                    classifications[idx]?.type==type -> 0
                    classifications[idx]?.type in fallbackTypes -> 1
                    classifications[idx]?.type=="other" -> 2
                    else -> 3
                }
            }.thenBy { it })
        }

        val jobs=mutableListOf<Triple<Int,String,List<Int>>>()
        segments.forEachIndexed{i,rows->
            if(rows.isEmpty()) return@forEachIndexed
            jobs+=Triple(i,"match",rowsForType(rows,"match"))
            jobs+=Triple(i,"squad",rowsForType(rows,"squad"))
            jobs+=Triple(i,"calendar",rowsForType(rows,"calendar"))
        }'''
new_rows = '''        fun rowsForType(rows:List<Int>, type:String):List<Int>{
            val accepted=when(type){
                "match" -> setOf("match","club","tactics","result")
                "squad" -> setOf("squad","training","market")
                "calendar" -> setOf("calendar","ranking")
                else -> emptySet()
            }
            val exact=rows.filter { classifications[it]?.type==type }
            val related=rows.filter { classifications[it]?.type in accepted && it !in exact }
            return (exact+related).distinct()
        }

        val jobs=mutableListOf<Triple<Int,String,List<Int>>>()
        segments.forEachIndexed{i,rows->
            if(rows.isEmpty()) return@forEachIndexed
            listOf("match","squad","calendar").forEach { type ->
                val relevant=rowsForType(rows,type)
                if(relevant.isNotEmpty()) jobs+=Triple(i,type,relevant)
            }
        }'''
proc = replace_once(proc, old_rows, new_rows, "Processor relevant screen selection")
proc = replace_once(proc,
'''            val nativeOcrText=job.third.mapNotNull{ocrByIndex[it]}.filter{it.isNotBlank()}.joinToString("\\n\\n").take(30000)''',
'''            val nativeOcrText=job.third
                .mapNotNull{ocrByIndex[it]?.trim()}
                .filter{it.isNotBlank()}
                .distinct()
                .take(24)
                .joinToString("\\n\\n")
                .take(24000)''',
"Processor cloud OCR text")
proc = replace_once(proc,
'''                val changed=runCatching{applyResult(slot,result.first,result.second)}.getOrElse { 0 }
                val after=knownCount(slot)''',
'''                val changed=runCatching{applyResult(slot,result.first,result.second)}.getOrElse { 0 }
                sanitizeCorruptedSlot(slot)
                val after=knownCount(slot)''',
"Processor sanitize after cloud")

old_squad = '''            if(parsed.isNotEmpty()){
                val merged=slot.players
                    .filter{ validPlayerName(it.name) && normPos(it.position)!="NI" }
                    .associateBy{ it.name.trim().lowercase()+"|"+normPos(it.position) }
                    .toMutableMap()

                parsed.forEach{ fresh ->
                    val key=fresh.name.trim().lowercase()+"|"+fresh.position
                    merged[key]=fresh
                }

                slot.players=merged.values.take(40).toMutableList()
                slot.squadCount=slot.players.size'''
new_squad = '''            if(parsed.isNotEmpty()){
                val merged=slot.players
                    .filter{ validPlayerName(it.name) }
                    .associateBy{ it.name.trim().lowercase() }
                    .toMutableMap()

                parsed.forEach{ fresh ->
                    val key=fresh.name.trim().lowercase()
                    val old=merged[key]
                    merged[key]=if(old==null) fresh else NativePlayerData(
                        name=fresh.name,
                        position=if(fresh.position!="NI")fresh.position else old.position,
                        age=if(fresh.age!="NI")fresh.age else old.age,
                        strength=if(fresh.strength!="NI")fresh.strength else old.strength,
                        value=if(fresh.value!="NI")fresh.value else old.value,
                        training=if(fresh.training!="NI")fresh.training else old.training,
                        selling=if(fresh.selling!="NI")fresh.selling else old.selling
                    )
                }

                slot.players=merged.values.take(40).toMutableList()
                slot.squadCount=slot.players.size'''
proc = replace_once(proc, old_squad, new_squad, "Processor incremental squad")

old_cal = '''        if(parsed.isNotEmpty()){
            slot.calendar=parsed.distinctBy{
                listOf(it.round,it.opponent,it.date,it.time).joinToString("|").lowercase()
            }.toMutableList()
            slot.calendarCount=slot.calendar.size
            changed += slot.calendar.size

            val future=slot.calendar.firstOrNull {'''
new_cal = '''        if(parsed.isNotEmpty()){
            fun keyOf(g:NativeCalendarGame):String =
                listOf(g.round,g.date,g.time,g.opponent).joinToString("|").lowercase()
            val merged=linkedMapOf<String,NativeCalendarGame>()
            slot.calendar.forEach { old -> merged[keyOf(old)]=old }
            parsed.forEach { fresh ->
                val key=keyOf(fresh)
                val old=merged[key]
                merged[key]=if(old==null) fresh else NativeCalendarGame(
                    round=if(fresh.round!="NI")fresh.round else old.round,
                    opponent=if(fresh.opponent!="NI")fresh.opponent else old.opponent,
                    date=if(fresh.date!="NI")fresh.date else old.date,
                    time=if(fresh.time!="NI")fresh.time else old.time,
                    venue=if(fresh.venue!="NI")fresh.venue else old.venue,
                    score=if(fresh.score!="NI")fresh.score else old.score,
                    competition=if(fresh.competition!="NI")fresh.competition else old.competition,
                    cup=fresh.cup || old.cup
                )
            }
            slot.calendar=merged.values.take(80).toMutableList()
            slot.calendarCount=slot.calendar.size
            changed += parsed.size

            val future=slot.calendar.firstOrNull {'''
proc = replace_once(proc, old_cal, new_cal, "Processor incremental calendar")

tracker = re.sub(r'''            if \(bestSlot == 0 \|\| bestScore < 3\) \{\n                val remaining = \(1\.\.4\)\.filter \{ it !in used \}\n                if \(remaining\.size == 1\) \{\n                    bestSlot = remaining\.first\(\)\n                    bestScore = 3\n                \}\n            \}\n\n''', '', tracker, count=1)
tracker = replace_once(tracker,
'''            if (bestSlot in 1..4 && bestScore >= 3) {
                used += bestSlot''',
'''            val ranked = signatures.map { it.slotId to score(joined,it) }.sortedByDescending { it.second }
            val secondScore = ranked.getOrNull(1)?.second ?: 0
            val confident = bestSlot in 1..4 && bestScore >= 5 && (bestScore-secondScore >= 2 || bestScore >= 14)

            if (confident) {
                used += bestSlot''',
"Tracker confidence")
tracker = replace_once(tracker,
'        score += minOf(sig.tokens.count { it in tokens }, 12)',
'        score += minOf(sig.tokens.count { it in tokens }, 12) * 2',
"Tracker token score")
tracker = replace_once(tracker,
'''        if (rounds in 3..6 && leagueWords >= 2) return true
        if (rounds >= 3 && n.contains("leandrozzy")) return true''',
'''        if (rounds in 3..8 && leagueWords >= 1) return true
        if (rounds >= 3 && n.contains("leandrozzy")) return true
        if (rounds >= 3 && listOf("slot","manager","treinador","liga","batalha").any { n.contains(it) }) return true''',
"Tracker hub detection")

gradle = re.sub(r'versionName\s*=\s*"[^"]*\$runNumber"', 'versionName = "4.0.$runNumber"', gradle, count=1)
gradle = gradle.replace("// V15: versionCode monotônico por minuto.", "// V22: versionCode monotônico por minuto.")

changed=0
changed += write_if_changed(main_p, main)
changed += write_if_changed(svc_p, svc)
changed += write_if_changed(proc_p, proc)
changed += write_if_changed(tracker_p, tracker)
changed += write_if_changed(gradle_p, gradle)

print()
print("V22 aplicada com sucesso.")
print("Arquivos alterados:", changed)


# =============================================================================
# V23.1 — correção de compilação + captura SystemUI + rastreamento dos 4 slots
# =============================================================================
print("\\nAplicando V23.1...")

svc = replace_once(svc,
'''    private fun shouldKeepCapturing(): Boolean {
        val pkg = detectForegroundPackage() ?: CollectorState.currentForegroundPackage
        return pkg == OSM_PACKAGE
    }''',
'''    private fun shouldKeepCapturing(): Boolean {
        if (isExplicitSessionArmed()) return true
        val pkg = detectForegroundPackage() ?: CollectorState.currentForegroundPackage
        return pkg == OSM_PACKAGE
    }''',
"V23.1 explicit-session capture")

tracker = re.sub(
r'''    private fun learnHubSignatures\(text: String, signatures: MutableList<Signature>, slots: List<NativeSlotData>\) \{.*?^    private fun looksLikeCompetition''',
r'''    private fun learnHubSignatures(text: String, signatures: MutableList<Signature>, slots: List<NativeSlotData>) {
        val normalized = normalize(text)

        val anchors = Regex("""\b\d{1,2}\s*/\s*\d{1,2}\b""")
            .findAll(normalized)
            .take(4)
            .toList()

        if (anchors.size >= 3) {
            anchors.forEachIndexed { slotIndex, anchor ->
                if (slotIndex > 3) return@forEachIndexed

                val left = if (slotIndex == 0) 0 else
                    ((anchors[slotIndex - 1].range.last + anchor.range.first) / 2).coerceAtLeast(0)

                val right = if (slotIndex == anchors.lastIndex) normalized.length else
                    ((anchor.range.last + anchors[slotIndex + 1].range.first) / 2)
                        .coerceAtMost(normalized.length)

                if (right > left) {
                    val chunk = normalized.substring(left, right)
                    addUsefulTokens(signatures[slotIndex].tokens, chunk)
                }
            }
        }

        val lines = text.replace("|", "\n").lines()
            .map { line -> line.trim() }
            .filter { line -> line.length in 2..100 }

        val roundIndices = lines.indices.filter { idx ->
            Regex("""\b\d{1,2}\s*/\s*\d{1,2}\b""")
                .containsMatchIn(normalize(lines[idx]))
        }

        if (roundIndices.size >= 3) {
            roundIndices.take(4).forEachIndexed { slotIndex, lineIndex ->
                val from = maxOf(0, lineIndex - 5)
                val to = minOf(lines.size - 1, lineIndex + 4)
                val chunk = lines.subList(from, to + 1).joinToString(" ")
                addUsefulTokens(signatures[slotIndex].tokens, chunk)

                if (signatures[slotIndex].team == "NI") {
                    lines.subList(from, lineIndex + 1).asReversed()
                        .firstOrNull { line -> looksLikeName(line) && !looksLikeCompetition(line) }
                        ?.let { name -> signatures[slotIndex].team = name }
                }

                if (signatures[slotIndex].competition == "NI") {
                    lines.subList(from, to + 1)
                        .firstOrNull { line -> looksLikeCompetition(line) }
                        ?.let { competition -> signatures[slotIndex].competition = competition }
                }
            }
        }

        signatures.forEachIndexed { index, signature ->
            slots.getOrNull(index)?.let { oldSlot ->
                if (signature.team == "NI" && oldSlot.team != "NI") signature.team = oldSlot.team
                if (signature.competition == "NI" && oldSlot.competition != "NI") {
                    signature.competition = oldSlot.competition
                }
                addUsefulTokens(signature.tokens, oldSlot.team)
                addUsefulTokens(signature.tokens, oldSlot.competition)
            }
        }
    }

    private fun looksLikeCompetition''',
tracker,
count=1,
flags=re.S|re.M
)

tracker = tracker.replace(
'''            val confident = bestSlot in 1..4 && bestScore >= 5 && (bestScore-secondScore >= 2 || bestScore >= 14)''',
'''            val confident = bestSlot in 1..4 && bestScore >= 2 && (bestScore-secondScore >= 1 || bestScore >= 10)'''
)

needle = '''        val frameToSlot = tracked.frameToSlot.toMutableMap()
        total = session.frames.size + segments.count { it.isNotEmpty() } * 3
'''
replacement = '''        val frameToSlot = tracked.frameToSlot.toMutableMap()
        total = session.frames.size + segments.count { it.isNotEmpty() } * 3

        if (segments.all { rows -> rows.isEmpty() }) {
            prefs.edit()
                .putString("slot_tracker_summary", tracked.summary)
                .putInt("slot_hub_frames", tracked.hubFrames.size)
                .putInt("slot_unassigned_frames", session.frames.size)
                .apply()
            val message = "Nenhuma visita pôde ser ligada a S1-S4. A sessão foi preservada."
            onProgress(Progress(false,total,total,"Sessão preservada — rastreamento pendente",0,1,"slot-map",message))
            return@withContext true
        }
'''
proc = replace_once(proc, needle, replacement, "V23.1 slot-map diagnostic")

main = re.sub(
    r'DetailLine\("Versão nativa","V\d+(?:\.\d+)?\s*·\s*\$\{BuildConfig\.VERSION_NAME\}"\)',
    'DetailLine("Versão nativa","V23.1 · ${BuildConfig.VERSION_NAME}")',
    main,
    count=1
)
gradle = re.sub(r'versionName\s*=\s*"[^"]*\$runNumber"', 'versionName = "4.1.$runNumber"', gradle, count=1)

changed_v231=0
changed_v231 += write_if_changed(main_p, main)
changed_v231 += write_if_changed(svc_p, svc)
changed_v231 += write_if_changed(proc_p, proc)
changed_v231 += write_if_changed(tracker_p, tracker)
changed_v231 += write_if_changed(gradle_p, gradle)
print("V23.1 aplicada com sucesso. Arquivos alterados:", changed_v231)
