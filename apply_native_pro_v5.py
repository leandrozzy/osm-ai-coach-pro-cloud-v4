from pathlib import Path

R = Path(".")
pkg = R / "android-collector/app/src/main/java/com/osmaicoach/collector"

processor = pkg / "NativeSessionProcessor.kt"
processor.write_text(r"""package com.osmaicoach.collector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
        val failed: Int = 0,
        val stage: String = "idle",
        val lastError: String = ""
    )

    private val prefs = context.getSharedPreferences("native_processor_v5", Context.MODE_PRIVATE)

    fun resetLatest() {
        prefs.edit().remove("processed_session").apply()
    }

    fun isProcessed(sessionId:String):Boolean =
        prefs.getString("processed_session", null) == sessionId

    suspend fun processLatest(
        force:Boolean = false,
        onProgress:(Progress)->Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val session = repository.latestSession() ?: return@withContext false
        if (session.state != "ready" || session.frames.isEmpty()) return@withContext false
        if (!force && isProcessed(session.id)) return@withContext false

        val segments = splitIntoSlots(session)
        if (segments.isEmpty()) return@withContext false

        val slots = store.loadAll()
        val jobs = mutableListOf<Triple<Int,String,List<Int>>>()
        segments.forEachIndexed { index, rows ->
            jobs += Triple(index, "match", rows)
            jobs += Triple(index, "squad", rows)
            jobs += Triple(index, "calendar", rows)
        }

        var success = 0
        var failed = 0
        var lastError = ""

        onProgress(Progress(true,0,jobs.size,"Preparando ${session.frames.size} telas",0,0,"prepare",""))

        jobs.forEachIndexed { index, job ->
            val slot = slots[job.first.coerceIn(0,3)]
            val type = job.second
            val friendly = when(type) {
                "match" -> "Pré-jogo"
                "squad" -> "Elenco"
                else -> "Calendário"
            }

            onProgress(Progress(
                true,index,jobs.size,
                "S${slot.id} · $friendly · enviando",
                success,failed,"upload",lastError
            ))

            val result = withTimeoutOrNull(30000L) {
                analyzeType(session, job.third, type, 2, slot)
            }

            if (result == null) {
                failed++
                lastError = "S${slot.id} $friendly: tempo limite ou backend sem resposta"
            } else {
                runCatching { applyResult(slot,result.first,result.second) }
                    .onSuccess { success++ }
                    .onFailure {
                        failed++
                        lastError = "S${slot.id} $friendly: ${it.message ?: "falha ao aplicar"}"
                    }
            }

            slot.lastUpdated = System.currentTimeMillis()
            val segmentFrames = job.third.mapNotNull { idx -> session.frames.firstOrNull { it.index == idx } }
            slot.marketSeen = slot.marketSeen || segmentFrames.any { it.screenType == "market" }
            slot.trainingSeen = slot.trainingSeen || segmentFrames.any { it.screenType == "training" }
            store.saveAll(slots)

            val done = index + 1
            onProgress(Progress(
                done < jobs.size,done,jobs.size,
                "S${slot.id} · $friendly · ${if(result!=null) "concluído" else "falhou"}",
                success,failed,if(done<jobs.size)"apply" else "done",lastError
            ))
        }

        prefs.edit().putString("processed_session",session.id).apply()
        onProgress(Progress(false,jobs.size,jobs.size,"Sessão finalizada",success,failed,"done",lastError))
        true
    }

    private fun splitIntoSlots(session:CaptureSession):List<List<Int>> {
        val frames=session.frames.sortedBy{it.capturedAt}
        if(frames.isEmpty())return emptyList()

        val gaps=mutableListOf<Pair<Int,Long>>()
        for(i in 1 until frames.size){
            gaps += i to (frames[i].capturedAt-frames[i-1].capturedAt).coerceAtLeast(0)
        }

        val cuts=gaps.filter{it.second>=2200L}
            .sortedByDescending{it.second}.take(3)
            .map{it.first}.sorted()

        val result=mutableListOf<List<Int>>()
        if(cuts.isNotEmpty()){
            var from=0
            for(cut in cuts+frames.size){
                if(cut>from)result += frames.subList(from,cut).map{it.index}
                from=cut
            }
        }

        if(result.size<2 && frames.size>=16){
            result.clear()
            var from=0
            for(i in 0 until 4){
                val to=if(i==3)frames.size else ((i+1)*frames.size/4)
                result += frames.subList(from,to).map{it.index}
                from=to
            }
        }

        if(result.isEmpty())result += frames.map{it.index}
        return result.take(4)
    }

    private suspend fun analyzeType(
        session:CaptureSession,
        indices:List<Int>,
        type:String,
        maxImages:Int,
        slot:NativeSlotData
    ):Pair<String,JSONObject>? = withContext(Dispatchers.IO) {
        val exact=indices.filter{idx->
            val f=session.frames.firstOrNull{it.index==idx}
            f!=null && f.screenType==type
        }
        val unknown=indices.filter{idx->
            val f=session.frames.firstOrNull{it.index==idx}
            f!=null && f.screenType=="other"
        }
        val pool=when{
            exact.isNotEmpty()->exact
            unknown.isNotEmpty()->unknown
            else->indices
        }
        val selected=sample(pool,maxImages)
        if(selected.isEmpty())return@withContext null

        val images=JSONArray()
        selected.forEach{index->
            val file=repository.frameFile(session,index) ?: return@forEach
            val encoded=encodeImage(file.absolutePath) ?: return@forEach
            images.put(JSONObject().apply{
                put("url",encoded.first)
                put("width",encoded.second.first)
                put("height",encoded.second.second)
                put("frameIndex",index)
            })
        }
        if(images.length()==0)return@withContext null

        val body=JSONObject().apply{
            put("type",type)
            put("images",images)
            put("ocrImages",images)
            put("forceOCR",true)
            put("useVisual",true)
            put("context",JSONObject().apply{
                put("username","leandrozzy")
                if(slot.team!="NI")put("myTeam",slot.team)
                put("competitionType",slot.competitionType)
                if(slot.nextRival!="NI")put("rivalName",slot.nextRival)
            })
        }

        val endpoint=BuildConfig.BACKEND_URL.trimEnd('/')+"/api/analyze"
        val conn=(URL(endpoint).openConnection() as HttpURLConnection).apply{
            requestMethod="POST"
            connectTimeout=10000
            readTimeout=22000
            doOutput=true
            instanceFollowRedirects=true
            setRequestProperty("Content-Type","application/json")
            setRequestProperty("Accept","application/json")
            setRequestProperty("User-Agent","OSM-AI-Coach-Native/5")
        }

        runCatching{
            conn.outputStream.use{
                val bytes=body.toString().toByteArray(Charsets.UTF_8)
                it.write(bytes)
                it.flush()
            }
        }.getOrElse{
            conn.disconnect()
            return@withContext null
        }

        val code=runCatching{conn.responseCode}.getOrElse{
            conn.disconnect(); return@withContext null
        }
        val raw=runCatching{
            (if(code in 200..299)conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use{it.readText()}.orEmpty()
        }.getOrDefault("")
        conn.disconnect()

        if(code !in 200..299 || raw.isBlank())return@withContext null
        val root=runCatching{JSONObject(raw)}.getOrNull() ?: return@withContext null
        val data=root.optJSONObject("data") ?: return@withContext null
        type to data
    }

    private fun applyResult(slot:NativeSlotData,type:String,data:JSONObject){
        when(type){
            "match"->applyMatch(slot,data.optJSONObject("match")?:JSONObject())
            "squad"->applySquad(slot,data)
            "calendar"->applyCalendar(slot,data)
        }
    }

    private fun applyMatch(slot:NativeSlotData,m:JSONObject){
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

    private fun applySquad(slot:NativeSlotData,data:JSONObject){
        val meta=data.optJSONObject("meta")?:JSONObject()
        putIfKnown(meta,"team"){slot.team=it}
        putIfKnown(meta,"competition"){slot.competition=it}
        putIfKnown(meta,"competitionType"){slot.competitionType=it}
        putIfKnown(meta,"squadValue"){slot.myValue=it}
        putIfKnown(meta,"strength"){slot.myStrength=it}
        putIfKnown(meta,"GK"){slot.myGoalkeeper=it}
        putIfKnown(meta,"DEF"){slot.myDefense=it}
        putIfKnown(meta,"MID"){slot.myMidfield=it}
        putIfKnown(meta,"ATT"){slot.myAttack=it}

        val players=data.optJSONArray("players")?:JSONArray()
        if(players.length()>0){
            slot.squadCount=players.length()
            var ata=0;var mei=0;var def=0;var gol=0;var training=0;var selling=0
            for(i in 0 until players.length()){
                val p=players.optJSONObject(i)?:continue
                val pos=p.optString("position").uppercase(Locale.ROOT)
                when{
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

    private fun applyCalendar(slot:NativeSlotData,data:JSONObject){
        val meta=data.optJSONObject("meta")?:JSONObject()
        putIfKnown(meta,"team"){slot.team=it}
        putIfKnown(meta,"competition"){slot.competition=it}
        putIfKnown(meta,"competitionType"){slot.competitionType=it}

        val rows=data.optJSONArray("calendar")?:JSONArray()
        if(rows.length()==0)return
        slot.calendarCount=rows.length()

        var chosen:JSONObject?=null
        for(i in 0 until rows.length()){
            val row=rows.optJSONObject(i)?:continue
            val score=row.optString("score")
            if(score.isBlank()||score=="NI"){chosen=row;break}
        }
        if(chosen==null)chosen=rows.optJSONObject(rows.length()-1)
        chosen?.let{row->
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
        if(max<=1)return listOf(rows[rows.size/2])
        return (0 until max).map{i->rows[((i.toDouble()*(rows.size-1))/(max-1)).toInt()]}.distinct()
    }

    private fun encodeImage(path:String):Pair<String,Pair<Int,Int>>?{
        val original=BitmapFactory.decodeFile(path)?:return null
        val targetWidth=minOf(900,original.width)
        val targetHeight=(original.height*(targetWidth.toFloat()/original.width)).toInt().coerceAtLeast(1)
        val scaled=if(original.width!=targetWidth)Bitmap.createScaledBitmap(original,targetWidth,targetHeight,true) else original
        val width=scaled.width
        val height=scaled.height

        var quality=62
        var bytes:ByteArray
        do{
            val out=ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG,quality,out)
            bytes=out.toByteArray()
            quality-=7
        }while(bytes.size>450_000&&quality>=34)

        if(scaled!==original)scaled.recycle()
        original.recycle()

        return "data:image/jpeg;base64,"+Base64.encodeToString(bytes,Base64.NO_WRAP) to (width to height)
    }
}
""", encoding="utf-8")

