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
                if (Overlay.isChip(ln.text)) continue
                if (Overlay.covers((r.left + r.right) / 2f / w, (r.top + r.bottom) / 2f / h)) continue
                // Palavra a palavra: o OCR pode juntar o botão flutuante ("Encerrar") com o nome que está atrás
                // dele ("Encerraroden"). Tira as palavras que caem sobre os botões e refaz a linha sem elas.
                val toks = ArrayList<OcrToken>()
                var dropped = false
                for (e in ln.elements) {
                    val b = e.boundingBox ?: continue
                    val t = OcrToken(e.text, b.left / w, b.top / h, b.right / w, b.bottom / h)
                    if (Overlay.isChip(e.text) || Overlay.covers(t.xc, t.yc) || Overlay.glued(e.text)) {
                        dropped = true
                        continue
                    }
                    toks.add(t)
                }
                if (dropped) {
                    if (toks.isEmpty()) continue
                    lines.add(OcrLine(toks.joinToString(" ") { it.text }, toks.minOf { it.l }, toks.minOf { it.t }, toks.maxOf { it.r }, toks.maxOf { it.b }, toks))
                } else {
                    lines.add(OcrLine(ln.text, r.left / w, r.top / h, r.right / w, r.bottom / h, toks))
                }
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

    /** Apaga imagens guardadas há mais de 30 dias (sem isso a pasta só crescia). */
    fun prune(ctx: Context) {
        val limit = System.currentTimeMillis() - 30L * 86400000L
        try {
            File(ctx.filesDir, "frames").listFiles()?.forEach { dir ->
                dir.listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
                if (dir.listFiles()?.isEmpty() == true) dir.delete()
            }
        } catch (e: Exception) {
            Diag.lastError = "Limpeza de imagens: " + (e.message ?: e.javaClass.simpleName)
        }
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
    private var lastProcessedAt = 0L
    private var dupCounted = false
    private var lastProcessed: LongArray? = null
    private val lastOwners = HashMap<ScreenType, Triple<Int, String, Long>>()
    private val noSlot = ArrayList<Pair<Extraction, Long>>()
    private var lastType: ScreenType? = null
    private var lastCheck = ""
    private var lastCheckAt = 0L

    /** Tela de tática do OSM aberta: compara com a tática gerada e avisa por cima do jogo o que está diferente. */
    private suspend fun checkTactic(slot: Int, ocr: OcrResult, now: Long) {
        val p = dao.plan(slot, "tactic")
        val j = p?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
        val f = repo.fieldMap(slot)
        val msg = if (p == null || j == null || !Director.tacticValid(j, p.at, f, now)) {
            "S$slot: sem tática gerada para este jogo. Gere no app antes de montar."
        } else {
            val w = Parsers.tacticCheck(ocr, j, f[K.REFEREE]?.value)
            // igual ao gerado: a tarefa "montar a tática" está feita
            if (w.isEmpty()) dao.putPlan(PlanEntity(slot, "tacticdone", JSONObject().put("at", p.at).toString(), now))
            if (w.isEmpty()) "" else "⚠ S$slot: " + w.joinToString(" • ")
        }
        if (msg.isEmpty() || (msg == lastCheck && now - lastCheckAt < 20000L)) return
        lastCheck = msg
        lastCheckAt = now
        Diag.log("Tática no OSM → $msg")
        CaptureService.instance?.notice(msg)
    }
    private var seasonSlot: Int? = null
    private var seasonSlotAt = 0L
    private var reportCandidates = 0
    private var resultSaved = 0
    private var pregameSaved = 0
    private var squadSaved = 0
    private var seasonSaved = 0
    private var tacticSaved = 0
    private var calendarSaved = 0
    private var lastOsmAcceptedAt = 0L
    private val pregameAiAt = HashMap<Int, Long>()
    private var imagesThisSession = 0
    private val mutex = Mutex()

    companion object {
        @Volatile
        private var inst: FramePipeline? = null

        fun get(ctx: Context): FramePipeline = inst ?: synchronized(this) {
            inst ?: FramePipeline(ctx.applicationContext).also { inst = it }
        }
    }

    init {
        Diag.init(ctx)
        ImageIo.prune(ctx)
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
        tacticSaved = 0
        calendarSaved = 0
        resultSaved = 0
        pregameSaved = 0
        squadSaved = 0
        seasonSaved = 0
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
        ScreenType.STADIUM -> Parsers.stadium(ocr, img, now)
        ScreenType.RESULT -> Parsers.matchResult(ocr)
        ScreenType.TRAINING -> Extraction(ScreenType.TRAINING, trainees = Parsers.training(ocr, now))
        ScreenType.PREP -> Extraction(ScreenType.PREP, extra = Screens.prep(ocr, img))
        ScreenType.DOCTOR, ScreenType.LAWYER -> Extraction(type, extra = Screens.roster(ocr))
        ScreenType.SPECIALISTS -> Extraction(ScreenType.SPECIALISTS, extra = Screens.specialists(ocr))
        ScreenType.SCOUT -> Extraction(ScreenType.SCOUT, extra = Screens.scout(ocr))
        ScreenType.DEALS -> Extraction(ScreenType.DEALS, extra = Screens.deals(ocr))
        ScreenType.STATS -> Extraction(ScreenType.STATS, extra = Screens.stats(ocr))
        ScreenType.MISSIONS -> Extraction(ScreenType.MISSIONS, extra = Screens.missions(ocr))
        ScreenType.SPONSORS -> Extraction(ScreenType.SPONSORS)
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
        ScreenType.STADIUM -> "Estádio"
        ScreenType.RESULT -> "Resultado do jogo"
        ScreenType.PREP -> "Preparação para o jogo"
        ScreenType.DOCTOR -> "Médico"
        ScreenType.LAWYER -> "Advogado"
        ScreenType.SPECIALISTS -> "Especialistas"
        ScreenType.SPONSORS -> "Patrocinadores"
        ScreenType.SCOUT -> "Olheiro"
        ScreenType.DEALS -> "Negociações"
        ScreenType.STATS -> "Estatísticas da liga"
        ScreenType.MISSIONS -> "Missões"
        else -> "Outra tela do OSM"
    }

    /**
     * Recorta o escudo de cada card da central para mostrar na interface. Time novo no card (liga nova) troca o
     * escudo na hora; o mesmo time renova no máx. 1x a cada 6 h. Slot vazio/procurando não tem escudo: fica o antigo.
     */
    private fun saveCrests(bmp: Bitmap, cards: List<HubCard>) {
        val dir = File(ctx.filesDir, "crests").apply { mkdirs() }
        for (card in cards) {
            if (card.state != null && card.state != "preepoca") continue
            val slot = card.slot
            val f = File(dir, "s$slot.png")
            val tag = File(dir, "s$slot.txt")
            val team = Txt.key(card.team)
            val sameTeam = team.isNotEmpty() && tag.exists() && runCatching { tag.readText() }.getOrNull() == team
            if (f.exists() && sameTeam && System.currentTimeMillis() - f.lastModified() < 6L * 3600L * 1000L) continue
            val cx = (if (slot % 2 == 1) 0.5867f else 0.7953f) + card.dx
            val cy = (if (slot <= 2) 0.263f else 0.711f) + card.dy
            val x = ((cx - 0.04f) * bmp.width).toInt().coerceIn(0, bmp.width - 2)
            val y = ((cy - 0.09f) * bmp.height).toInt().coerceIn(0, bmp.height - 2)
            val w = (0.08f * bmp.width).toInt().coerceAtMost(bmp.width - x)
            val hh = (0.18f * bmp.height).toInt().coerceAtMost(bmp.height - y)
            if (w < 8 || hh < 8) continue
            try {
                val crop = Bitmap.createBitmap(bmp, x, y, w, hh)
                FileOutputStream(f).use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
                crop.recycle()
                if (team.isNotEmpty()) tag.writeText(team)
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

            // O screenshot é nítido mesmo durante a rolagem: processa todo quadro que mudou o bastante.
            // Perto do último processamento (< 2,5 s) exige mudança maior, para não repetir OCR em tela animada.
            val nowGate = System.currentTimeMillis()
            val lp = lastProcessed
            val dist = if (lp == null) Int.MAX_VALUE else FrameHash.distance(lp, h)
            // Pré-jogo alterna sozinho força <-> bônus nos círculos (mudança pequena na imagem): relê a cada 3 s.
            val need = if (lastType == ScreenType.PREGAME && nowGate - lastProcessedAt >= 3000L) 1
            else if (nowGate - lastProcessedAt < 2500L) 60 else 24
            if (dist < need) {
                if (!dupCounted) {
                    Diag.dedup.incrementAndGet()
                    dupCounted = true
                }
                return
            }
            dupCounted = false

            val now = System.currentTimeMillis()
            val img = ImageIo.toImg(bmp)
            val topBar = PixelProbe.isOsmTopBar(img)
            // Telas cheias do jogo sem a barra superior (ex.: análise do rival) ainda são do OSM: se o jogo estava
            // em primeiro plano há pouco, lê o texto e decide por ele (anúncios não têm o vocabulário do jogo).
            val recentlyOsm = now - lastOsmAcceptedAt < 90000L
            if (!topBar && !PixelProbe.hubBackdrop(img) && !recentlyOsm) {
                Diag.discarded.incrementAndGet()
                Diag.currentType = ScreenType.NON_OSM.name
                Diag.log("Descartada: não é tela do OSM (anúncio ou outro app)")
                lastProcessed = h
                return
            }

            val ocr = OcrEngine.read(bmp)
            lastProcessedAt = now
            Diag.ocr.incrementAndGet()
            // Tela do próprio OSM Coach (barra "Hoje/Sessões/Slots/Diretor/Ajustes" ou textos dele): não é o jogo.
            if (OwnApp.looksLikeApp(ocr.fullText)) {
                Diag.discarded.incrementAndGet()
                Diag.log("Descartada: tela do próprio OSM Coach")
                lastProcessed = h
                return
            }
            val type = ScreenClassifier.classify(ocr, topBar)
            Diag.currentType = type.name
            lastType = type
            if (type != ScreenType.NOISE && type != ScreenType.NON_OSM) {
                // texto lido com a posição de cada linha: é o que permite conferir depois o que o app viu
                // (telas do fim do jogo, plantel inteiro, pré-jogo com os bônus)
                Diag.trace("OCR ${type.name} S${Diag.currentSlot}: " + ocr.lines.joinToString(" | ") {
                    it.text.trim() + "@" + "%.2f,%.2f".format(java.util.Locale.US, it.xc, it.yc)
                }.take(4000))
            }
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
                val mentions = Parsers.isAnalysis(ocr) ||
                    (rn != null && Txt.key(rn).length >= 4 && Txt.key(ocr.fullText).contains(Txt.key(rn)))
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
            var asg = if (type == ScreenType.HUB) {
                machine.onHub(ex.hubCards)
            } else {
                machine.onScreen(type, ex.teamCandidates, ex.roundRead, ex.ownerTeam, known)
            }
            // Pré-jogo do MEU time (apelido ao lado) que não bate com nenhum slot: é a competição nova do slot que está
            // em pré-época / procurando adversário / livre (batalha nova com outro clube). Antes ficava "sem identidade"
            // e nada do slot novo era lido.
            if (asg.slot == null && type == ScreenType.PREGAME && ex.fields.containsKey(K.TEAM)) {
                val team = ex.fields[K.TEAM]?.value
                val open = if (team != null) repo.openSlotFor(team, ocr.fullText) else null
                if (open != null) {
                    repo.startCompetition(open, team!!, now)
                    machine.adopt(open)
                    asg = Assignment(open, 0.8, "pré-jogo da competição nova")
                    Diag.log("Competição nova reconhecida: $team → S$open")
                }
            }
            saveMachine(sessionId)
            Diag.currentSlot = machine.current ?: 0

            val own = ex.ownerTeam
            val cur = asg.slot
            if (own != null && cur != null && (type == ScreenType.SQUAD || type == ScreenType.CALENDAR)) {
                lastOwners[type] = Triple(cur, own, now)
            }

            if (type == ScreenType.HUB) saveCrests(bmp, ex.hubCards)
            // Telas de dados lidas antes de o slot ser reconhecido (entrou no slot por uma tela sem nome do time):
            // ficam guardadas e vão para o slot assim que ele é identificado, sem passar pela central no meio.
            val asgSlot = asg.slot
            if (type == ScreenType.HUB) {
                noSlot.clear()
            } else if (asgSlot == null && (type == ScreenType.REPORT || type == ScreenType.PREGAME || type == ScreenType.SQUAD)) {
                noSlot.add(Pair(ex, now))
                while (noSlot.size > 15) noSlot.removeAt(0)
            } else if (asgSlot != null && noSlot.isNotEmpty()) {
                var late = 0
                for ((e, at) in noSlot) if (now - at < 300000L) late += repo.apply(asgSlot, e, "ocr", at)
                Diag.log("${noSlot.size} tela(s) lidas antes de reconhecer o slot → S$asgSlot (+$late campos)")
                noSlot.clear()
            }
            var changed = repo.apply(asg.slot, ex, "ocr", now)
            runCatching { repo.smartReads(asg.slot, type, ocr, now) }
            // Fim da competição / diretoria: não é tela de adversário. Vai para o aprendizado do slot (reconhecido pela
            // navegação ou pelo nome do meu time na classificação) e "a época acabou" encerra a competição do slot.
            if (type == ScreenType.OTHER_OSM && SeasonScreens.isSeasonScreen(ocr.fullText)) {
                val full = Txt.key(ocr.fullText)
                val byName = known.filter { id -> id.names.any { it.length >= 5 && full.contains(it) } }.map { it.slot }.distinct()
                // telas sem nome de time ("A época acabou...") seguem o slot da tela de fim de época vista há pouco
                val ss = asgSlot ?: byName.singleOrNull() ?: seasonSlot?.takeIf { now - seasonSlotAt < 180000L }
                if (ss != null) {
                    seasonSlot = ss
                    seasonSlotAt = now
                    val sr = Parsers.seasonSummary(ocr, repo.fieldMap(ss)[K.TEAM]?.value)
                    changed += repo.recordSeasonScreen(ss, sr, now)
                    Diag.log("Fim de temporada/direção → S$ss" + (sr.rank?.let { " (${it}º lugar)" } ?: "") + (if (sr.over) " • competição encerrada" else ""))
                }
            }
            if (type == ScreenType.TACTIC && asgSlot != null) {
                try { checkTactic(asgSlot, ocr, now) } catch (e: Exception) { Diag.lastError = "Conferir tática: " + (e.message ?: "") }
            }
            val counts = when (type) {
                ScreenType.CALENDAR -> " [${ex.matches.size} cards" + (if (ex.ownerTeam == null) ", sem dono" else "") + "]"
                ScreenType.SQUAD -> " [${ex.players.size} jogadores" + (if (ex.ownerTeam == null) ", sem dono" else "") + "]"
                ScreenType.MARKET -> " [${ex.listings.size} jogadores à venda]"
                ScreenType.REPORT -> " [${ex.fields.size} campos do relatório]"
                ScreenType.RESULT -> " [placar ${ex.matchReport?.scoreHome ?: "?"}-${ex.matchReport?.scoreAway ?: "?"}, ${ex.matchReport?.stats?.size ?: 0} estatísticas, ${(ex.matchReport?.ratingsHome?.size ?: 0) + (ex.matchReport?.ratingsAway?.size ?: 0)} notas]"
                else -> ""
            }
            val hint = if (type == ScreenType.OTHER_OSM || type == ScreenType.TACTIC || type == ScreenType.REPORT || type == ScreenType.STADIUM) {
                " [" + ocr.lines.map { it.text.trim() }.filter { Txt.letters(it) >= 4 }.take(3).joinToString(" | ").take(70) + "]"
            } else ""
            Diag.log(
                typeLabel(type) + " → " + (asg.slot?.let { "S$it" } ?: if (type == ScreenType.HUB) "central" else "sem slot") +
                    (if (changed > 0) " (+$changed campos)" else "") + counts + hint
            )
            if (changed > 0) {
                Diag.extracted.addAndGet(changed)
                Diag.lastUpdateAt = now
                if (type == ScreenType.PREGAME || type == ScreenType.CALENDAR) {
                    try {
                        Notifier.reschedule(ctx)
                    } catch (e: Exception) {
                        Diag.lastError = "Alarmes: " + (e.message ?: e.javaClass.simpleName)
                    }
                }
            }
            val dataType = type == ScreenType.SQUAD || type == ScreenType.CALENDAR ||
                type == ScreenType.MARKET || type == ScreenType.PREGAME || type == ScreenType.REPORT || type == ScreenType.RESULT
            val unassigned = asg.slot == null && type != ScreenType.HUB
            if (unassigned) Diag.unassigned.incrementAndGet()

            // Tela desconhecida do OSM com cara de relatório do rival: guarda a imagem para a IA confirmar e ler.
            val rivalName = asg.slot?.let { repo.fieldMap(it)[K.RIVAL_TEAM]?.value }
            val mentionsRival = rivalName != null && Txt.key(rivalName).length >= 4 &&
                Txt.key(ocr.fullText).contains(Txt.key(rivalName))
            // A tela de introdução do analista (cartoon + botão) não tem dados: não vale guardar para a IA.
            val analystIntro = Txt.norm(ocr.fullText).contains("relatorio do analista")
            val reportHint = type == ScreenType.OTHER_OSM && asg.slot != null && !analystIntro &&
                reportCandidates < 12 && (Parsers.hasReportHint(ocr) || mentionsRival)
            // Diagnóstico: guarda poucas telas de tática e calendários "sem novidade" para eu ver o que o app viu.
            val diagTactic = type == ScreenType.TACTIC && asg.slot != null && tacticSaved < 6
            val diagCalendar = type == ScreenType.CALENDAR && changed == 0 && ex.matches.isNotEmpty() && calendarSaved < 4
            // Fim do jogo (análise, estatísticas, notas), pré-jogo (bônus) e plantel: imagens para o diagnóstico exportado
            val diagResult = type == ScreenType.RESULT && resultSaved < 16
            val diagPregame = type == ScreenType.PREGAME && pregameSaved < 6
            val diagSquad = type == ScreenType.SQUAD && squadSaved < 10
            // fim de época / direção: guarda para dar para reprocessar e conferir depois
            val diagSeason = type == ScreenType.OTHER_OSM && seasonSaved < 12 && SeasonScreens.isSeasonScreen(ocr.fullText)
            // pré-jogo em que o OCR não achou o rival ou as forças/bônus: a IA lê a imagem ao encerrar a captura
            // (uma por slot a cada 2 min: o pré-jogo é relido a cada 3 s e não pode gastar a cota da IA)
            val pregameGap = type == ScreenType.PREGAME && (
                !ex.fields.containsKey(K.RIVAL_TEAM) ||
                    (!ex.fields.containsKey(K.MY_STRENGTH) && !ex.fields.containsKey(K.MY_BONUS))
                ) && now - (pregameAiAt[asg.slot ?: 0] ?: 0L) > 120000L
            if (pregameGap) pregameAiAt[asg.slot ?: 0] = now
            // Só vale gastar a IA com tela que tem cara de relatório E quando ainda falta a formação/estilo do rival
            // (antes qualquer tela citando o rival ia para a fila: escritório, menus…)
            val rivalKnown = asg.slot?.let { sl ->
                val rf = repo.fieldMap(sl)
                FieldMerge.known(rf[K.RIVAL_FORMATION]?.value) && FieldMerge.known(rf[K.RIVAL_PLAN]?.value)
            } ?: true
            val reportForAi = reportHint && Parsers.hasReportHint(ocr) && !rivalKnown
            var path: String? = null
            if (((unassigned && dataType) || ex.needsAi || reportHint || pregameGap || diagTactic || diagCalendar || diagResult || diagPregame || diagSquad || diagSeason) &&
                imagesThisSession < 90
            ) {
                if (diagTactic) tacticSaved++
                if (diagCalendar) calendarSaved++
                if (diagResult) resultSaved++
                if (diagPregame) pregameSaved++
                if (diagSquad) squadSaved++
                if (diagSeason) seasonSaved++
                path = ImageIo.saveJpeg(ctx, bmp, sessionId, now)
                imagesThisSession++
                if (reportHint) reportCandidates++
            }
            dao.insertScreen(
                ScreenEntity(
                    sessionId = sessionId, at = now, type = type.name, slotId = asg.slot ?: 0,
                    slotConf = asg.confidence, hash = FrameHash.toText(h), imagePath = path,
                    ocrChars = ocr.fullText.length, extracted = changed,
                    aiState = if ((ex.needsAi || reportForAi || pregameGap) && path != null) "pending" else "none", note = asg.reason
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
                            ?: if (type == ScreenType.SQUAD) ex.ownerTeam?.let { SlotMatcher.matchRival(listOf(it), known) } else null
                    ScreenType.RESULT ->
                        SlotMatcher.match(ex.ownerTeam?.let { listOf(it) } ?: ex.teamCandidates, null, known)?.takeIf { it.conf >= 0.85 }
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

    data class ReplayResult(val screens: Int, val read: Int, val fields: Int, val bySlot: Map<Int, Int>)

    /**
     * Reprocessa a captura inteira (todas as telas guardadas da sessão, em ordem), com os leitores atuais: o que
     * uma versão nova do app aprendeu a ler entra nos slots sem precisar navegar de novo. Leitura antiga nunca
     * sobrescreve dado mais novo (o Repo compara a hora da tela com a do campo).
     */
    suspend fun replaySession(sessionId: String, onStep: (Int, Int) -> Unit = { _, _ -> }): ReplayResult {
        val list = dao.screensOfSession(sessionId).filter { it.imagePath != null && File(it.imagePath).exists() }
        var read = 0
        var fields = 0
        val bySlot = HashMap<Int, Int>()
        val owners = HashMap<ScreenType, Triple<Int, String, Long>>()
        var lastSeasonSlot: Int? = null
        var lastSeasonAt = 0L
        for ((i, s) in list.withIndex()) {
            onStep(i, list.size)
            val bmp = BitmapFactory.decodeFile(s.imagePath ?: continue) ?: continue
            try {
                val img = ImageIo.toImg(bmp)
                val ocr = OcrEngine.read(bmp)
                val type = ScreenClassifier.classify(ocr, PixelProbe.isOsmTopBar(img))
                var ex = parse(ocr, type, img, s.at)
                read++
                val known = repo.knownIdentities()
                // o slot gravado na hora da captura veio da navegação: vale; sem ele, tenta pelos nomes da tela
                val slot = s.slotId.takeIf { it in 1..4 } ?: when (type) {
                    ScreenType.PREGAME -> SlotMatcher.match(ex.teamCandidates, ex.roundRead, known)?.slot
                    ScreenType.SQUAD, ScreenType.CALENDAR, ScreenType.RESULT ->
                        SlotMatcher.match(ex.ownerTeam?.let { listOf(it) } ?: ex.teamCandidates, null, known)?.takeIf { it.conf >= 0.85 }?.slot
                    else -> null
                }
                // lista rolada sem cabeçalho: o dono é o da última tela do mesmo tipo e slot (como na captura ao vivo)
                if (slot != null && ex.ownerTeam == null && (type == ScreenType.SQUAD || type == ScreenType.CALENDAR)) {
                    owners[type]?.takeIf { it.first == slot && s.at - it.third < 600000L }?.let { ex = ex.copy(ownerTeam = it.second) }
                }
                if (slot != null && ex.ownerTeam != null && (type == ScreenType.SQUAD || type == ScreenType.CALENDAR)) {
                    owners[type] = Triple(slot, ex.ownerTeam!!, s.at)
                }
                var changed = if (type == ScreenType.HUB || slot != null) repo.apply(slot, ex, "reprocess", s.at) else 0
                if (type == ScreenType.OTHER_OSM && SeasonScreens.isSeasonScreen(ocr.fullText)) {
                    val full = Txt.key(ocr.fullText)
                    val ss = slot ?: known.filter { id -> id.names.any { it.length >= 5 && full.contains(it) } }.map { it.slot }.distinct().singleOrNull()
                        ?: lastSeasonSlot?.takeIf { s.at - lastSeasonAt < 180000L }
                    if (ss != null) {
                        lastSeasonSlot = ss
                        lastSeasonAt = s.at
                        changed += repo.recordSeasonScreen(ss, Parsers.seasonSummary(ocr, repo.fieldMap(ss)[K.TEAM]?.value), s.at)
                    }
                }
                if (changed > 0 && slot != null) bySlot[slot] = (bySlot[slot] ?: 0) + changed
                fields += changed
            } catch (e: Exception) {
                Diag.lastError = "Reprocessar tela ${s.id}: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                bmp.recycle()
            }
        }
        onStep(list.size, list.size)
        for (slot in 1..4) runCatching { repo.syncRound(slot) }
        return ReplayResult(list.size, read, fields, bySlot)
    }
}