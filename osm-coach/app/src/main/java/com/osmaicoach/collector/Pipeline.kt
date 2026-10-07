package com.osmaicoach.collector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

object OcrEngine {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun read(bmp: Bitmap): OcrResult {
        val res = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val lines = ArrayList<OcrLine>()
        val tokens = ArrayList<OcrToken>()
        for (block in res.textBlocks) {
            for (ln in block.lines) {
                val r = ln.boundingBox ?: continue
                val toks = ArrayList<OcrToken>()
                for (e in ln.elements) {
                    val b = e.boundingBox ?: continue
                    toks.add(OcrToken(e.text, b.left / w, b.top / h, b.right / w, b.bottom / h))
                }
                lines.add(OcrLine(ln.text, r.left / w, r.top / h, r.right / w, r.bottom / h, toks))
                tokens.addAll(toks)
            }
        }
        return OcrResult(lines, tokens)
    }
}

object ImageIo {
    fun saveJpeg(ctx: Context, bmp: Bitmap, sessionId: String, at: Long): String {
        val dir = File(ctx.filesDir, "frames/$sessionId").apply { mkdirs() }
        val f = File(dir, "$at.jpg")
        val maxW = 1280
        val scaled = if (bmp.width > maxW) {
            Bitmap.createScaledBitmap(bmp, maxW, (maxW.toFloat() * bmp.height / bmp.width).toInt(), true)
        } else bmp
        FileOutputStream(f).use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        if (scaled !== bmp) scaled.recycle()
        return f.absolutePath
    }

    fun toImg(bmp: Bitmap): PixelProbe.Img {
        val pw = 640
        val ph = (pw.toFloat() * bmp.height / bmp.width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bmp, pw, ph, true)
        val px = IntArray(pw * ph)
        small.getPixels(px, 0, pw, 0, 0, pw, ph)
        if (small !== bmp) small.recycle()
        return PixelProbe.Img(pw, ph, px)
    }
}

/**
 * Event/Window -> validação OSM -> impressão digital -> classificação -> máquina de slot ->
 * OCR local -> parser determinístico -> merge persistente -> (fallback de IA em fila).
 */
class FramePipeline private constructor(private val ctx: Context) {
    private val repo = Repo(ctx)
    private val dao = repo.dao
    private val machine = SlotStateMachine()
    private var machineSession: String? = null
    private var candidate: LongArray? = null
    private var stable = 0
    private var lastProcessed: LongArray? = null
    private var lastOwner: Triple<Int, String, Long>? = null
    private var lastOsmAcceptedAt = 0L
    private var imagesThisSession = 0
    private val mutex = Mutex()

    companion object {
        @Volatile
        private var inst: FramePipeline? = null

        fun get(ctx: Context): FramePipeline = inst ?: synchronized(this) {
            inst ?: FramePipeline(ctx.applicationContext).also { inst = it }
        }
    }

    fun nextDelayMs(): Long = if (System.currentTimeMillis() - lastOsmAcceptedAt < 15000L) 600L else 1800L

