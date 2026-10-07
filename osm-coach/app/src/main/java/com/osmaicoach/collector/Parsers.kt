package com.osmaicoach.collector

import kotlin.math.abs

/**
 * Parsers determinísticos baseados em posição (colunas/linhas) das caixas do OCR.
 * Regra de ouro: o que não for lido com segurança fica ausente (NI) — nunca é chutado.
 */
object Parsers {
    private val RX_HUB_ROUND = Regex("(\\d{1,2})\\s*/\\s*(\\d{1,2})\\s*jornada")
    private val RX_JORNADA = Regex("^jornada\\s*(\\d{1,2})$")
    private val RX_DATE = Regex("\\b(\\d{2})-(\\d{2})-(\\d{2})\\b")
    private val RX_TIME = Regex("^(\\d{1,2}):(\\d{2})$")
    private val RX_SCORE = Regex("^(\\d{1,2})\\s*[-–—]\\s*(\\d{1,2})$")
    private val RX_FORMATION = Regex("([3-5]-\\d-\\d(?:-\\d)?)\\s*([A-Da-d])?")
    private val RX_SELLING = Regex("vender jogadores\\s*(\\d)\\s*/\\s*(\\d)")

    private fun intTok(t: OcrToken): Int? {
        val s = t.text.trim()
        return if (s.isNotEmpty() && s.length <= 3 && s.all { it.isDigit() }) s.toIntOrNull() else null
    }

    private fun isNick(s: String): Boolean {
        val t = s.trim()
        return t.length in 3..24 && Txt.letters(t) >= 2 && !t.all { it.isDigit() || it == ' ' || it == '.' || it == ',' }
    }

    // ---------------------------------------------------------------- HUB
    fun hub(o: OcrResult): Extraction {
        val cards = ArrayList<HubCard>()
        for (slot in 1..4) {
            val col = if (slot % 2 == 1) 0 else 1
            val row = if (slot <= 2) 0 else 1
            val x0 = if (col == 0) 0.47f else 0.69f
            val x1 = if (col == 0) 0.69f else 0.90f
            val y0 = if (row == 0) 0.04f else 0.50f
            val y1 = if (row == 0) 0.495f else 0.97f
            var done: Int? = null
            var total: Int? = null
            val rest = ArrayList<OcrLine>()
            for (ln in o.lines) {
                if (ln.xc < x0 || ln.xc > x1 || ln.yc < y0 || ln.yc > y1) continue
                val m = RX_HUB_ROUND.find(Txt.norm(ln.text))
                if (m != null) {
                    done = m.groupValues[1].toIntOrNull()
                    total = m.groupValues[2].toIntOrNull()
                } else {
                    rest.add(ln)
                }
            }
            val minY = y0 + (y1 - y0) * 0.55f
            val names = rest.filter { it.yc >= minY && Txt.letters(it.text) >= 3 }.sortedBy { it.yc }
            if (names.isEmpty()) continue
            cards.add(HubCard(slot, names[0].text.trim(), names.getOrNull(1)?.text?.trim() ?: "", done, total))
        }
        return Extraction(ScreenType.HUB, hubCards = cards)
    }

