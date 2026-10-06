from pathlib import Path

R = Path(".")
pkg = R / "android-collector/app/src/main/java/com/osmaicoach/collector"

gradle = R / "android-collector/app/build.gradle.kts"
g = gradle.read_text(encoding="utf-8")
dep = '    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")\n'
if dep.strip() not in g:
    g = g.replace('    implementation("androidx.core:core-ktx:1.15.0")\n',
                  '    implementation("androidx.core:core-ktx:1.15.0")\n'+dep)
gradle.write_text(g, encoding="utf-8")

repo = pkg / "SessionRepository.kt"
s = repo.read_text(encoding="utf-8")
needle = "    fun latestFrameBase64(index:Int):String?{"
if needle not in s:
    needle = "    fun latestFrameBase64(index: Int): String? {"
insert = """
    fun frameFile(session: CaptureSession, index: Int): File? {
        val frame = session.frames.firstOrNull { it.index == index } ?: return null
        val file = File(File(root, session.id), frame.fileName)
        return file.takeIf { it.exists() }
    }

"""
if "fun frameFile(session:" not in s:
    pos = s.find(needle)
    if pos < 0:
        raise SystemExit("latestFrameBase64 não encontrado")
    s = s[:pos] + insert + s[pos:]
repo.write_text(s, encoding="utf-8")