    private fun ensureMachine(sessionId: String) {
        if (machineSession == sessionId) return
        machineSession = sessionId
        candidate = null
        stable = 0
        lastProcessed = null
        lastOwner = null
        imagesThisSession = 0
        val p = ctx.getSharedPreferences("machine", Context.MODE_PRIVATE)
        if (p.getString("session", null) == sessionId) {
            // processo foi morto no meio da sessão: restaura o contexto do slot
            val hub = ArrayList<SlotIdentity>()
            try {
                val arr = JSONArray(p.getString("hub", "[]") ?: "[]")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val names = HashSet<String>()
                    val na = o.getJSONArray("names")
                    for (j in 0 until na.length()) names.add(na.getString(j))
                    val r = o.optInt("round", -1)
                    hub.add(SlotIdentity(o.getInt("slot"), names, if (r >= 0) r else null))
                }
            } catch (e: Exception) {
                Diag.lastError = "Restaurar estado: " + (e.message ?: "erro")
            }
            val cur = p.getInt("current", -1)
            machine.restore(if (cur in 1..4) cur else null, p.getBoolean("awaiting", false), hub)
        } else {
            machine.restore(null, false, emptyList())
            saveMachine(sessionId)
        }
    }

    private fun saveMachine(sessionId: String) {
        val arr = JSONArray()
        for (h in machine.hubIdentities) {
            val o = JSONObject()
            o.put("slot", h.slot)
            o.put("names", JSONArray(h.names.toList()))
            o.put("round", h.roundNext ?: -1)
            arr.put(o)
        }
        ctx.getSharedPreferences("machine", Context.MODE_PRIVATE).edit()
            .putString("session", sessionId)
            .putInt("current", machine.current ?: -1)
            .putBoolean("awaiting", machine.awaitingEntry)
            .putString("hub", arr.toString())
            .apply()
    }

    private suspend fun parse(ocr: OcrResult, type: ScreenType, img: PixelProbe.Img, now: Long): Extraction = when (type) {
        ScreenType.HUB -> Parsers.hub(ocr)
        ScreenType.PREGAME -> Parsers.pregame(ocr, img, now)
        ScreenType.SQUAD -> Parsers.squad(ocr, img)
        ScreenType.CALENDAR -> Parsers.calendar(ocr, img)
        ScreenType.MARKET -> Parsers.market(ocr)
        ScreenType.REPORT -> Extraction(type, needsAi = true)
        else -> Extraction(type)
    }

    suspend fun onShot(sessionId: String, bmp: Bitmap) {
        mutex.withLock {
            ensureMachine(sessionId)
            Diag.lastShotAt = System.currentTimeMillis()

            val tiny = Bitmap.createScaledBitmap(bmp, FrameHash.W, FrameHash.H, true)
            val tpx = IntArray(FrameHash.W * FrameHash.H)
            tiny.getPixels(tpx, 0, FrameHash.W, 0, 0, FrameHash.W, FrameHash.H)
            if (tiny !== bmp) tiny.recycle()
            val h = FrameHash.of(tpx)

            val cand = candidate
            if (cand != null && FrameHash.distance(cand, h) <= 6) stable++ else {
                candidate = h
                stable = 1
            }
            if (stable < 2) return
            val lp = lastProcessed
            if (lp != null && FrameHash.distance(lp, h) < 12) {
                if (stable == 2) Diag.dedup.incrementAndGet()
                return
            }

            val now = System.currentTimeMillis()
            val img = ImageIo.toImg(bmp)
            val topBar = PixelProbe.isOsmTopBar(img)
            if (!topBar && !PixelProbe.hubBackdrop(img)) {
                Diag.discarded.incrementAndGet()
                Diag.currentType = ScreenType.NON_OSM.name
                lastProcessed = h
                return
            }

            val ocr = OcrEngine.read(bmp)
            Diag.ocr.incrementAndGet()
            val type = ScreenClassifier.classify(ocr, topBar)
            Diag.currentType = type.name
            lastProcessed = h
            if (type == ScreenType.NOISE || type == ScreenType.NON_OSM) {
                Diag.discarded.incrementAndGet()
                return
            }
            Diag.valid.incrementAndGet()
            lastOsmAcceptedAt = now

            var ex = parse(ocr, type, img, now)
            Diag.parsed.incrementAndGet()

            if (ex.ownerTeam == null && (type == ScreenType.SQUAD || type == ScreenType.CALENDAR)) {
                val lo = lastOwner
                if (lo != null && now - lo.third < 120000L && lo.first == (machine.current ?: -1)) {
                    ex = ex.copy(ownerTeam = lo.second)
                }
            }

            val known = repo.knownIdentities()
            val asg = if (type == ScreenType.HUB) {
                machine.onHub(ex.hubCards)
            } else {
                machine.onScreen(type, ex.teamCandidates, ex.roundRead, ex.ownerTeam, known)
            }
            saveMachine(sessionId)
            Diag.currentSlot = machine.current ?: 0

            val own = ex.ownerTeam
            val cur = asg.slot
            if (own != null && cur != null && (type == ScreenType.SQUAD || type == ScreenType.CALENDAR)) {
                lastOwner = Triple(cur, own, now)
            }

            val changed = repo.apply(asg.slot, ex, "ocr", now)
            if (changed > 0) {
                Diag.extracted.addAndGet(changed)
                Diag.lastUpdateAt = now
            }
            val dataType = type == ScreenType.SQUAD || type == ScreenType.CALENDAR ||
                type == ScreenType.MARKET || type == ScreenType.PREGAME || type == ScreenType.REPORT
            val unassigned = asg.slot == null && type != ScreenType.HUB
            if (unassigned) Diag.unassigned.incrementAndGet()

            var path: String? = null
            if (((unassigned && dataType) || ex.needsAi) && imagesThisSession < 60) {
                path = ImageIo.saveJpeg(ctx, bmp, sessionId, now)
                imagesThisSession++
            }
            dao.insertScreen(
                ScreenEntity(
                    sessionId = sessionId, at = now, type = type.name, slotId = asg.slot ?: 0,
                    slotConf = asg.confidence, hash = FrameHash.toText(h), imagePath = path,
                    ocrChars = ocr.fullText.length, extracted = changed,
                    aiState = if (ex.needsAi && path != null) "pending" else "none", note = asg.reason
                )
            )
        }
    }

    /** Reprocessa quadros sem slot (idempotente): tenta identificar pelo dono do cabeçalho/pré-jogo. */
    suspend fun reprocessUnassigned(limit: Int = 40): Int {
        var fixed = 0
        val now = System.currentTimeMillis()
        for (s in dao.unassignedWithImage(limit)) {
            val path = s.imagePath ?: continue
            val bmp = BitmapFactory.decodeFile(path) ?: continue
            try {
                val img = ImageIo.toImg(bmp)
                val topBar = PixelProbe.isOsmTopBar(img)
                val ocr = OcrEngine.read(bmp)
                val type = ScreenClassifier.classify(ocr, topBar)
                val ex = parse(ocr, type, img, s.at)
                val known = repo.knownIdentities()
                val m = when (type) {
                    ScreenType.PREGAME -> SlotMatcher.match(ex.teamCandidates, ex.roundRead, known)
                    ScreenType.SQUAD, ScreenType.CALENDAR ->
                        ex.ownerTeam?.let { SlotMatcher.match(listOf(it), null, known) }?.takeIf { it.conf >= 0.85 }
                    else -> null
                }
                if (m != null) {
                    val changed = repo.apply(m.slot, ex, "reprocess", now)
                    dao.updateScreenSlot(s.id, m.slot, m.conf, changed, "reprocessado")
                    fixed++
                }
            } finally {
                bmp.recycle()
            }
        }
        return fixed
    }
}
