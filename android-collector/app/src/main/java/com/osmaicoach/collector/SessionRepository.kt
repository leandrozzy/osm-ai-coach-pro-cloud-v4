package com.osmaicoach.collector

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class SessionRepository(private val context: Context) {
    private val root = File(context.filesDir, "osm_sessions").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("collector", Context.MODE_PRIVATE)
    private var active: CaptureSession? = null

    @Synchronized
    fun startIfNeeded(): CaptureSession {
        active?.let { return it }

        val persistedId = prefs.getString("active_session_id", null)
        if (!persistedId.isNullOrBlank()) {
            val restored = parseSession(sessionJson(persistedId))
            if (restored != null && restored.state == "recording") {
                active = restored
                return restored
            }
        }

        return createSession()
    }

    @Synchronized
    fun beginNewSession(): CaptureSession {
        finish()
        return createSession()
    }

    @Synchronized
    fun current(): CaptureSession? {
        active?.let { return it }
        val persistedId = prefs.getString("active_session_id", null) ?: return null
        return parseSession(sessionJson(persistedId))?.takeIf { it.state == "recording" }?.also { active = it }
    }

    private fun createSession(): CaptureSession {
        val now = System.currentTimeMillis()
        val id = "osm-${now}-${UUID.randomUUID().toString().take(8)}"
        return CaptureSession(id=id, startedAt=now).also { session ->
            active = session
            sessionDir(session).mkdirs()
            persist(session)
            prefs.edit()
                .putString("active_session_id", session.id)
                .putString("latest_session_id", session.id)
                .putLong("latest_session_started_at", now)
                .apply()
        }
    }

    @Synchronized
    fun saveFrame(bitmap: Bitmap, fingerprint: Long, textHint: String = ""): CaptureFrame {
        val session=startIfNeeded()
        val index=session.frames.size
        val name="frame-${index.toString().padStart(4,'0')}.jpg"
        val file=File(sessionDir(session),name)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }

        val classified=ScreenClassifier.classify(textHint)
        val frame=CaptureFrame(
            index=index,
            fileName=name,
            capturedAt=System.currentTimeMillis(),
            width=bitmap.width,
            height=bitmap.height,
            fingerprint=fingerprint,
            textHint=textHint.take(7000),
            screenType=classified.type,
            screenTitle=classified.title.take(80)
        )
        session.frames += frame
        persist(session)
        return frame
    }

    @Synchronized
    fun finish(): CaptureSession? {
        val session = current() ?: return null
        if (session.state == "ready") return session
        session.endedAt = System.currentTimeMillis()
        session.state = "ready"
        persist(session)
        active = null
        prefs.edit()
            .putString("latest_session_id", session.id)
            .remove("active_session_id")
            .putLong("latest_session_ended_at", session.endedAt ?: System.currentTimeMillis())
            .apply()
        return session
    }

    fun latestSessionJson(): String {
        val id=prefs.getString("latest_session_id",null) ?: return "null"
        return sessionJson(id)
    }

    fun latestSession(): CaptureSession? = parseSession(latestSessionJson())

    fun listSessions(limit: Int = 20): List<CaptureSession> =
        root.listFiles()
            ?.filter { it.isDirectory && File(it,"session.json").exists() }
            ?.mapNotNull { parseSession(File(it,"session.json").readText()) }
            ?.sortedByDescending { it.startedAt }
            ?.take(limit)
            ?: emptyList()

    fun frameFile(session: CaptureSession, index: Int): File? {
        val frame = session.frames.firstOrNull { it.index == index } ?: return null
        val file = File(File(root, session.id), frame.fileName)
        return file.takeIf { it.exists() }
    }

    @Synchronized
    fun updateLatestFrameAnalysis(updates: Map<Int, FrameAnalysisUpdate>) {
        val id=prefs.getString("latest_session_id",null) ?: return
        val file=File(File(root,id),"session.json")
        if(!file.exists()) return
        runCatching {
            val json=JSONObject(file.readText())
            val frames=json.optJSONArray("frames") ?: return@runCatching
            for(i in 0 until frames.length()){
                val frame=frames.getJSONObject(i)
                val index=frame.optInt("index",i)
                val u=updates[index] ?: continue
                frame.put("slotId",u.slotId)
                frame.put("screenType",u.screenType)
                frame.put("screenTitle",u.screenTitle.take(80))
                frame.put("ocrText",u.ocrText.take(12000))
                frame.put("analysisState",u.analysisState)
                frame.put("extractedFields",u.extractedFields)
            }
            file.writeText(json.toString(2))
        }
    }

    @Synchronized
    fun markLatestFrames(indices: List<Int>, slotId:Int, state:String, extractedFields:Int) {
        val id=prefs.getString("latest_session_id",null) ?: return
        val file=File(File(root,id),"session.json")
        if(!file.exists()) return
        runCatching {
            val json=JSONObject(file.readText())
            val frames=json.optJSONArray("frames") ?: return@runCatching
            val wanted=indices.toSet()
            for(i in 0 until frames.length()){
                val frame=frames.getJSONObject(i)
                if(frame.optInt("index",i) !in wanted) continue
                frame.put("slotId",slotId)
                frame.put("analysisState",state)
                frame.put("extractedFields",maxOf(frame.optInt("extractedFields",0),extractedFields))
            }
            file.writeText(json.toString(2))
        }
    }

    fun latestFrameBase64(index:Int):String?{
        val id=prefs.getString("latest_session_id",null) ?: return null
        val json=JSONObject(sessionJson(id))
        val frames=json.optJSONArray("frames") ?: return null
        if(index !in 0 until frames.length()) return null
        val name=frames.getJSONObject(index).getString("fileName")
        val bytes=File(File(root,id),name).readBytes()
        return android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)
    }

    private fun sessionDir(session:CaptureSession)=File(root,session.id)

    private fun sessionJson(id:String):String{
        val f=File(File(root,id),"session.json")
        return if(f.exists()) f.readText() else "null"
    }

    private fun persist(session:CaptureSession){
        val frames=JSONArray()
        session.frames.forEach { f ->
            frames.put(JSONObject().apply{
                put("index",f.index);put("fileName",f.fileName);put("capturedAt",f.capturedAt)
                put("sourcePackage",f.sourcePackage);put("width",f.width);put("height",f.height)
                put("fingerprint",f.fingerprint.toString());put("textHint",f.textHint)
                put("screenType",f.screenType);put("screenTitle",f.screenTitle)
                put("slotId",f.slotId);put("ocrText",f.ocrText)
                put("analysisState",f.analysisState);put("extractedFields",f.extractedFields)
            })
        }
        val json=JSONObject().apply{
            put("version",4);put("kind","osm-native-session");put("id",session.id)
            put("startedAt",session.startedAt);put("endedAt",session.endedAt?:JSONObject.NULL)
            put("state",session.state);put("frames",frames)
        }
        File(sessionDir(session),"session.json").writeText(json.toString(2))
    }

    private fun parseSession(raw:String):CaptureSession?=runCatching{
        val o=JSONObject(raw)
        if(o.optString("id").isBlank()) return@runCatching null
        val s=CaptureSession(
            id=o.getString("id"),
            startedAt=o.optLong("startedAt"),
            endedAt=o.optLong("endedAt").takeIf{it>0},
            state=o.optString("state","ready")
        )
        val arr=o.optJSONArray("frames")?:JSONArray()
        for(i in 0 until arr.length()){
            val f=arr.getJSONObject(i)
            s.frames += CaptureFrame(
                index=f.optInt("index",i),
                fileName=f.optString("fileName"),
                capturedAt=f.optLong("capturedAt"),
                sourcePackage=f.optString("sourcePackage",OSM_PACKAGE),
                width=f.optInt("width"),
                height=f.optInt("height"),
                fingerprint=f.optString("fingerprint","0").toLongOrNull()?:0L,
                textHint=f.optString("textHint"),
                screenType=f.optString("screenType","other"),
                screenTitle=f.optString("screenTitle","Tela do OSM"),
                slotId=f.optInt("slotId",0),
                ocrText=f.optString("ocrText",""),
                analysisState=f.optString("analysisState","captured"),
                extractedFields=f.optInt("extractedFields",0)
            )
        }
        s
    }.getOrNull()
}