(pkg / "NativeSessionProcessor.kt").write_text(r"""package com.osmaicoach.collector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class NativeSessionProcessor(
    private val context: Context,
    private val repository: SessionRepository,
    private val store: NativeSlotStore
) {
    data class Progress(
        val running: Boolean = false,
        val current: Int = 0,
        val total: Int = 0,
        val label: String = "",
        val success: Int = 0,
        val failed: Int = 0
    )

    private val prefs = context.getSharedPreferences("native_processor_v4", Context.MODE_PRIVATE)

    suspend fun processLatest(onProgress:(Progress)->Unit): Boolean = withContext(Dispatchers.IO) {
        val session = repository.latestSession() ?: return@withContext false
        if (session.state != "ready" || session.frames.isEmpty()) return@withContext false
        if (prefs.getString("processed_session", null) == session.id) return@withContext false

        val segments = splitIntoSlots(session)
        if (segments.isEmpty()) return@withContext false

        val slots = store.loadAll()
        var success = 0
        var failed = 0
        val total = segments.size * 3
        var current = 0

        onProgress(Progress(true,0,total,"Organizando ${session.frames.size} telas",0,0))

        for ((segIndex, indices) in segments.withIndex()) {
            val slotIndex = segIndex.coerceIn(0,3)
            val slot = slots[slotIndex]

            coroutineScope {
                val matchJob = async { analyzeType(session, indices, "match", 3, slot) }
                val squadJob = async { analyzeType(session, indices, "squad", 2, slot) }
                val calendarJob = async { analyzeType(session, indices, "calendar", 2, slot) }

                val results = listOf(
                    "Pré-jogo" to matchJob.await(),
                    "Elenco" to squadJob.await(),
                    "Calendário" to calendarJob.await()
                )

                for ((label, result) in results) {
                    current++
                    if (result != null) {
                        runCatching { applyResult(slot, result.first, result.second) }
                            .onSuccess { success++ }
                            .onFailure { failed++ }
                    } else failed++

                    slot.lastUpdated = System.currentTimeMillis()
                    store.saveAll(slots)
                    onProgress(Progress(true,current,total,"S${slot.id} · $label",success,failed))
                }
            }

            val segmentFrames = indices.mapNotNull { idx -> session.frames.firstOrNull { it.index == idx } }
            slot.marketSeen = slot.marketSeen || segmentFrames.any { it.screenType == "market" }
            slot.trainingSeen = slot.trainingSeen || segmentFrames.any { it.screenType == "training" }
            store.saveAll(slots)
        }

        prefs.edit().putString("processed_session", session.id).apply()
        onProgress(Progress(false,total,total,"Concluído",success,failed))
        true
    }

    private fun splitIntoSlots(session: CaptureSession): List<List<Int>> {
        val frames = session.frames.sortedBy { it.capturedAt }
        if (frames.isEmpty()) return emptyList()
        val gaps = mutableListOf<Pair<Int,Long>>()
        for (i in 1 until frames.size) gaps += i to (frames[i].capturedAt - frames[i-1].capturedAt).coerceAtLeast(0)

        val cuts = gaps.filter { it.second >= 2200L }
            .sortedByDescending { it.second }.take(3)
            .map { it.first }.sorted()

        val result = mutableListOf<List<Int>>()
        if (cuts.isNotEmpty()) {
            var from = 0
            for (cut in cuts + frames.size) {
                if (cut > from) result += frames.subList(from,cut).map { it.index }
                from = cut
            }
        }

        if (result.size < 2 && frames.size >= 16) {
            result.clear()
            val groups = minOf(4,maxOf(1,frames.size/8))
            var from = 0
            for (i in 0 until groups) {
                val to = if (i == groups-1) frames.size else ((i+1)*frames.size/groups)
                result += frames.subList(from,to).map { it.index }
                from = to
            }
        }

        if (result.isEmpty()) result += frames.map { it.index }
        return result.take(4)
    }

    private suspend fun analyzeType(
        session: CaptureSession,
        indices: List<Int>,
        type: String,
        maxImages: Int,
        slot: NativeSlotData
    ): Pair<String,JSONObject>? = withContext(Dispatchers.IO) {
        val typed = indices.filter { idx ->
            val frame = session.frames.firstOrNull { it.index == idx }
            frame != null && (frame.screenType == type || frame.screenType == "other")
        }
        val selected = sample(if (typed.isNotEmpty()) typed else indices,maxImages)
        if (selected.isEmpty()) return@withContext null

        val images = JSONArray()
        selected.forEach { index ->
            val file = repository.frameFile(session,index) ?: return@forEach
            val encoded = encodeImage(file.absolutePath) ?: return@forEach
            images.put(JSONObject().apply {
                put("url",encoded.first)
                put("width",encoded.second.first)
                put("height",encoded.second.second)
                put("frameIndex",index)
            })
        }
        if (images.length() == 0) return@withContext null

        val body = JSONObject().apply {
            put("type",type)
            put("images",images)
            put("ocrImages",images)
            put("forceOCR",true)
            put("useVisual",true)
            put("context",JSONObject().apply {
                put("username","leandrozzy")
                if (slot.team != "NI") put("myTeam",slot.team)
                put("competitionType",slot.competitionType)
                if (slot.nextRival != "NI") put("rivalName",slot.nextRival)
            })
        }

        val endpoint = BuildConfig.BACKEND_URL.trimEnd('/') + "/api/analyze"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod="POST"
            connectTimeout=15000
            readTimeout=45000
            doOutput=true
            setRequestProperty("Content-Type","application/json")
            setRequestProperty("Accept","application/json")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val raw = runCatching {
            (if(code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
        }.getOrDefault("")
        conn.disconnect()

        if (code !in 200..299 || raw.isBlank()) return@withContext null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext null
        val data = root.optJSONObject("data") ?: return@withContext null
        type to data
    }

    private fun applyResult(slot:NativeSlotData,type:String,data:JSONObject) {
        when(type) {
            "match" -> applyMatch(slot,data.optJSONObject("match") ?: JSONObject())
            "squad" -> applySquad(slot,data)
            "calendar" -> applyCalendar(slot,data)
        }
    }

    private fun applyMatch(slot:NativeSlotData,m:JSONObject) {
        putIfKnown(m,"myName"){slot.team=it}
        putIfKnown(m,"rivalName"){slot.nextRival=it}
        putIfKnown(m,"location"){slot.venue=it}
        putIfKnown(m,"referee"){slot.referee=it}
        putIfKnown(m,"myStrength"){slot.myStrength=it}
        putIfKnown(m,"rivalStrength"){slot.rivalStrength=it}
        putIfKnown(m,"mySquadValue"){slot.myValue=it}
        putIfKnown(m,"rivalSquadValue"){slot.rivalValue=it}
        putIfKnown(m,"myGK"){slot.myGoalkeeper=it}
        putIfKnown(m,"myDEF"){slot.myDefense=it}
        putIfKnown(m,"myMID"){slot.myMidfield=it}
        putIfKnown(m,"myATT"){slot.myAttack=it}
        putIfKnown(m,"rivalGK"){slot.rivalGoalkeeper=it}
        putIfKnown(m,"rivalDEF"){slot.rivalDefense=it}
        putIfKnown(m,"rivalMID"){slot.rivalMidfield=it}
        putIfKnown(m,"rivalATT"){slot.rivalAttack=it}
        putIfKnown(m,"rivalFormation"){slot.rivalFormation=it}
        putIfKnown(m,"rivalPlan"){slot.rivalPlan=it}
        putIfKnown(m,"rivalMarking"){slot.marking=it}
        putIfKnown(m,"rivalOffside"){slot.offside=it}
        putIfKnown(m,"secretTraining"){slot.secretTraining=it}
        putIfKnown(m,"trainingCamp"){slot.trainingCamp=it}
        putIfKnown(m,"stadium"){slot.stadium=it}
        putIfKnown(m,"myBonus"){slot.bonus=it}
    }

    private fun applySquad(slot:NativeSlotData,data:JSONObject) {
        val meta=data.optJSONObject("meta") ?: JSONObject()
        putIfKnown(meta,"team"){slot.team=it}
        putIfKnown(meta,"squadValue"){slot.myValue=it}
        putIfKnown(meta,"strength"){slot.myStrength=it}
        putIfKnown(meta,"GK"){slot.myGoalkeeper=it}
        putIfKnown(meta,"DEF"){slot.myDefense=it}
        putIfKnown(meta,"MID"){slot.myMidfield=it}
        putIfKnown(meta,"ATT"){slot.myAttack=it}

        val players=data.optJSONArray("players") ?: JSONArray()
        if(players.length()>0) {
            slot.squadCount=players.length()
            var ata=0;var mei=0;var def=0;var gol=0;var training=0;var selling=0
            for(i in 0 until players.length()) {
                val p=players.optJSONObject(i) ?: continue
                val pos=p.optString("position").uppercase(Locale.ROOT)
                when {
                    pos.contains("ATA")||pos.contains("ATT")||pos.contains("FOR")->ata++
                    pos.contains("MEI")||pos.contains("MID")->mei++
                    pos.contains("DEF")->def++
                    pos.contains("GOL")||pos.contains("GK")->gol++
                }
                if(p.optString("training").equals("Sim",true)||p.optBoolean("training",false))training++
                if(p.optString("forSale").equals("Sim",true)||p.optBoolean("forSale",false))selling++
            }
            slot.attackers=ata;slot.midfielders=mei;slot.defenders=def;slot.goalkeepers=gol
            slot.trainingCount=training;slot.sellingCount=selling
        }
    }

    private fun applyCalendar(slot:NativeSlotData,data:JSONObject) {
        val meta=data.optJSONObject("meta") ?: JSONObject()
        putIfKnown(meta,"team"){slot.team=it}
        putIfKnown(meta,"competition"){slot.competition=it}
        putIfKnown(meta,"competitionType"){slot.competitionType=it}

        val rows=data.optJSONArray("calendar") ?: JSONArray()
        if(rows.length()==0)return
        slot.calendarCount=rows.length()
        var chosen:JSONObject?=null
        for(i in 0 until rows.length()) {
            val row=rows.optJSONObject(i) ?: continue
            val score=row.optString("score")
            if(score.isBlank()||score=="NI"){chosen=row;break}
        }
        if(chosen==null)chosen=rows.optJSONObject(rows.length()-1)
        chosen?.let { row ->
            putIfKnown(row,"opponent"){slot.nextRival=it}
            putIfKnown(row,"date"){slot.matchDate=it}
            putIfKnown(row,"time"){slot.matchTime=it}
            if(row.has("home")&&!row.isNull("home"))slot.venue=if(row.optBoolean("home"))"Casa" else "Fora"
        }
    }

    private fun putIfKnown(o:JSONObject,key:String,block:(String)->Unit){
        if(!o.has(key)||o.isNull(key))return
        val value=o.opt(key)?.toString()?.trim().orEmpty()
        if(value.isNotBlank()&&value!="NI"&&value!="null")block(value)
    }

    private fun sample(rows:List<Int>,max:Int):List<Int>{
        if(rows.size<=max)return rows
        if(max==1)return listOf(rows[rows.size/2])
        return (0 until max).map{i->rows[((i.toDouble()*(rows.size-1))/(max-1)).toInt()]}.distinct()
    }

    private fun encodeImage(path:String):Pair<String,Pair<Int,Int>>?{
        val original=BitmapFactory.decodeFile(path) ?: return null
        val maxWidth=1080
        val scaled=if(original.width>maxWidth){
            val h=(original.height*(maxWidth.toFloat()/original.width)).toInt()
            Bitmap.createScaledBitmap(original,maxWidth,h,true)
        }else original

        val width=scaled.width
        val height=scaled.height
        var quality=72
        var bytes:ByteArray
        do{
            val out=ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG,quality,out)
            bytes=out.toByteArray()
            quality-=8
        }while(bytes.size>900_000&&quality>=40)

        if(scaled!==original)scaled.recycle()
        original.recycle()

        return "data:image/jpeg;base64,"+Base64.encodeToString(bytes,Base64.NO_WRAP) to (width to height)
    }
}
""", encoding="utf-8")

