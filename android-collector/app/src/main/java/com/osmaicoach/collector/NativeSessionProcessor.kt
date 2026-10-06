package com.osmaicoach.collector

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

class NativeSessionProcessor(
    private val context: Context,
    private val repository: SessionRepository,
    private val store: NativeSlotStore
) {
    data class Progress(
        val running:Boolean=false,
        val current:Int=0,
        val total:Int=0,
        val label:String="",
        val success:Int=0,
        val failed:Int=0,
        val stage:String="idle",
        val lastError:String=""
    )

    private val prefs=context.getSharedPreferences("native_processor_v13",Context.MODE_PRIVATE)
    private val local=LocalOcrExtractor()
    @Volatile private var cloudError:String=""

    fun isProcessed(sessionId:String)=prefs.getString("processed_session",null)==sessionId

    suspend fun processLatest(force:Boolean=false,onProgress:(Progress)->Unit):Boolean=withContext(Dispatchers.IO){
        val session=repository.latestSession()?:return@withContext false
        if(session.state!="ready"||session.frames.isEmpty())return@withContext false
        if(!force&&isProcessed(session.id))return@withContext false

        val slots=store.loadAll()
        var total=session.frames.size + 12
        var current=0
        var success=0
        var failed=0
        var lastError=""

        onProgress(Progress(true,0,total,"OCR local: preparando ${session.frames.size} telas",0,0,"ocr",""))

        val ocrByIndex=linkedMapOf<Int,String>()
        session.frames.sortedBy{it.index}.forEach{frame->
            val file=repository.frameFile(session,frame.index)
            val visualText=if(file!=null) withTimeoutOrNull(12000L){local.read(file)} ?: "" else ""
            val text=listOf(frame.textHint,visualText).filter{it.isNotBlank()}.distinct().joinToString("\n")
            ocrByIndex[frame.index]=text
            current++
            val readable=ocrByIndex.values.count{it.isNotBlank()}
            prefs.edit().putInt("local_ocr_readable",readable).putInt("local_ocr_total",session.frames.size).apply()
            onProgress(Progress(true,current,total,"OCR local · tela ${frame.index+1}/${session.frames.size} · $readable com texto",success,failed,"ocr",lastError))
        }

        val classifications = ocrByIndex.mapValues { (_, text) -> ScreenClassifier.classify(text) }

        val tracked = SlotNavigationTracker.assign(session, ocrByIndex, slots)
        val segments = tracked.slotRows
        val frameToSlot = tracked.frameToSlot.toMutableMap()
        total = session.frames.size + segments.count { it.isNotEmpty() } * 3

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
        )

        val localKnownBefore = IntArray(4)
        val localKnownAfter = IntArray(4)
        segments.forEachIndexed{slotIndex,indices->
            val slot=slots[slotIndex.coerceIn(0,3)]
            localKnownBefore[slotIndex]=knownCount(slot)
            val texts=indices.mapNotNull{ocrByIndex[it]}.filter{it.isNotBlank()}
            local.applyToSlot(slot,texts)
            localKnownAfter[slotIndex]=knownCount(slot)
            slot.lastUpdated=System.currentTimeMillis()
        }
        store.saveAll(slots)
        val localApplied=localKnownAfter.indices.count { localKnownAfter[it] > localKnownBefore[it] }
        success += localApplied
        prefs.edit().putInt("local_slots_applied",localApplied).apply()

        val endpoint=probeBackend()
        if(endpoint==null){
            lastError="IA Cloud indisponível. OCR local foi mantido."
            failed += segments.count { it.isNotEmpty() } * 3
            current=total
            prefs.edit().putString("processed_session",session.id).apply()
            onProgress(Progress(false,current,total,"Sessão concluída com OCR local",success,failed,"done",lastError))
            return@withContext true
        }

        fun rowsForType(rows:List<Int>, type:String):List<Int>{
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
        }

        jobs.forEach{job->
            val slot=slots[job.first.coerceIn(0,3)]
            val friendly=when(job.second){"match"->"Pré-jogo";"squad"->"Elenco";else->"Calendário"}
            onProgress(Progress(true,current,total,"S${slot.id} · $friendly · IA",success,failed,"cloud",lastError))

            val maxImages=4
            val nativeOcrText=job.third.mapNotNull{ocrByIndex[it]}.filter{it.isNotBlank()}.joinToString("\n\n").take(30000)
            val result=withTimeoutOrNull(65000L){analyzeType(endpoint,session,job.third,job.second,maxImages,slot,nativeOcrText)}
            if(result==null){
                failed++
                val detail=cloudError.ifBlank{"sem resposta dentro do limite"}
                lastError="S${slot.id} $friendly: $detail"
                repository.markFrames(session.id,job.third,slot.id,"IA falhou",0)
            }else{
                val before=knownCount(slot)
                val changed=runCatching{applyResult(slot,result.first,result.second)}.getOrElse { 0 }
                val after=knownCount(slot)
                if(after>before || changed>0){
                    success++
                    repository.markFrames(session.id,job.third,slot.id,"Interpretada ✓",maxOf(changed,after-before))
                }else{
                    failed++
                    lastError="S${slot.id} $friendly: resposta recebida, mas sem dados úteis para este slot"
                    repository.markFrames(session.id,job.third,slot.id,"Sem dado útil",0)
                }
            }
            current++
            slot.lastUpdated=System.currentTimeMillis()
            store.saveAll(slots)
            onProgress(Progress(true,current,total,"S${slot.id} · $friendly · concluído",success,failed,"cloud",lastError))
        }

        prefs.edit().putString("processed_session",session.id).apply()
        val readable=prefs.getInt("local_ocr_readable",0)
        onProgress(Progress(false,total,total,"Sessão finalizada · OCR $readable/${session.frames.size}",success,failed,"done",lastError))
        true
    }

    private fun probeBackend():String?{
        val candidates=listOf(BuildConfig.BACKEND_URL,BuildConfig.BACKEND_FALLBACK_URL)
            .filter{it.isNotBlank()}.distinct()
        for(base in candidates){
            try{
                val conn=(URL(base.trimEnd('/')+"/api/status").openConnection() as HttpURLConnection).apply{
                    requestMethod="GET";connectTimeout=6000;readTimeout=6000;instanceFollowRedirects=false
                    setRequestProperty("Accept","application/json")
                    setRequestProperty("User-Agent","OSM-AI-Coach-Native/18")
                }
                val code=conn.responseCode
                val contentType=conn.contentType.orEmpty().lowercase()
                val raw=runCatching{(if(code in 200..299)conn.inputStream else conn.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()}.getOrDefault("")
                conn.disconnect()
                if(code in 200..299 && (contentType.contains("json") || raw.trim().startsWith("{"))) return base
            }catch(_:Throwable){}
        }
        return null
    }

    private suspend fun analyzeType(
        endpointBase:String,session:CaptureSession,indices:List<Int>,type:String,maxImages:Int,slot:NativeSlotData,nativeOcrText:String
    ):Pair<String,JSONObject>?=withContext(Dispatchers.IO){
        val selected=sample(indices,maxImages)
        if(selected.isEmpty())return@withContext null
        val images=JSONArray()
        selected.forEach{index->
            val file=repository.frameFile(session,index)?:return@forEach
            val encoded=encodeImage(file.absolutePath)?:return@forEach
            images.put(JSONObject().apply{
                put("url",encoded.first)
                put("width",encoded.second.first)
                put("height",encoded.second.second)
                put("frameIndex",index)
                put("region","full")
            })
        }
        if(images.length()==0)return@withContext null

        val body=JSONObject().apply{
            put("type",type)
            put("images",images)
            put("ocrImages",images)
            put("forceOCR",true)
            put("useVisual",true)
            put("useText",true)
            put("nativeOcrText",nativeOcrText)
            put("context",JSONObject().apply{
                put("username","leandrozzy")
                if(slot.team!="NI")put("myTeam",slot.team)
                put("competitionType",slot.competitionType)
                if(slot.nextRival!="NI")put("rivalName",slot.nextRival)
            })
        }

        val conn=(URL(endpointBase.trimEnd('/')+"/api/analyze").openConnection() as HttpURLConnection).apply{
            requestMethod="POST";connectTimeout=12000;readTimeout=56000;doOutput=true;instanceFollowRedirects=true
            setRequestProperty("Content-Type","application/json")
            setRequestProperty("Accept","application/json")
            setRequestProperty("User-Agent","OSM-AI-Coach-Native/18")
        }

        return@withContext try{
            conn.outputStream.use{it.write(body.toString().toByteArray(Charsets.UTF_8));it.flush()}
            val code=conn.responseCode
            val raw=(if(code in 200..299)conn.inputStream else conn.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
            if(code !in 200..299){
                cloudError="HTTP $code: "+raw.take(220).replace("\n"," ")
                null
            }else if(raw.isBlank()){
                cloudError="HTTP $code sem conteúdo"
                null
            }else{
                val root=JSONObject(raw)
                val data =
                    root.optJSONObject("data")
                        ?: root.optJSONObject("result")
                        ?: if(
                            root.has("match") || root.has("preGame") ||
                            root.has("players") || root.has("squad") ||
                            root.has("calendar") || root.has("meta")
                        ) root else null
                if(data==null){
                    cloudError="Resposta sem dados reconhecíveis: "+raw.take(220).replace("\n"," ")
                    null
                }else{
                    cloudError=""
                    type to data
                }
            }
        }catch(e:Throwable){
            cloudError=(e.javaClass.simpleName+": "+(e.message?:"erro de rede")).take(240)
            null
        }finally{conn.disconnect()}
    }

    private fun applyResult(slot:NativeSlotData,type:String,data:JSONObject):Int =
        when(type){
            "match" -> applyMatch(slot, data.optJSONObject("match") ?: data.optJSONObject("preGame") ?: data.optJSONObject("pregame") ?: data)
            "squad" -> applySquad(slot, data.optJSONObject("squad") ?: data)
            "calendar" -> applyCalendar(slot, data.optJSONObject("calendarData") ?: data)
            else -> 0
        }

    private fun applyMatch(slot:NativeSlotData,m:JSONObject):Int{
        var changed=0
        fun putAny(keys:List<String>,set:(String)->Unit){
            val v=keys.firstNotNullOfOrNull { key -> knownString(m,key) } ?: return
            set(v); changed++
        }
        putAny(listOf("myName","myTeam","team","club")){slot.team=it}
        putAny(listOf("rivalName","opponent","rival","opponentName")){slot.nextRival=it}
        putAny(listOf("date","matchDate")){slot.matchDate=it}
        putAny(listOf("time","matchTime","kickoff")){slot.matchTime=it}
        putAny(listOf("location","venue","homeAway")){slot.venue=it}
        putAny(listOf("referee","refereeColor","refereeStrictness")){ if(validReferee(it)) slot.referee=it else changed-- }
        putAny(listOf("myStrength","teamStrength","strength")){slot.myStrength=it}
        putAny(listOf("rivalStrength","opponentStrength")){slot.rivalStrength=it}
        putAny(listOf("mySquadValue","squadValue","teamValue")){slot.myValue=it}
        putAny(listOf("rivalSquadValue","opponentValue","rivalValue")){slot.rivalValue=it}
        putAny(listOf("myGK","myGoalkeeper","goalkeeper")){slot.myGoalkeeper=it}
        putAny(listOf("myDEF","myDefense","defense")){slot.myDefense=it}
        putAny(listOf("myMID","myMidfield","midfield")){slot.myMidfield=it}
        putAny(listOf("myATT","myAttack","attack")){slot.myAttack=it}
        putAny(listOf("rivalGK","opponentGK","rivalGoalkeeper")){slot.rivalGoalkeeper=it}
        putAny(listOf("rivalDEF","opponentDEF","rivalDefense")){slot.rivalDefense=it}
        putAny(listOf("rivalMID","opponentMID","rivalMidfield")){slot.rivalMidfield=it}
        putAny(listOf("rivalATT","opponentATT","rivalAttack")){slot.rivalAttack=it}
        putAny(listOf("rivalFormation","opponentFormation","formation")){slot.rivalFormation=it}
        putAny(listOf("rivalPlan","opponentPlan","gamePlan","plan")){slot.rivalPlan=it}
        putAny(listOf("rivalMarking","marking")){slot.marking=it}
        putAny(listOf("rivalOffside","offside")){slot.offside=it}
        putAny(listOf("secretTraining","secretTrainingOpponent")){slot.secretTraining=it}
        putAny(listOf("trainingCamp","camp","opponentTrainingCamp")){slot.trainingCamp=it}
        putAny(listOf("stadium","stadiumLevel")){slot.stadium=it}
        putAny(listOf("myBonus","bonus","homeBonus")){slot.bonus=it}
        return changed
    }

    private fun applySquad(slot:NativeSlotData,data:JSONObject):Int{
        var changed=0
        val meta=data.optJSONObject("meta")?:JSONObject()

        fun putMeta(key:String,set:(String)->Unit){
            val v=knownString(meta,key) ?: return
            set(v); changed++
        }
        putMeta("team"){slot.team=it};putMeta("competition"){slot.competition=it}
        putMeta("competitionType"){slot.competitionType=it};putMeta("squadValue"){slot.myValue=it}
        putMeta("strength"){slot.myStrength=it};putMeta("GK"){slot.myGoalkeeper=it}
        putMeta("DEF"){slot.myDefense=it};putMeta("MID"){slot.myMidfield=it};putMeta("ATT"){slot.myAttack=it}

        val arr=data.optJSONArray("players")?:JSONArray()
        if(arr.length()>0){
            val parsed=mutableListOf<NativePlayerData>()
            for(i in 0 until arr.length()){
                val p=arr.optJSONObject(i)?:continue
                val name=firstKnown(p,"name","player","playerName") ?: continue
                parsed += NativePlayerData(
                    name=name,
                    position=firstKnown(p,"position","pos","role") ?: "NI",
                    age=firstKnown(p,"age") ?: "NI",
                    strength=firstKnown(p,"strength","rating","overall") ?: "NI",
                    value=firstKnown(p,"value","marketValue") ?: "NI",
                    training=firstKnown(p,"training","inTraining") ?: "NI",
                    selling=firstKnown(p,"selling","forSale","listed") ?: "NI"
                )
            }
            if(parsed.isNotEmpty()){
                slot.players = parsed.distinctBy{it.name.lowercase()+"|"+it.position.lowercase()}.toMutableList()
                slot.squadCount=slot.players.size
                slot.attackers=slot.players.count{normPos(it.position)=="ATA"}
                slot.midfielders=slot.players.count{normPos(it.position)=="MEI"}
                slot.defenders=slot.players.count{normPos(it.position)=="DEF"}
                slot.goalkeepers=slot.players.count{normPos(it.position)=="GOL"}
                slot.trainingCount=slot.players.count{truthy(it.training)}
                slot.sellingCount=slot.players.count{truthy(it.selling)}
                changed += slot.players.size
            }
        }
        return changed
    }

    private fun applyCalendar(slot:NativeSlotData,data:JSONObject):Int{
        var changed=0
        val meta=data.optJSONObject("meta")?:JSONObject()
        fun putMeta(key:String,set:(String)->Unit){
            val v=knownString(meta,key) ?: return
            set(v); changed++
        }
        putMeta("team"){slot.team=it};putMeta("competition"){slot.competition=it};putMeta("competitionType"){slot.competitionType=it}

        val rows=data.optJSONArray("calendar")?:JSONArray()
        if(rows.length()==0)return changed

        val parsed=mutableListOf<NativeCalendarGame>()
        for(i in 0 until rows.length()){
            val row=rows.optJSONObject(i)?:continue
            val opponent=firstKnown(row,"opponent","rival","team") ?: "NI"
            val home = if(row.has("home")&&!row.isNull("home")) row.optBoolean("home") else null
            parsed += NativeCalendarGame(
                round=firstKnown(row,"round","matchday") ?: "NI",
                opponent=opponent,
                date=firstKnown(row,"date") ?: "NI",
                time=firstKnown(row,"time") ?: "NI",
                venue=when(home){true->"Casa";false->"Fora";null->firstKnown(row,"venue","location") ?: "NI"},
                score=firstKnown(row,"score","displayedScore","result") ?: "NI",
                competition=firstKnown(row,"competition","type") ?: slot.competition,
                cup=row.optBoolean("cup",false)
            )
        }

        if(parsed.isNotEmpty()){
            slot.calendar=parsed.distinctBy{
                listOf(it.round,it.opponent,it.date,it.time).joinToString("|").lowercase()
            }.toMutableList()
            slot.calendarCount=slot.calendar.size
            changed += slot.calendar.size

            val future=slot.calendar.firstOrNull {
                it.score=="NI" || it.score.isBlank() || it.score=="-" || !Regex("\\d+\\s*[xX:-]\\s*\\d+").containsMatchIn(it.score)
            } ?: slot.calendar.lastOrNull()

            future?.let{
                if(it.opponent!="NI")slot.nextRival=it.opponent
                if(it.date!="NI")slot.matchDate=it.date
                if(it.time!="NI")slot.matchTime=it.time
                if(it.venue!="NI")slot.venue=it.venue
            }
        }
        return changed
    }

    private fun knownString(o:JSONObject,key:String):String?{
        if(!o.has(key)||o.isNull(key))return null
        val v=o.opt(key)?.toString()?.trim().orEmpty()
        return v.takeIf{it.isNotBlank()&&it!="NI"&&it!="null"}
    }

    private fun firstKnown(o:JSONObject,vararg keys:String):String?{
        for(k in keys) knownString(o,k)?.let{return it}
        return null
    }

    private fun validReferee(v:String):Boolean{
        val n=v.lowercase()
        return listOf("verde","azul","amarelo","laranja","vermelho","rigoroso","médio","medio","tolerante","green","blue","yellow","orange","red").any{n.contains(it)}
    }

    private fun normPos(v:String):String{
        val n=v.uppercase()
        return when{
            n.contains("ATA")||n.contains("ATT")||n.contains("FW") -> "ATA"
            n.contains("MEI")||n.contains("MID")||n.contains("MF") -> "MEI"
            n.contains("DEF")||n.contains("CB")||n.contains("LB")||n.contains("RB") -> "DEF"
            n.contains("GOL")||n.contains("GK") -> "GOL"
            else -> "NI"
        }
    }

    private fun truthy(v:String):Boolean{
        val n=v.lowercase()
        return n=="sim"||n=="yes"||n=="true"||n=="1"||n.contains("trein")||n.contains("venda")
    }

    private fun knownCount(slot:NativeSlotData):Int {
        val values=listOf(
            slot.team,slot.competition,slot.nextRival,slot.matchDate,slot.matchTime,slot.venue,slot.referee,
            slot.myStrength,slot.rivalStrength,slot.myValue,slot.rivalValue,
            slot.myGoalkeeper,slot.myDefense,slot.myMidfield,slot.myAttack,
            slot.rivalGoalkeeper,slot.rivalDefense,slot.rivalMidfield,slot.rivalAttack,
            slot.rivalFormation,slot.rivalPlan,slot.marking,slot.offside,
            slot.secretTraining,slot.trainingCamp,slot.stadium,slot.bonus
        )
        return values.count { it.isNotBlank() && it!="NI" && it!="null" } +
            slot.players.size + slot.calendar.size
    }

    private fun sample(rows:List<Int>,max:Int):List<Int>{
        if(rows.size<=max)return rows
        // rowsForType já ordena as telas mais relevantes primeiro. Espalhar a
        // amostra pelo bloco fazia o app enviar telas "Outra" e perder justamente
        // calendário/elenco/pré-jogo.
        return rows.take(max)
    }

    private fun encodeImage(path:String):Pair<String,Pair<Int,Int>>?{
        val original=BitmapFactory.decodeFile(path)?:return null
        val targetWidth=minOf(900,original.width)
        val targetHeight=(original.height*(targetWidth.toFloat()/original.width)).toInt().coerceAtLeast(1)
        val scaled=if(original.width!=targetWidth)Bitmap.createScaledBitmap(original,targetWidth,targetHeight,true) else original
        val width=scaled.width;val height=scaled.height
        var quality=60;var bytes:ByteArray
        do{
            val out=ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG,quality,out)
            bytes=out.toByteArray()
            quality-=7
        }while(bytes.size>450_000&&quality>=32)
        if(scaled!==original)scaled.recycle()
        original.recycle()
        return "data:image/jpeg;base64,"+Base64.encodeToString(bytes,Base64.NO_WRAP) to (width to height)
    }
}