    // ---------------------------------------------------------------- PRÉ-JOGO
    fun pregame(o: OcrResult, img: PixelProbe.Img?, nowMs: Long): Extraction {
        val f = LinkedHashMap<String, Reading>()
        var roundRead: Int? = null

        for (ln in o.lines) {
            if (ln.yc >= 0.25f || ln.xc !in 0.40f..0.60f) continue
            val n = Txt.norm(ln.text)
            val rm = RX_JORNADA.find(n)
            if (rm != null) roundRead = rm.groupValues[1].toIntOrNull()
            val units = Regex("(\\d+)\\s*([dhms])\\b").findAll(n).toList()
            if (units.size >= 2) {
                var secs = 0L
                for (u in units) {
                    val v = u.groupValues[1].toLongOrNull() ?: 0L
                    secs += when (u.groupValues[2]) {
                        "d" -> v * 86400L
                        "h" -> v * 3600L
                        "m" -> v * 60L
                        else -> v
                    }
                }
                val at = ((nowMs + secs * 1000L) / 60000L) * 60000L
                f[K.MATCH_AT] = Reading(at.toString(), 0.8)
            }
        }
        if (roundRead != null) f[K.ROUND] = Reading(roundRead.toString(), 0.9)

        o.tokens.firstOrNull { it.yc < 0.09f && it.xc in 0.10f..0.22f && Money.valid(it.text) }
            ?.let { f[K.CASH] = Reading(it.text.trim(), 0.85) }

        val teamLines = o.lines.filter {
            it.yc in 0.33f..0.395f && Txt.letters(it.text) >= 3 && !Txt.norm(it.text).contains("arbitro")
        }
        val leftTeam = teamLines.filter { it.xc < 0.5f }.maxByOrNull { it.h }
        val rightTeam = teamLines.filter { it.xc >= 0.5f }.maxByOrNull { it.h }
        val nickLines = o.lines.filter { it.yc in 0.395f..0.45f && Txt.letters(it.text) >= 2 }
        val myNick = nickLines.firstOrNull { Txt.sim(Txt.key(it.text), MY_NICK) >= 0.75 }

        var candidates = listOfNotNull(leftTeam?.text?.trim(), rightTeam?.text?.trim())
        if (myNick != null) {
            val mineLeft = myNick.xc < 0.5f
            val mine = if (mineLeft) leftTeam else rightTeam
            val rival = if (mineLeft) rightTeam else leftTeam
            if (mine != null) {
                f[K.TEAM] = Reading(mine.text.trim(), 0.9)
                candidates = listOf(mine.text.trim())
            }
            if (rival != null) f[K.RIVAL_TEAM] = Reading(rival.text.trim(), 0.9)
            f[K.HOME] = Reading(if (mineLeft) "Casa" else "Fora", 0.8)
            val rivalNick = nickLines.firstOrNull { it !== myNick && (it.xc < 0.5f) != mineLeft && isNick(it.text) }
            if (rivalNick != null) {
                f[K.RIVAL_HUMAN] = Reading("Sim", 0.85)
                f[K.RIVAL_NICK] = Reading(rivalNick.text.trim(), 0.8)
            } else if (rival != null) {
                f[K.RIVAL_HUMAN] = Reading("Não", 0.55)
            }
            var left: Int? = null
            var right: Int? = null
            for (t in o.tokens) {
                val n = intTok(t) ?: continue
                if (n !in 30..130 || t.yc !in 0.22f..0.34f) continue
                if (t.xc in 0.30f..0.38f) left = n
                if (t.xc in 0.62f..0.70f) right = n
            }
            val mineStr = if (mineLeft) left else right
            val rivalStr = if (mineLeft) right else left
            if (mineStr != null) f[K.MY_STRENGTH] = Reading(mineStr.toString(), 0.85)
            if (rivalStr != null) f[K.RIVAL_STRENGTH] = Reading(rivalStr.toString(), 0.8)

            // Classificação (tabela no canto inferior direito): linha do meu time.
            val myKey = Txt.key(f[K.TEAM]?.value ?: "")
            if (myKey.length >= 3) {
                val rowTok = o.tokens.firstOrNull {
                    it.xc in 0.70f..0.92f && it.yc > 0.78f && Txt.sim(Txt.key(it.text), myKey) >= 0.8
                } ?: o.lines.firstOrNull {
                    it.xc in 0.70f..0.92f && it.yc > 0.78f && Txt.sim(Txt.key(it.text), myKey) >= 0.8
                }?.let { OcrToken(it.text, it.l, it.t, it.r, it.b) }
                if (rowTok != null) {
                    val band = o.tokens.filter { abs(it.yc - rowTok.yc) <= 0.03f && it.xc >= 0.66f }
                    val pos = band.firstOrNull { it.xc < 0.72f && (intTok(it) ?: 0) in 1..30 }
                    val pts = band.lastOrNull { it.xc > 0.93f && intTok(it) != null }
                    if (pos != null) f[K.LEAGUE_POS] = Reading(pos.text.trim(), 0.8)
                    if (pts != null) f[K.POINTS] = Reading(pts.text.trim(), 0.8)
                }
            }
        }
        if (img != null) {
            PixelProbe.refereeSeverity(img)?.let { f[K.REFEREE] = Reading(it, 0.55) }
        }
        return Extraction(
            ScreenType.PREGAME,
            fields = f,
            teamCandidates = candidates,
            roundRead = roundRead
        )
    }

    // ---------------------------------------------------------------- ELENCO
    private val SECTION_WORDS = listOf("avancad", "medio", "defes", "guarda")