main = pkg / "MainActivity.kt"
m = main.read_text(encoding="utf-8")

start = m.find("    private fun isAccessibilityServiceEnabledRobust():Boolean {")
end = m.find("\n    private fun completion(", start)
if start < 0 or end < 0:
    raise SystemExit("Accessibility function not found")

new_fn = r"""    private fun isAccessibilityServiceEnabledRobust():Boolean {
        if (CollectorState.isServiceReady()) return true

        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val byManager = runCatching {
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info -> info.resolveInfo?.serviceInfo?.packageName == packageName }
        }.getOrDefault(false)
        if (byManager) return true

        val setting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        if (setting.split(':').any { it.contains(packageName, ignoreCase=true) }) return true

        if (repo.listSessions(1).isNotEmpty()) return true

        return getSharedPreferences("collector_runtime",Context.MODE_PRIVATE)
            .getBoolean("accessibility_connected",false)
    }
"""
m = m[:start] + new_fn + m[end:]

needle_state = "        var refresh by remember { mutableIntStateOf(0) }\n"
replacement_state = """        var refresh by remember { mutableIntStateOf(0) }
        var processing by remember { mutableStateOf(NativeSessionProcessor.Progress()) }
"""
if needle_state in m and "var processing by remember" not in m:
    m = m.replace(needle_state,replacement_state,1)

