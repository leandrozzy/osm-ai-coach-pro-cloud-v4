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

    private val prefs=context.getSharedPreferences("native_processor_v7",Context.MODE_PRIVATE)
    private val local=LocalOcrExtractor()
    @Volatile private var cloudError:String=""

    fun isProcessed(sessionId:String)=prefs.getString("processed_session",null)==sessionId

    suspend fun processLatest(force:Boolean=false,onProgress:(Progress)->Unit):Boolean=withContext(Dispatchers.IO){
        val session=repository.latestSession()?:return@withContext false
        if(session.state!="ready"||session.frames.isEmpty())return@withContext false
        if(!force&&isProcessed(session.id))return@withContext false

        val segments=splitIntoSlots(session)
        if(segments.isEmpty())return@withContext false

        val slots=store.loadAll()
        val total=session.frames.size + segments.size*3
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

        segments.forEachIndexed{slotIndex,indices->
            val slot=slots[slotIndex.coerceIn(0,3)]
            val texts=indices.mapNotNull{ocrByIndex[it]}.filter{it.isNotBlank()}
            local.applyToSlot(slot,texts)
            slot.lastUpdated=System.currentTimeMillis()
        }
        store.saveAll(slots)

        val endpoint=probeBackend()
        if(endpoint==null){
            lastError="IA Cloud indisponível. OCR local aplicado; os dados reconhecidos foram mantidos."
            failed += segments.size*3
            current=total
            prefs.edit().putString("processed_session",session.id).apply()
            onProgress(Progress(false,current,total,"Sessão concluída com OCR local",success,failed,"done",lastError))
            return@withContext true
        }

        val jobs=mutableListOf<Triple<Int,String,List<Int>>>()
        segments.forEachIndexed{i,rows->
            jobs+=Triple(i,"match",rows)
            jobs+=Triple(i,"squad",rows)
            jobs+=Triple(i,"calendar",rows)
        }

        jobs.forEach{job->
            val slot=slots[job.first.coerceIn(0,3)]
            val friendly=when(job.second){"match"->"Pré-jogo";"squad"->"Elenco";else->"Calendário"}
            onProgress(Progress(true,current,total,"S${slot.id} · $friendly · IA",success,failed,"cloud",lastError))

            val result=withTimeoutOrNull(60000L){analyzeType(endpoint,session,job.third,job.second,2,slot)}
            if(result==null){
                failed++
                val detail=cloudError.ifBlank{"sem resposta dentro do limite"}
                lastError="S${slot.id} $friendly: $detail"
            }else{
                runCatching{applyResult(slot,result.first,result.second)}
                    .onSuccess{success++}
                    .onFailure{failed++;lastError="S${slot.id} $friendly: ${it.message?:"falha ao aplicar"}"}
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
        val candidates=listOf(BuildConfig.BACKEND_URL,BuildConfig.BACKEND_FALLBACK_URL).distinct()
        for(base in candidates){
            try{
                val conn=(URL(base.trimEnd('/')+"/api/analyze").openConnection() as HttpURLConnection).apply{
                    requestMethod="GET";connectTimeout=5000;readTimeout=5000;instanceFollowRedirects=true
                    setRequestProperty("User-Agent","OSM-AI-Coach-Native/7")
                }
                val code=conn.responseCode
                conn.disconnect()
                if(code in 200..499)return base
            }catch(_:Throwable){}
        }
        return null
    }

    private fun splitIntoSlots(session:CaptureSession):List<List<Int>>{
        val frames=session.frames.sortedBy{it.capturedAt}
        if(frames.isEmpty())return emptyList()
        val gaps=mutableListOf<Pair<Int,Long>>()
        for(i in 1 until frames.size)gaps+=i to (frames[i].capturedAt-frames[i-1].capturedAt).coerceAtLeast(0)
        val cuts=gaps.filter{it.second>=2200L}.sortedByDescending{it.second}.take(3).map{it.first}.sorted()
        val result=mutableListOf<List<Int>>()
        if(cuts.isNotEmpty()){
            var from=0
            for(cut in cuts+frames.size){
                if(cut>from)result+=frames.subList(from,cut).map{it.index}
                from=cut
            }
        }
        if(result.size<2&&frames.size>=16){
            result.clear()
            var from=0
            for(i in 0 until 4){
                val to=if(i==3)frames.size else ((i+1)*frames.size/4)
                result+=frames.subList(from,to).map{it.index}
                from=to
            }
        }
        if(result.isEmpty())result+=frames.map{it.index}
        return result.take(4)
    }

    private suspend fun analyzeType(
        endpointBase:String,session:CaptureSession,indices:List<Int>,type:String,maxImages:Int,slot:NativeSlotData
    ):Pair<String,JSONObject>?=withContext(Dispatchers.IO){
        val selected=sample(indices,maxImages)
        if(selected.isEmpty())return@withContext null
        val images=JSONArray()
        selected.forEach{index->
            val file=repository.frameFile(session,index)?:return@forEach
            val encoded=encodeImage(file.absolutePath)?:return@forEach
            images.put(JSONObject().apply{
                put("url",encoded.first);put("width",encoded.second.first);put("height",encoded.second.second);put("frameIndex",index);put("region","full")
            })
        }
        if(images.length()==0)return@withContext null

        val body=JSONObject().apply{
            put("type",type);put("images",images);put("ocrImages",images);put("forceOCR",true);put("useVisual",true)
            put("context",JSONObject().apply{
                put("username","leandrozzy")
                if(slot.team!="NI")put("myTeam",slot.team)
                put("competitionType",slot.competitionType)
                if(slot.nextRival!="NI")put("rivalName",slot.nextRival)
            })
        }

        val conn=(URL(endpointBase.trimEnd('/')+"/api/analyze").openConnection() as HttpURLConnection).apply{
            requestMethod="POST";connectTimeout=10000;readTimeout=52000;doOutput=true;instanceFollowRedirects=true
            setRequestProperty("Content-Type","application/json");setRequestProperty("Accept","application/json")
            setRequestProperty("User-Agent","OSM-AI-Coach-Native/7")
        }
        return@withContext try{
            conn.outputStream.use{it.write(body.toString().toByteArray(Charsets.UTF_8));it.flush()}
            val code=conn.responseCode
            val raw=(if(code in 200..299)conn.inputStream else conn.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
            if(code !in 200..299){
                cloudError="HTTP $code: "+raw.take(180).replace("\n"," ")
                null
            }else if(raw.isBlank()){
                cloudError="HTTP $code sem conteúdo"
                null
            }else{
                val root=JSONObject(raw)
                val data=root.optJSONObject("data")
                if(data==null){
                    cloudError="Resposta sem campo data: "+raw.take(180).replace("\n"," ")
                    null
                }else{
                    cloudError=""
                    type to data
                }
            }
        }catch(e:Throwable){
            cloudError=(e.javaClass.simpleName+": "+(e.message?:"erro de rede")).take(220)
            null
        }finally{conn.disconnect()}
    }

    private fun applyResult(slot:NativeSlotData,type:String,data:JSONObject){
        when(type){
            "match"->applyMatch(slot,data.optJSONObject("match")?:JSONObject())
            "squad"->applySquad(slot,data)
            "calendar"->applyCalendar(slot,data)
        }
    }

    private fun applyMatch(slot:NativeSlotData,m:JSONObject){
        putIfKnown(m,"myName"){slot.team=it};putIfKnown(m,"rivalName"){slot.nextRival=it}
        putIfKnown(m,"location"){slot.venue=it};putIfKnown(m,"referee"){slot.referee=it}
        putIfKnown(m,"myStrength"){slot.myStrength=it};putIfKnown(m,"rivalStrength"){slot.rivalStrength=it}
        putIfKnown(m,"mySquadValue"){slot.myValue=it};putIfKnown(m,"rivalSquadValue"){slot.rivalValue=it}
        putIfKnown(m,"myGK"){slot.myGoalkeeper=it};putIfKnown(m,"myDEF"){slot.myDefense=it}
        putIfKnown(m,"myMID"){slot.myMidfield=it};putIfKnown(m,"myATT"){slot.myAttack=it}
        putIfKnown(m,"rivalGK"){slot.rivalGoalkeeper=it};putIfKnown(m,"rivalDEF"){slot.rivalDefense=it}
        putIfKnown(m,"rivalMID"){slot.rivalMidfield=it};putIfKnown(m,"rivalATT"){slot.rivalAttack=it}
        putIfKnown(m,"rivalFormation"){slot.rivalFormation=it};putIfKnown(m,"rivalPlan"){slot.rivalPlan=it}
        putIfKnown(m,"rivalMarking"){slot.marking=it};putIfKnown(m,"rivalOffside"){slot.offside=it}
        putIfKnown(m,"secretTraining"){slot.secretTraining=it};putIfKnown(m,"trainingCamp"){slot.trainingCamp=it}
        putIfKnown(m,"stadium"){slot.stadium=it};putIfKnown(m,"myBonus"){slot.bonus=it}
    }

    private fun applySquad(slot:NativeSlotData,data:JSONObject){
        val meta=data.optJSONObject("meta")?:JSONObject()
        putIfKnown(meta,"team"){slot.team=it};putIfKnown(meta,"competition"){slot.competition=it}
        putIfKnown(meta,"competitionType"){slot.competitionType=it};putIfKnown(meta,"squadValue"){slot.myValue=it}
        putIfKnown(meta,"strength"){slot.myStrength=it};putIfKnown(meta,"GK"){slot.myGoalkeeper=it}
        putIfKnown(meta,"DEF"){slot.myDefense=it};putIfKnown(meta,"MID"){slot.myMidfield=it};putIfKnown(meta,"ATT"){slot.myAttack=it}
        val players=data.optJSONArray("players")?:JSONArray()
        if(players.length()>0)slot.squadCount=players.length()
    }

    private fun applyCalendar(slot:NativeSlotData,data:JSONObject){
        val meta=data.optJSONObject("meta")?:JSONObject()
        putIfKnown(meta,"team"){slot.team=it};putIfKnown(meta,"competition"){slot.competition=it};putIfKnown(meta,"competitionType"){slot.competitionType=it}
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
            putIfKnown(row,"opponent"){slot.nextRival=it};putIfKnown(row,"date"){slot.matchDate=it};putIfKnown(row,"time"){slot.matchTime=it}
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
        val width=scaled.width;val height=scaled.height
        var quality=60;var bytes:ByteArray
        do{
            val out=ByteArrayOutputStream();scaled.compress(Bitmap.CompressFormat.JPEG,quality,out);bytes=out.toByteArray();quality-=7
        }while(bytes.size>450_000&&quality>=32)
        if(scaled!==original)scaled.recycle();original.recycle()
        return "data:image/jpeg;base64,"+Base64.encodeToString(bytes,Base64.NO_WRAP) to (width to height)
    }
}