    fun squad(o: OcrResult, img: PixelProbe.Img?): Extraction {
        val f = LinkedHashMap<String, Reading>()

        val head = o.lines.filter { it.yc in 0.18f..0.30f && it.xc < 0.30f }.sortedBy { it.yc }
        val owner = head.firstOrNull { Txt.letters(it.text) >= 3 && !Txt.norm(it.text).startsWith("nota") }
        val nick = if (owner != null) head.firstOrNull { it !== owner && it.yc > owner.yc && Txt.letters(it.text) >= 3 } else null

        // Bolhas de força por setor + força geral.
        val labels = mapOf("gr" to "x.gol", "def" to "x.def", "med" to "x.mid", "ata" to "x.atk")
        for (t in o.tokens) {
            if (t.yc !in 0.20f..0.30f || t.xc !in 0.58f..0.80f) continue
            val key = labels[Txt.norm(t.text)] ?: continue
            val v = o.tokens.filter { intTok(it) != null && abs(it.xc - t.xc) < 0.03f && it.yc > t.yc && it.yc <= t.yc + 0.08f }
                .mapNotNull { intTok(it) }.firstOrNull { it in 30..130 }
            if (v != null) f[key] = Reading(v.toString(), 0.85)
        }
        val equipa = o.tokens.firstOrNull { Txt.norm(it.text) == "equipa" && it.yc in 0.24f..0.34f }
        if (equipa != null) {
            val v = o.tokens.filter { intTok(it) != null && abs(it.xc - equipa.xc) < 0.05f && it.yc > equipa.yc && it.yc <= equipa.yc + 0.12f }
                .mapNotNull { intTok(it) }.firstOrNull { it in 30..130 }
            if (v != null) f["x.strength"] = Reading(v.toString(), 0.9)
        }
        for (ln in o.lines) {
            if (ln.yc !in 0.12f..0.18f) continue
            val n = Txt.norm(ln.text)
            if (ln.xc in 0.55f..0.85f) {
                val fm = RX_FORMATION.find(ln.text)
                if (fm != null) {
                    val variant = fm.groupValues[2].uppercase()
                    f["x.formation"] = Reading(fm.groupValues[1] + if (variant.isNotEmpty()) " $variant" else "", 0.85)
                }
            }
            Regex("posicao:?\\s*(\\d{1,2})").find(n)?.let { f["x.pos"] = Reading(it.groupValues[1], 0.85) }
            Regex("objetivo:?\\s*(\\d{1,2})").find(n)?.let { f["x.objective"] = Reading(it.groupValues[1], 0.8) }
        }
        o.tokens.firstOrNull { it.xc > 0.88f && it.yc in 0.12f..0.18f && Money.valid(it.text) }
            ?.let { f["x.value"] = Reading(it.text.trim(), 0.85) }

        // Colunas (usa cabeçalho se visível; senão padrões medidos nos quadros reais).
        fun colX(word: String, def: Float): Float =
            o.tokens.firstOrNull { Txt.norm(it.text) == word && it.yc in 0.45f..0.60f }?.xc ?: def
        val ageX = colX("idade", 0.555f)
        val posX = colX("pos", 0.62f)
        val ataX = colX("ata", 0.663f)
        val defX = colX("def", 0.70f)
        val medX = colX("med", 0.737f)

        val sections = o.tokens.mapNotNull { t ->
            val n = Txt.norm(t.text)
            if (SECTION_WORDS.any { n.startsWith(it) } && t.xc in 0.30f..0.70f) Pair(t.yc, Pos.catFromSection(n)) else null
        }

        val players = ArrayList<PlayerRead>()
        val ageToks = o.tokens.filter { (intTok(it) ?: 0) in 15..45 && abs(it.xc - ageX) <= 0.035f && it.yc > 0.05f }
        for (a in ageToks) {
            val band = o.tokens.filter { abs(it.yc - a.yc) <= 0.04f }
            val name = band.filter { it.xc in 0.04f..0.34f && Txt.letters(it.text) >= 1 && intTok(it) == null }
                .sortedBy { it.l }.joinToString(" ") { it.text.trim() }.trim()
            if (Txt.letters(name) < 2) continue
            val posTok = band.firstOrNull { abs(it.xc - posX) <= 0.035f && it.text.trim().length in 1..4 && it.text.trim().all { c -> c.isLetter() } }
            fun col(x: Float): Int? = band.mapNotNull { if (abs(it.xc - x) <= 0.03f) intTok(it) else null }.firstOrNull { it in 1..130 }
            val sectionCat = sections.filter { it.first <= a.yc }.maxByOrNull { it.first }?.second
            val cat = Pos.cat(posTok?.text) ?: sectionCat
            val strength = when (cat) {
                "ATA" -> col(ataX)
                "MEI" -> col(medX)
                "DEF", "GOL" -> col(defX)
                else -> null
            }?.takeIf { it in 30..120 }
            val value = band.firstOrNull { it.xc > 0.88f && Money.valid(it.text) }?.text?.trim()
            if (strength == null && value == null) continue
            players.add(
                PlayerRead(
                    name = name,
                    age = intTok(a),
                    posCode = posTok?.text?.trim()?.uppercase(),
                    cat = cat,
                    strength = strength,
                    valueText = value,
                    training = img?.let { PixelProbe.orangeShirt(it, a.yc) },
                    forSale = null
                )
            )
        }
        val bad = players.count { it.strength == null || it.cat == null }
        val needsAi = players.size >= 3 && bad > players.size * 0.4
        return Extraction(
            ScreenType.SQUAD,
            fields = f,
            players = players,
            ownerTeam = owner?.text?.trim(),
            ownerNick = nick?.text?.trim(),
            needsAi = needsAi
        )
    }