main = pkg / "MainActivity.kt"
m = main.read_text(encoding="utf-8")

state_needle = "        var processing by remember { mutableStateOf(NativeSessionProcessor.Progress()) }\n"
if state_needle in m and "var forceProcessToken" not in m:
    m = m.replace(state_needle, state_needle + "        var forceProcessToken by remember { mutableIntStateOf(0) }\n", 1)

start = m.find("        LaunchedEffect(refresh) {")
end = m.find("\n\n        val serviceReady", start)
if start < 0 or end < 0:
    raise SystemExit("processing LaunchedEffect não encontrado")
new_effect = """        LaunchedEffect(refresh, forceProcessToken) {
            val latestNow = repo.latestSession()
            if (latestNow?.state == "ready" && !processing.running) {
                val processor = NativeSessionProcessor(this@MainActivity, repo, slotStore)
                processor.processLatest(force=forceProcessToken > 0) { p ->
                    runOnUiThread {
                        processing = p
                        refresh++
                    }
                }
            }
        }"""
m = m[:start] + new_effect + m[end:]

m = m.replace(
    "0 -> TodayScreen(serviceReady, recording, latest, processing) { openOsm() }",
    "0 -> TodayScreen(serviceReady, recording, latest, processing, slots, onReprocess={ forceProcessToken++ }) { openOsm() }",
    1
)
m = m.replace(
    "else -> SettingsScreen(serviceReady)",
    "else -> SettingsScreen(serviceReady, latest, processing, onReprocess={ forceProcessToken++ })",
    1
)