needle_loop = """        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(1200)
                refresh++
            }
        }
"""
addition = needle_loop + """
        LaunchedEffect(refresh) {
            val latestNow = repo.latestSession()
            if (latestNow?.state == "ready" && !processing.running) {
                val processor = NativeSessionProcessor(this@MainActivity, repo, slotStore)
                processor.processLatest { p ->
                    runOnUiThread {
                        processing = p
                        refresh++
                    }
                }
            }
        }
"""
if needle_loop in m and "NativeSessionProcessor(this@MainActivity" not in m:
    m = m.replace(needle_loop,addition,1)

m = m.replace(
    "0 -> TodayScreen(serviceReady, recording, latest) { openOsm() }",
    "0 -> TodayScreen(serviceReady, recording, latest, processing) { openOsm() }",
    1
)
m = m.replace(
    "private fun TodayScreen(serviceReady:Boolean, recording:Boolean, latest:CaptureSession?, openOsm:()->Unit)",
    "private fun TodayScreen(serviceReady:Boolean, recording:Boolean, latest:CaptureSession?, processing:NativeSessionProcessor.Progress, openOsm:()->Unit)",
    1
)

marker = "            item { SessionSummaryCard(latest) }\n"
if marker in m and "Processando sessão" not in m:
    progress_card = """            if(processing.running || processing.total > 0) {
                item {
                    Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFEFF5E5)),shape=RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(18.dp)) {
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                Text("Processando sessão",fontWeight=FontWeight.Bold)
                                Text("${processing.current}/${processing.total}",fontSize=12.sp)
                            }
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress={if(processing.total>0) processing.current.toFloat()/processing.total else 0f},
                                modifier=Modifier.fillMaxWidth().height(8.dp),
                                color=Color(0xFF7AA526),
                                trackColor=Color(0xFFDDE5D1)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(processing.label,fontSize=13.sp)
                            Text("Aplicados: ${processing.success} • Falhas: ${processing.failed}",fontSize=12.sp,color=Color.Gray)
                        }
                    }
                }
            }
            item { SessionSummaryCard(latest) }
"""
    m = m.replace(marker,progress_card,1)

main.write_text(m,encoding="utf-8")

print("Native Pro v4 applied.")