    // ---------------------------------------------------------------- CALENDÁRIO
    data class Orient(val mine: Int, val opp: Int, val conf: Double)

    fun orient(a: Int, b: Int, badge: Char?, home: Boolean?): Orient? {
        fun ok(m: Int, o: Int): Boolean = when (badge) {
            'V' -> m > o
            'D' -> m < o
            'E' -> m == o
            else -> true
        }
        if (badge == null) {
            return when (home) {
                true -> Orient(a, b, 0.7)
                false -> Orient(b, a, 0.7)
                null -> null
            }
        }
        val okA = ok(a, b)
        val okB = ok(b, a)
        return when (home) {
            true -> if (okA) Orient(a, b, 0.9) else if (okB) Orient(b, a, 0.6) else null
            false -> if (okB) Orient(b, a, 0.9) else if (okA) Orient(a, b, 0.6) else null
            null -> when {
                okA && okB -> Orient(a, b, 0.9)
                okA -> Orient(a, b, 0.8)
                okB -> Orient(b, a, 0.8)
                else -> null
            }
        }
    }

    fun calendar(o: OcrResult, img: PixelProbe.Img?): Extraction {
        data class Anchor(val line: OcrLine, val round: Int?, val label: String)

        val anchors = ArrayList<Anchor>()
        for (ln in o.lines) {
            val n = Txt.norm(ln.text)
            val m = RX_JORNADA.find(n)
            if (m != null) {
                anchors.add(Anchor(ln, m.groupValues[1].toIntOrNull(), "Jornada " + m.groupValues[1]))
            } else if (n == "final" || n == "semifinal" || n == "semi final" || n.startsWith("quartas") || n.startsWith("oitavas")) {
                if (ln.yc > 0.5f) anchors.add(Anchor(ln, null, ln.text.trim()))
            }
        }
        val rowYs = ArrayList<Float>()
        for (a in anchors.sortedBy { it.line.yc }) {
            if (rowYs.isEmpty() || a.line.yc - rowYs.last() > 0.04f) rowYs.add(a.line.yc)
        }
        val matches = ArrayList<MatchRead>()
        for (a in anchors) {
            val nextRow = rowYs.firstOrNull { it > a.line.yc + 0.04f }
            val bottom = if (nextRow != null) nextRow - 0.03f else a.line.yc + 0.36f
            val card = o.lines.filter {
                it !== a.line && abs(it.xc - a.line.xc) <= 0.082f && it.yc > a.line.yc + 0.02f && it.yc < bottom
            }
            var date: String? = null
            var time: String? = null
            var score: Pair<Int, Int>? = null
            val alpha = ArrayList<OcrLine>()
            for (ln in card.sortedBy { it.yc }) {
                val t = ln.text.trim()
                val dm = RX_DATE.find(t)
                val tm = RX_TIME.find(t)
                val sm = RX_SCORE.find(t)
                when {
                    dm != null -> date = dm.groupValues[1] + "/" + dm.groupValues[2] + "/" + dm.groupValues[3]
                    tm != null -> time = t
                    sm != null -> score = Pair(sm.groupValues[1].toInt(), sm.groupValues[2].toInt())
                    Txt.letters(t) >= 2 -> alpha.add(ln)
                }
            }
            val opponent = alpha.getOrNull(0)?.text?.trim()
            val nick = alpha.getOrNull(1)?.text?.trim()
            if (score == null && opponent == null) continue

            val badge = img?.let { PixelProbe.resultBadge(it, a.line.xc, a.line.yc) }
            val home = img?.let { PixelProbe.homeIcon(it, a.line.xc, a.line.yc) }
            var mine: Int? = null
            var opp: Int? = null
            var finalHome = home
            var result: String? = null
            val sc = score
            if (sc != null) {
                val o2 = orient(sc.first, sc.second, badge, home)
                if (o2 != null) {
                    mine = o2.mine
                    opp = o2.opp
                    result = if (mine > opp) "V" else if (mine < opp) "D" else "E"
                    if (home != null && o2.conf < 0.7) finalHome = null
                }
            }
            val key = if (a.round != null) "L" + a.round else "C:" + Txt.key(a.label)
            matches.add(
                MatchRead(
                    key = key, label = a.label, round = a.round, date = date, time = time,
                    home = finalHome, scoreMine = mine, scoreOpp = opp, result = result,
                    opponent = opponent, opponentNick = nick
                )
            )
        }

        // Cabeçalho: de quem é este calendário.
        val ownerLine = o.lines.filter {
            it.xc in 0.35f..0.65f && it.yc in 0.45f..0.55f && Txt.letters(it.text) >= 3
        }.maxByOrNull { it.h }

        // Linha de times no topo (com apelido embaixo quando humano).
        val humans = LinkedHashMap<String, String?>()
        val topLines = o.lines.filter { it.yc in 0.24f..0.37f && Txt.letters(it.text) >= 2 }.sortedBy { it.xc }
        val used = HashSet<OcrLine>()
        for (ln in topLines) {
            if (ln in used) continue
            val group = topLines.filter { abs(it.xc - ln.xc) <= 0.06f }.sortedBy { it.yc }
            used.addAll(group)
            val nm = group[0].text.trim()
            val nk = group.getOrNull(1)?.text?.trim()
            if (Txt.key(nm).length >= 3) humans[Txt.key(nm)] = nk
        }
        return Extraction(
            ScreenType.CALENDAR,
            matches = matches,
            humans = humans,
            ownerTeam = ownerLine?.text?.trim()
        )
    }