start = m.find("    @Composable\n    private fun TodayScreen(")
end = m.find("\n    @Composable\n    private fun SessionSummaryCard", start)
if start < 0 or end < 0:
    raise SystemExit("TodayScreen não encontrado")

today = r"""    @Composable
    private fun TodayScreen(
        serviceReady:Boolean,
        recording:Boolean,
        latest:CaptureSession?,
        processing:NativeSessionProcessor.Progress,
        slots:List<NativeSlotData>,
        onReprocess:()->Unit,
        openOsm:()->Unit
    ) {
        LazyColumn(contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item {
                Text("CENTRO DE COMANDO",fontSize=11.sp,color=Color(0xFF718078),fontWeight=FontWeight.Bold)
                Text("Seu OSM, em um só lugar.",fontSize=30.sp,fontWeight=FontWeight.Bold,color=Color(0xFF17242B))
                Text("Jogue normalmente. O Coach registra a sessão e atualiza seus quatro slots.",color=Color(0xFF68777E))
            }

            item {
                Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF122630)),shape=RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Icon(if(recording)Icons.Default.FiberManualRecord else Icons.Default.Verified,null,
                                tint=if(recording)Color(0xFFFFC857) else Color(0xFFB8E34D))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if(recording)"Sessão sendo capturada" else "Leitura automática pronta",
                                    color=Color.White,fontWeight=FontWeight.Bold,fontSize=17.sp)
                                Text(if(recording)"Navegue livremente pelo OSM."
                                    else "Permissão ativa e pronta para nova sessão.",
                                    color=Color(0xFFB6C6CE),fontSize=12.sp)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick=openOsm,
                            enabled=serviceReady&&!recording&&!processing.running,
                            modifier=Modifier.fillMaxWidth().height(52.dp),
                            colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFB8E34D),contentColor=Color(0xFF122630))
                        ){
                            Icon(Icons.Default.PlayArrow,null);Spacer(Modifier.width(8.dp));Text("Abrir OSM",fontWeight=FontWeight.Bold)
                        }
                    }
                }
            }

            if(processing.running || processing.total>0) {
                item {
                    Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFEFF5E5)),shape=RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(18.dp)) {
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                Column {
                                    Text(if(processing.running)"Atualizando seus slots" else "Último processamento",fontWeight=FontWeight.Bold)
                                    Text(processing.label,fontSize=12.sp,color=Color.Gray)
                                }
                                Text("${processing.current}/${processing.total}",fontWeight=FontWeight.Bold)
                            }
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress={if(processing.total>0)processing.current.toFloat()/processing.total else 0f},
                                modifier=Modifier.fillMaxWidth().height(9.dp),
                                color=Color(0xFF7AA526),trackColor=Color(0xFFDDE5D1)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("Aplicados ${processing.success} • Falhas ${processing.failed}",fontSize=12.sp)
                            if(processing.lastError.isNotBlank()) {
                                Spacer(Modifier.height(5.dp))
                                Text(processing.lastError,fontSize=11.sp,color=Color(0xFF9C4D36))
                            }
                            if(!processing.running) {
                                Spacer(Modifier.height(12.dp))
                                OutlinedButton(onClick=onReprocess,modifier=Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Refresh,null);Spacer(Modifier.width(7.dp));Text("Reprocessar última sessão")
                                }
                            }
                        }
                    }
                }
            }

            item { Text("Seus slots",fontWeight=FontWeight.Bold,fontSize=20.sp) }

            items(slots) { slot ->
                val pct=completion(slot)
                Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(15.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Surface(shape=RoundedCornerShape(10.dp),color=Color(0xFF122630)) {
                                Text("S${slot.id}",Modifier.padding(horizontal=10.dp,vertical=8.dp),color=Color(0xFFB8E34D),fontWeight=FontWeight.Bold)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if(slot.team!="NI")slot.team else "Slot ${slot.id}",fontWeight=FontWeight.Bold)
                                Text(if(slot.competition!="NI")slot.competition else "Liga aguardando leitura",fontSize=11.sp,color=Color.Gray)
                            }
                            Text("$pct%",fontWeight=FontWeight.Bold)
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress={pct/100f},modifier=Modifier.fillMaxWidth().height(6.dp),
                            color=Color(0xFF7AA526),trackColor=Color(0xFFE8ECDF)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Próximo: ${slot.nextRival} • ${slot.matchDate} • ${slot.venue}",fontSize=12.sp,color=Color(0xFF56656B))
                    }
                }
            }

            item { SessionSummaryCard(latest) }
        }
    }
"""
m = m[:start] + today + m[end:]

