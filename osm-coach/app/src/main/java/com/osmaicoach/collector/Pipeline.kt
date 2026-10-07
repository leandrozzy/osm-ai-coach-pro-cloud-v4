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
    private var unstable = 0
    private var lastProcessed: LongArray? = null
    private val lastOwners = HashMap<ScreenType, Triple<Int, String, Long>>()
    private var reportCandidates = 0
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
        unstable = 0
        lastProcessed = null
        lastOwners.clear()
        reportCandidates = 0
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
        ScreenType.REPORT -> Parsers.report(ocr)
        else -> Extraction(type)
    }

    private fun typeLabel(t: ScreenType): String = when (t) {
        ScreenType.HUB -> "Central dos slots"
        ScreenType.PREGAME -> "Pré-jogo"
        ScreenType.SQUAD -> "Elenco"
        ScreenType.CALENDAR -> "Calendário"
        ScreenType.MARKET -> "Mercado"
        ScreenType.TRAINING -> "Treinamento"
        ScreenType.TACTIC -> "Tática"
        ScreenType.REPORT -> "Relatório"
        else -> "Outra tela do OSM"
    }

    /** Recorta o escudo de cada card da central para mostrar na interface (no máx. 1x a cada 6 h por slot). */
    private fun saveCrests(bmp: Bitmap, slots: List<Int>) {
        val dir = File(ctx.filesDir, "crests").apply { mkdirs() }
        for (slot in slots) {
            val f = File(dir, "s$slot.png")
            if (f.exists() && System.currentTimeMillis() - f.lastModified() < 6L * 3600L * 1000L) continue
            val cx = if (slot % 2 == 1) 0.5867f else 0.7953f
            val cy = if (slot <= 2) 0.263f else 0.711f
            val x = ((cx - 0.04f) * bmp.width).toInt().coerceIn(0, bmp.width - 2)
            val y = ((cy - 0.09f) * bmp.height).toInt().coerceIn(0, bmp.height - 2)
            val w = (0.08f * bmp.width).toInt().coerceAtMost(bmp.width - x)
            val hh = (0.18f * bmp.height).toInt().coerceAtMost(bmp.height - y)
            if (w < 8 || hh < 8) continue
            try {
                val crop = Bitmap.createBitmap(bmp, x, y, w, hh)
                FileOutputStream(f).use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
                crop.recycle()
            } catch (e: Exception) {
                Diag.lastError = "Escudo: " + (e.message ?: "erro")
            }
        }
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
            if (cand != null && FrameHash.distance(cand, h) <= 60) {
                stable++
                unstable = 0
            } else {
                candidate = h
                stable = 1
                unstable++
            }
            // tela sempre animada: depois de ~5 amostras instáveis, processa mesmo assim
            if (unstable >= 5 && stable < 2) {
                stable = 2
                unstable = 0
            }
            if (stable < 2) return
            val lp = lastProcessed
            if (lp != null && FrameHash.distance(lp, h) < 24) {
                if (stable == 2) Diag.dedup.incrementAndGet()
                return
            }

            val now = System.currentTimeMillis()
            val img = ImageIo.toImg(bmp)
            val topBar = PixelProbe.isOsmTopBar(img)
            if (!topBar && !PixelProbe.hubBackdrop(img)) {
                Diag.discarded.incrementAndGet()
                Diag.currentType = ScreenType.NON_OSM.name
                Diag.log("Descartada: não é tela do OSM (anúncio ou outro app)")
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
                Diag.log(if (type == ScreenType.NOISE) "Descartada: transição/tela vazia" else "Descartada: sem barra do OSM")
                return
            }
            Diag.valid.incrementAndGet()
            lastOsmAcceptedAt = now

            var ex = parse(ocr, type, img, now)
            Diag.parsed.incrementAndGet()

            // Rolando a lista o cabeçalho some: reaproveita o dono lido por último (mesmo tipo e slot, até 10 min).
            if (type == ScreenType.REPORT) {
                val rn = machine.current?.let { repo.fieldMap(it)[K.RIVAL_TEAM]?.value }
                val mentions = rn != null && Txt.key(rn).length >= 4 && Txt.key(ocr.fullText).contains(Txt.key(rn))
                if (!mentions) ex = Extraction(ScreenType.REPORT, needsAi = true, note = "sem nome do rival")
            }
            if (ex.ownerTeam == null && (type == ScreenType.SQUAD || type == ScreenType.CALENDAR)) {
                val lo = lastOwners[type]
                if (lo != null && now - lo.third < 600000L && lo.first == (machine.current ?: -1)) {
                    ex = ex.copy(ownerTeam = lo.second)
                }
            }
            // Trocou de tipo de tela: o dono da lista anterior deixa de valer.
            if (type == ScreenType.HUB || type == ScreenType.PREGAME || type == ScreenType.MARKET) {
                lastOwners.clear()
            } else if (type == ScreenType.SQUAD) {
                lastOwners.remove(ScreenType.CALENDAR)
            } else if (type == ScreenType.CALENDAR) {
                lastOwners.remove(ScreenType.SQUAD)
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
                lastOwners[type] = Triple(cur, own, now)
            }

            if (type == ScreenType.HUB) saveCrests(bmp, ex.hubCards.map { it.slot })
            val changed = repo.apply(asg.slot, ex, "ocr", now)
            val counts = when (type) {
                ScreenType.CALENDAR -> " [${ex.matches.size} cards" + (if (ex.ownerTeam == null) ", sem dono" else "") + "]"
                ScreenType.SQUAD -> " [${ex.players.size} jogadores" + (if (ex.ownerTeam == null) ", sem dono" else "") + "]"
                ScreenType.MARKET -> " [${ex.listings.size} jogadores à venda]"
                else -> ""
            }
            val hint = if (type == ScreenType.OTHER_OSM) {
                " [" + ocr.lines.map { it.text.trim() }.filter { Txt.letters(it) >= 4 }.take(3).joinToString(" | ").take(70) + "]"
            } else ""
            Diag.log(
                typeLabel(type) + " → " + (asg.slot?.let { "S$it" } ?: if (type == ScreenType.HUB) "central" else "sem slot") +
                    (if (changed > 0) " (+$changed campos)" else "") + counts + hint
            )
            if (changed > 0) {
                Diag.extracted.addAndGet(changed)
                Diag.lastUpdateAt = now
            }
            val dataType = type == ScreenType.SQUAD || type == ScreenType.CALENDAR ||
                type == ScreenType.MARKET || type == ScreenType.PREGAME || type == ScreenType.REPORT
            val unassigned = asg.slot == null && type != ScreenType.HUB
            if (unassigned) Diag.unassigned.incrementAndGet()

            // Tela desconhecida do OSM com cara de relatório do rival: guarda a imagem para a IA confirmar e ler.
            val rivalName = asg.slot?.let { repo.fieldMap(it)[K.RIVAL_TEAM]?.value }
            val mentionsRival = rivalName != null && Txt.key(rivalName).length >= 4 &&
                Txt.key(ocr.fullText).contains(Txt.key(rivalName))
            val reportHint = type == ScreenType.OTHER_OSM && asg.slot != null &&
                reportCandidates < 12 && (Parsers.hasReportHint(ocr) || mentionsRival)
            var path: String? = null
            if (((unassigned && dataType) || ex.needsAi || reportHint) && imagesThisSession < 60) {
                path = ImageIo.saveJpeg(ctx, bmp, sessionId, now)
                imagesThisSession++
                if (reportHint) reportCandidates++
            }
            dao.insertScreen(
                ScreenEntity(
                    sessionId = sessionId, at = now, type = type.name, slotId = asg.slot ?: 0,
                    slotConf = asg.confidence, hash = FrameHash.toText(h), imagePath = path,
                    ocrChars = ocr.fullText.length, extracted = changed,
                    aiState = if ((ex.needsAi || reportHint) && path != null) "pending" else "none", note = asg.reason
                )
            )
        }
    }

    /** Reprocessa quadros sem slot (idempotente): tenta identificar pelo dono do cabeçalho/pré-jogo. */
    suspend fun reprocessUnassigned(limit: Int = 40, onStep: () -> Unit = {}): Int {
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
                onStep()
            }
        }
        return fixed
    }
}