    // ---------------------------------------------------------------- MERCADO
    fun market(o: OcrResult): Extraction {
        val f = LinkedHashMap<String, Reading>()
        val sm = RX_SELLING.find(Txt.norm(o.fullText))
        if (sm != null) f[K.SELLING] = Reading(sm.groupValues[1] + "/" + sm.groupValues[2], 0.9)

        val listings = ArrayList<ListingRead>()
        val ageToks = o.tokens.filter { (intTok(it) ?: 0) in 15..45 && it.xc in 0.49f..0.57f && it.yc > 0.18f }
        for (a in ageToks) {
            val band = o.tokens.filter { abs(it.yc - a.yc) <= 0.05f }
            val name = band.filter { it.xc < 0.30f && Txt.letters(it.text) >= 1 && intTok(it) == null && abs(it.yc - a.yc) <= 0.03f }
                .sortedBy { it.l }.joinToString(" ") { it.text.trim() }.trim()
            if (Txt.letters(name) < 2) continue
            val posTok = band.firstOrNull {
                it.xc in 0.57f..0.63f && it.text.trim().length in 1..4 && it.text.trim().all { c -> c.isLetter() }
            }
            val clubToks = band.filter { it !== posTok && it.xc in 0.615f..0.80f && it.l >= 0.605f }
            val club = clubToks.filter { it.yc <= a.yc + 0.012f }.sortedBy { it.l }.joinToString(" ") { it.text.trim() }.trim()
            val nick = clubToks.filter { it.yc > a.yc + 0.012f }.sortedBy { it.l }.joinToString(" ") { it.text.trim() }.trim()
            val nums = band.filter { it.xc in 0.79f..0.92f && intTok(it) != null }.sortedBy { it.xc }.mapNotNull { intTok(it) }
            val price = band.firstOrNull { it.xc > 0.925f && Money.valid(it.text) }?.text?.trim()
            if (price == null) continue
            val cat = Pos.cat(posTok?.text)
            // Ordem das colunas: Ata, Def, Med.
            val strength = if (nums.size >= 3) {
                when (cat) {
                    "ATA" -> nums[nums.size - 3]
                    "DEF", "GOL" -> nums[nums.size - 2]
                    "MEI" -> nums[nums.size - 1]
                    else -> null
                }
            } else null
            listings.add(
                ListingRead(
                    name = name, age = intTok(a), posCode = posTok?.text?.trim()?.uppercase(), cat = cat,
                    strength = strength?.takeIf { it in 30..120 }, priceText = price,
                    club = club.ifEmpty { null }, sellerNick = nick.ifEmpty { null }
                )
            )
        }
        return Extraction(ScreenType.MARKET, fields = f, listings = listings)
    }
}