start = m.find("    @Composable\n    private fun SlotOverview(")
end = m.find("\n    @Composable\n    private fun SlotPreGame", start)
if start < 0 or end < 0:
    raise SystemExit("SlotOverview não encontrado")

overview = r"""    @Composable
    private fun SlotOverview(slot:NativeSlotData) {
        LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item {
                Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF122630)),shape=RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("PRÓXIMO JOGO",fontSize=11.sp,color=Color(0xFFB8E34D),fontWeight=FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(if(slot.nextRival!="NI")slot.nextRival else "Rival ainda não identificado",
                            color=Color.White,fontSize=24.sp,fontWeight=FontWeight.Bold)
                        Text("${slot.matchDate} · ${slot.matchTime} · ${slot.venue}",color=Color(0xFFB7C8CF),fontSize=13.sp)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            QuickStat("Minha força",slot.myStrength,Modifier.weight(1f))
                            QuickStat("Rival",slot.rivalStrength,Modifier.weight(1f))
                            QuickStat("Árbitro",slot.referee,Modifier.weight(1f))
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Metric("${completion(slot)}%","Dados completos",Modifier.weight(1f))
                    Metric(slot.squadCount.toString(),"Jogadores",Modifier.weight(1f))
                }
            }
            item {
                Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Clube e competição",fontWeight=FontWeight.Bold,fontSize=17.sp)
                        Spacer(Modifier.height(10.dp))
                        DetailLine("Time",slot.team)
                        DetailLine("Competição",slot.competition)
                        DetailLine("Tipo",slot.competitionType)
                        DetailLine("Estádio",slot.stadium)
                        DetailLine("Bônus",slot.bonus)
                    }
                }
            }
            item {
                Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Ações rápidas",fontWeight=FontWeight.Bold,fontSize=17.sp)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            Button(onClick={},enabled=false,modifier=Modifier.weight(1f)){Text("Gerar tática")}
                            OutlinedButton(onClick={},enabled=false,modifier=Modifier.weight(1f)){Text("433 forte")}
                        }
                    }
                }
            }
            if(missingFields(slot).isNotBlank()) {
                item {
                    Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFFFF4D9)),shape=RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(18.dp)) {
                            Text("O que ainda falta",fontWeight=FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text(missingFields(slot),fontSize=13.sp)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun QuickStat(label:String,value:String,modifier:Modifier=Modifier) {
        Surface(modifier,shape=RoundedCornerShape(13.dp),color=Color(0xFF1E3440)) {
            Column(Modifier.padding(10.dp)) {
                Text(label,fontSize=10.sp,color=Color(0xFFAFC1C9))
                Text(value,fontWeight=FontWeight.Bold,color=Color.White,fontSize=14.sp,maxLines=1)
            }
        }
    }

    @Composable
    private fun DetailLine(label:String,value:String) {
        Row(Modifier.fillMaxWidth().padding(vertical=7.dp)) {
            Text(label,Modifier.weight(1f),fontSize=13.sp,color=Color.Gray)
            Text(value,fontSize=13.sp,fontWeight=FontWeight.SemiBold)
        }
        HorizontalDivider(color=Color(0xFFEEF0EA))
    }
"""
m = m[:start] + overview + m[end:]

