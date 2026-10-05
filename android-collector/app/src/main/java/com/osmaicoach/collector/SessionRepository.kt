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
    private var active: CaptureSession? = null

    @Synchronized
    fun startIfNeeded(): CaptureSession {
        active?.let { return it }
        val now = System.currentTimeMillis()
        val id = "osm-${now}-${UUID.randomUUID().toString().take(8)}"
        return CaptureSession(id = id, startedAt = now).also {
            active = it
            sessionDir(it).mkdirs()
            persist(it)
        }
    }

    @Synchronized
    fun current(): CaptureSession? = active

    @Synchronized
    fun saveFrame(bitmap: Bitmap, fingerprint: Long): CaptureFrame {
        val session = startIfNeeded()
        val index = session.frames.size
        val name = "frame-${index.toString().padStart(4, '0')}.jpg"
        val file = File(sessionDir(session), name)
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out) }
        val frame = CaptureFrame(
            index = index,
            fileName = name,
            capturedAt = System.currentTimeMillis(),
            width = bitmap.width,
            height = bitmap.height,
            fingerprint = fingerprint
        )
        session.frames += frame
        persist(session)
        return frame
    }

    @Synchronized
    fun finish(): CaptureSession? {
        val session = active ?: return null
        session.endedAt = System.currentTimeMillis()
        session.state = "ready"
        persist(session)
        active = null
        context.getSharedPreferences("collector", Context.MODE_PRIVATE)
            .edit().putString("latest_session_id", session.id).apply()
        return session
    }

    fun latestSessionJson(): String {
        val id = context.getSharedPreferences("collector", Context.MODE_PRIVATE)
            .getString("latest_session_id", null) ?: return "null"
        val file = File(File(root, id), "session.json")
        return if (file.exists()) file.readText() else "null"
    }

    fun latestFrameBase64(index: Int): String? {
        val id = context.getSharedPreferences("collector", Context.MODE_PRIVATE)
            .getString("latest_session_id", null) ?: return null
        val sessionJson = JSONObject(latestSessionJson())
        val frames = sessionJson.optJSONArray("frames") ?: return null
        if (index !in 0 until frames.length()) return null
        val name = frames.getJSONObject(index).getString("fileName")
        val bytes = File(File(root, id), name).readBytes()
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    private fun sessionDir(session: CaptureSession) = File(root, session.id)

    private fun persist(session: CaptureSession) {
        val frames = JSONArray()
        session.frames.forEach { frame ->
            frames.put(JSONObject().apply {
                put("index", frame.index)
                put("fileName", frame.fileName)
                put("capturedAt", frame.capturedAt)
                put("sourcePackage", frame.sourcePackage)
                put("width", frame.width)
                put("height", frame.height)
                put("fingerprint", frame.fingerprint.toString())
            })
        }
        val json = JSONObject().apply {
            put("version", 1)
            put("kind", "osm-collector-session")
            put("id", session.id)
            put("startedAt", session.startedAt)
            put("endedAt", session.endedAt ?: JSONObject.NULL)
            put("state", session.state)
            put("frames", frames)
        }
        File(sessionDir(session), "session.json").writeText(json.toString(2))
    }
}