start = m.find("    @Composable\n    private fun SettingsScreen(")
end = m.find("\n    @Composable\n    private fun Metric", start)
if start < 0 or end < 0:
    raise SystemExit("SettingsScreen não encontrado")

settings = r"""    @Composable
    private fun SettingsScreen(
        serviceReady:Boolean,
        latest:CaptureSession?,
        processing:NativeSessionProcessor.Progress,
        onReprocess:()->Unit
    ) {
        LazyColumn(contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item {
                Text("Configurações",fontSize=28.sp,fontWeight=FontWeight.Bold)
                Text("Leitura, IA, dados e diagnóstico do Coach.",color=Color.Gray)
            }
            item {
                SettingsCard("Leitura automática",Icons.Default.Visibility) {
                    SettingStatus("Serviço de acessibilidade",if(serviceReady)"Ativo" else "Desativado",serviceReady)
                    SettingStatus("Captura do OSM",if(CollectorState.isRecording())"Gravando" else "Pronta",true)
                    if(!serviceReady) {
                        Spacer(Modifier.height(10.dp))
                        Button(onClick={startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))},modifier=Modifier.fillMaxWidth()) {
                            Text("Abrir acessibilidade")
                        }
                    }
                }
            }
            item {
                SettingsCard("Conta e slots",Icons.Default.AccountCircle) {
                    DetailLine("Usuário OSM","leandrozzy")
                    DetailLine("Slots","4")
                    DetailLine("Armazenamento","Local no aparelho")
                }
            }
            item {
                SettingsCard("IA e processamento",Icons.Default.AutoAwesome) {
                    DetailLine("Backend","OSM AI Coach Cloud")
                    DetailLine("Última sessão",latest?.let{"${it.frames.size} telas"}?:"Nenhuma")
                    DetailLine("Resultado","${processing.success} aplicados · ${processing.failed} falhas")
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick=onReprocess,enabled=latest!=null&&!processing.running,modifier=Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Refresh,null);Spacer(Modifier.width(7.dp));Text("Reprocessar última sessão")
                    }
                }
            }
            item {
                SettingsCard("Diagnóstico",Icons.Default.BugReport) {
                    DetailLine("Sessão preservada",if(latest!=null)"Sim" else "Não")
                    DetailLine("Estado",latest?.state?:"NI")
                    DetailLine("Último erro",processing.lastError.ifBlank{"Nenhum"})
                }
            }
            item {
                SettingsCard("Notificações e automação",Icons.Default.Notifications) {
                    Text("Alertas de jogo, pendências, resultado e mercado ficarão concentrados aqui.",fontSize=13.sp)
                }
            }
        }
    }

    @Composable
    private fun SettingsCard(
        title:String,
        icon:androidx.compose.ui.graphics.vector.ImageVector,
        content:@Composable ColumnScope.()->Unit
    ) {
        Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Icon(icon,null,tint=Color(0xFF6E942D))
                    Spacer(Modifier.width(9.dp))
                    Text(title,fontWeight=FontWeight.Bold,fontSize=17.sp)
                }
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }

    @Composable
    private fun SettingStatus(label:String,value:String,good:Boolean) {
        Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(label,Modifier.weight(1f),fontSize=13.sp,color=Color.Gray)
            Surface(shape=RoundedCornerShape(999.dp),color=if(good)Color(0xFFEAF4DD) else Color(0xFFFFE9D8)) {
                Text(value,Modifier.padding(horizontal=9.dp,vertical=5.dp),fontSize=11.sp,fontWeight=FontWeight.Bold)
            }
        }
    }
"""
m = m[:start] + settings + m[end:]

main.write_text(m, encoding="utf-8")
print("Native Pro v5 applied.")
