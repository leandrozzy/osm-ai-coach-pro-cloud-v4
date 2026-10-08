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
    // O texto do fundo do estádio ("25 ANNIVERSARY") gruda na data e some o \b: aceita a data colada.
    private val RX_DATE_LOOSE = Regex("(\\d{2})-(\\d{2})-(\\d{2})")
    private val RX_TIME = Regex("^(\\d{1,2}):(\\d{2})$")
    private val RX_SCORE = Regex("^(\\d{1,2})\\s*[-–—]\\s*(\\d{1,2})$")
    private val RX_FORMATION = Regex("([3-5]-\\d-\\d(?:-\\d)?)\\s*([A-Da-d])?")
    private val RX_BUBBLE = Regex("^(?:GR|GOL|G0L|DE[FI]|M[EÉé]D|MEI|ATA)?(\\d{2,3})(?:GR|GOL|DE[FI]|M[EÉé]D|MEI|ATA)?$", RegexOption.IGNORE_CASE)
    private val RX_BONUS = Regex("^\\+?(\\d{1,2})\\s*%$")
    // "+12" sem o "%" (separado pelo OCR) ou "+12%" com lixo do ícone em volta
    private val RX_BONUS_LOOSE = Regex("(?:^|[^0-9])\\+(\\d{1,2})(?:%|$)")
    private val RX_SELLING = Regex("vender jogadores\\s*(\\d)\\s*/\\s*(\\d)")

    private fun intTok(t: OcrToken): Int? {
        val s = t.text.trim()
        return if (s.isNotEmpty() && s.length <= 3 && s.all { it.isDigit() }) s.toIntOrNull() else null
    }

    private fun isNick(s: String): Boolean {
        val t = s.trim()
        return t.length in 3..24 && Txt.letters(t) >= 2 && !t.all { it.isDigit() || it == ' ' || it == '.' || it == ',' } &&
            Evidence.plausibleNick(t)
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

        o.tokens.filter { it.yc < 0.09f && it.xc in 0.10f..0.22f }.mapNotNull { Money.extract(it.text) }.firstOrNull()
            ?.let { f[K.CASH] = Reading(it, 0.85) }

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
            // Times humanos mostram o BÔNUS (+N%) no círculo; times de CPU mostram a força.
            var leftBonus: Int? = null
            var rightBonus: Int? = null
            // O OCR às vezes separa "+12" e "%" ou junta com o ícone: procura em tokens e linhas, numa área maior.
            val bonusCands = o.tokens.map { Triple(it.text, it.xc, it.yc) } + o.lines.map { Triple(it.text, it.xc, it.yc) }
            for ((txt, xc, yc) in bonusCands) {
                if (yc !in 0.17f..0.36f) continue
                val t = txt.trim().replace(" ", "")
                val m = RX_BONUS.find(t) ?: RX_BONUS_LOOSE.find(t) ?: continue
                val v = m.groupValues[1].toIntOrNull() ?: continue
                if (v !in 1..60) continue
                if (xc in 0.24f..0.42f && leftBonus == null) leftBonus = v
                if (xc in 0.58f..0.76f && rightBonus == null) rightBonus = v
            }
            val myBonus = if (mineLeft) leftBonus else rightBonus
            val rivalBonus = if (mineLeft) rightBonus else leftBonus
            if (myBonus != null) f[K.MY_BONUS] = Reading("+$myBonus%", 0.85)
            if (rivalBonus != null) f[K.RIVAL_LOGIN_BONUS] = Reading("+$rivalBonus%", 0.85)
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

        // O cabeçalho (time, apelido, força) só existe com a lista no topo. Rolando, nomes de jogadores
        // sobem para essa região: sem "Posição/Objetivo/Nota mais alta" visíveis, não há dono.
        val hasHeader = o.lines.any { ln ->
            val n = Txt.norm(ln.text)
            (ln.yc in 0.10f..0.20f && (n.contains("posicao") || n.contains("objetivo"))) || n.contains("nota mais alta")
        }
        val head = if (hasHeader) o.lines.filter { it.yc in 0.18f..0.30f && it.xc < 0.30f }.sortedBy { it.yc } else emptyList()
        val owner = head.firstOrNull { Txt.letters(it.text) >= 3 && !Txt.norm(it.text).startsWith("nota") }
        val nick = if (owner != null) head.firstOrNull { it !== owner && it.yc > owner.yc && Txt.letters(it.text) >= 3 } else null

        // Bolhas de força por setor (GOL, DEF, MEI, ATA): lidas pela POSIÇÃO, porque o rótulo pequeno o OCR erra.
        val bubbleX = listOf(0.631f, 0.676f, 0.721f, 0.766f)
        val bubbleKeys = listOf("x.gol", "x.def", "x.mid", "x.atk")
        // O número da bolha às vezes vem grudado no rótulo ("MED73", "73MED") ou como "7 3": aceita esses casos.
        val bubbles = ArrayList<Pair<Float, Int>>()
        for (t in o.tokens) {
            if (t.yc !in 0.22f..0.33f || t.xc !in 0.59f..0.81f) continue
            // dígitos confundidos pelo OCR dentro da bolha: S->5, O->0, l/I->1, B->8
            val raw = t.text.trim().replace(" ", "").let { r ->
                if (r.count { it.isDigit() } >= 1 && r.length <= 3) r.replace('S', '5').replace('O', '0').replace('o', '0')
                    .replace('l', '1').replace('I', '1').replace('B', '8') else r
            }
            val n = raw.toIntOrNull()?.takeIf { raw.length in 2..3 } ?: intTok(t) ?: RX_BUBBLE
                .find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (n in 30..130) bubbles.add(Pair(t.xc, n))
        }
        val byX = bubbles.distinctBy { (it.first * 100).toInt() }.sortedBy { it.first }
        if (byX.size == 4) {
            // as quatro bolhas lidas: a ordem da esquerda para a direita é GOL, DEF, MEI, ATA
            for (i in 0 until 4) f[bubbleKeys[i]] = Reading(byX[i].second.toString(), 0.85)
        } else {
            for ((x, n) in byX) {
                var bi = -1
                var bd = 1f
                for (i in bubbleX.indices) {
                    val dd = abs(x - bubbleX[i])
                    if (dd < bd) {
                        bd = dd
                        bi = i
                    }
                }
                if (bi >= 0 && bd <= 0.03f && !f.containsKey(bubbleKeys[bi])) f[bubbleKeys[bi]] = Reading(n.toString(), 0.85)
            }
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
        o.tokens.filter { it.xc > 0.85f && it.yc in 0.12f..0.18f }.mapNotNull { Money.extract(it.text) }.firstOrNull()
            ?.let { f["x.value"] = Reading(it, 0.85) }

        if (!hasHeader) f.clear()

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
            val value = band.filter { it.xc > 0.85f }.mapNotNull { Money.extract(it.text) }.firstOrNull()
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
                    forSale = null,
                    cond = img?.let { PixelProbe.barFill(it, 0.757f, 0.800f, a.yc) },
                    morale = img?.let { PixelProbe.barFill(it, 0.808f, 0.850f, a.yc) }
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
            var stage: String? = null
            val alpha = ArrayList<OcrLine>()
            for (ln in card.sortedBy { it.yc }) {
                val t = ln.text.trim()
                val dm = RX_DATE_LOOSE.find(t)
                val tm = RX_TIME.find(t)
                val sm = RX_SCORE.find(t)
                val junk = Txt.norm(t).let { it.contains("anniv") || it.startsWith("anive") || it.startsWith("annive") }
                when {
                    dm != null -> date = dm.groupValues[1] + "/" + dm.groupValues[2] + "/" + dm.groupValues[3]
                    tm != null -> time = t
                    sm != null -> score = Pair(sm.groupValues[1].toInt(), sm.groupValues[2].toInt())
                    junk -> {}
                    // jogo de copa: o card mostra a fase ("Meias finais") onde ficaria o rival
                    stage == null && Fixtures.isStage(t) -> stage = t
                    Txt.letters(t) >= 2 -> alpha.add(ln)
                }
            }
            val opponent = alpha.getOrNull(0)?.text?.trim()
            val nick = alpha.getOrNull(1)?.text?.trim()?.takeIf { isNick(it) }
            if (score == null && opponent == null && stage == null) continue

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
                    key = key, label = if (stage != null) "Copa • $stage" else a.label, round = a.round, date = date, time = time,
                    home = finalHome, scoreMine = mine, scoreOpp = opp, result = result,
                    opponent = opponent, opponentNick = nick
                )
            )
        }

        // O título (time dono do calendário) só existe com a lista no topo: é texto grande, e nenhum rótulo
        // "Jornada N" aparece acima dele. Rolando, nomes de adversários caem nessa região e NÃO são título.
        val titleCand = o.lines.filter {
            it.xc in 0.35f..0.65f && it.yc in 0.45f..0.55f && Txt.letters(it.text) >= 3 &&
                !Txt.norm(it.text).startsWith("jornada") && !RX_DATE.containsMatchIn(it.text) && it.h >= 0.035f
        }.maxByOrNull { it.h }
        val anchorYs = anchors.map { it.line.yc }
        val ownerLine = if (titleCand != null && anchorYs.none { it > titleCand.yc - 0.32f && it < titleCand.yc + 0.04f }) titleCand else null
        val hasStrip = ownerLine != null
        val topLines = o.lines.filter {
            it.yc in 0.24f..0.37f && Txt.letters(it.text) >= 2 && !Txt.norm(it.text).startsWith("jornada")
        }.sortedBy { it.xc }
        val humans = LinkedHashMap<String, String?>()
        val used = HashSet<OcrLine>()
        for (ln in (if (hasStrip) topLines else emptyList())) {
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

    private val STYLE_NAMES = listOf("Jogo de passe", "Jogar pelas alas", "Remate à vista", "Contra-ataque", "Bola longa")

    /** A nota do analista ("Pelo que pude ver, ...") aparece à esquerda nas duas telas da análise. */
    fun isAnalysis(o: OcrResult): Boolean {
        val t = Txt.norm(o.fullText)
        return t.contains("pelo que pude ver") || (t.contains("nivel do estadio") && t.contains("formacao")) ||
            (t.contains("tenho a certeza") && t.contains("estagio"))
    }

    private fun belowOf(o: OcrResult, ln: OcrLine): OcrLine? =
        o.lines.filter { it !== ln && it.yc > ln.yc && it.yc - ln.yc <= 0.1f && abs(it.xc - ln.xc) <= 0.1f }
            .minByOrNull { it.yc - ln.yc }

    /**
     * Análise do rival. Esquerda: nota do analista (entradas, formação, estágio, nível do estádio) e o apelido.
     * Direita: ora o campo ("Formação: 4-3-3 A" + suplentes), ora a tática (estilo no topo, Marcação, Fora-de-jogo).
     */
    fun report(o: OcrResult): Extraction {
        val f = LinkedHashMap<String, Reading>()
        val left = o.lines.filter { it.xc < 0.47f }.sortedBy { it.yc }
        val right = o.lines.filter { it.xc >= 0.47f }
        val leftRaw = left.joinToString(" ") { it.text.trim() }
        val leftNorm = Txt.norm(leftRaw)
        val noteSeen = leftNorm.contains("pelo que pude ver") || leftNorm.contains("nivel do estadio")

        val tk = Regex("entradas\\s+([a-z]+)").find(leftNorm)
        if (tk != null) {
            val w = tk.groupValues[1]
            val v = if (w.startsWith("agress")) "Agressivo" else if (w == "normal") "Normal" else w.replaceFirstChar { it.uppercase() }
            f[K.RIVAL_TACKLE] = Reading(v, 0.85)
        }
        val fm = RX_FORMATION.find(leftRaw)
        if (fm != null) {
            val variant = fm.groupValues[2].uppercase()
            f[K.RIVAL_FORMATION] = Reading(fm.groupValues[1] + (if (variant.isNotEmpty()) " $variant" else ""), 0.85)
        }
        if (leftNorm.contains("estagio")) {
            val no = Regex("nao\\s+(?:foram|estiveram|fizeram|estao)[^.]{0,25}estagio").containsMatchIn(leftNorm)
            f[K.RIVAL_CAMP] = Reading(if (no) "Não" else "Sim", 0.85)
        }
        val st = Regex("nivel do estadio\\s*:?\\s*(\\d{1,2})").find(leftNorm)
        if (st != null) f[K.STADIUM] = Reading("Nível " + st.groupValues[1], 0.9)
        if (leftNorm.contains("secreto")) {
            val no = Regex("nao[^.]{0,30}secreto").containsMatchIn(leftNorm)
            f[K.RIVAL_SECRET] = Reading(if (no) "Não" else "Sim", 0.8)
        } else if (noteSeen) {
            f[K.RIVAL_SECRET] = Reading("Não", 0.55)
        }
        // Apelido do usuário aparece como "Apelido [S3]" logo abaixo do nome do time (só times humanos).
        for (ln in left) {
            if (ln.yc >= 0.45f) continue
            val m = Regex("^(.{3,30}?)\\s*\\[[^\\]]{1,4}\\]\\s*$").find(ln.text.trim())
            if (m != null) {
                f[K.RIVAL_NICK] = Reading(m.groupValues[1].trim(), 0.85)
                f[K.RIVAL_HUMAN] = Reading("Sim", 0.85)
                break
            }
        }

        // Direita, tela do campo: "Formação: 4-3-3 A" (mais confiável que a frase da nota).
        val head = right.firstOrNull { Txt.norm(it.text).startsWith("formacao") }
        if (head != null) {
            val m = RX_FORMATION.find(head.text)
            if (m != null) {
                val variant = m.groupValues[2].uppercase()
                f[K.RIVAL_FORMATION] = Reading(m.groupValues[1] + (if (variant.isNotEmpty()) " $variant" else ""), 0.9)
            }
        }
        // Direita, tela da tática: estilo no topo, depois Marcação e Fora-de-jogo com o valor logo abaixo.
        for (ln in right.filter { it.yc < 0.14f }) {
            val n = Txt.norm(ln.text)
            val hit = STYLE_NAMES.firstOrNull { Txt.norm(it) == n || n.contains(Txt.norm(it)) }
                ?: Osm.style(n)?.takeIf { n.length <= 24 }
            if (hit != null) {
                f[K.RIVAL_PLAN] = Reading(hit, 0.9)
                break
            }
        }
        val mk = right.firstOrNull { Txt.norm(it.text) == "marcacao" }
        if (mk != null) {
            val v = belowOf(o, mk)?.let { Txt.norm(it.text) } ?: ""
            if (v.contains("zona")) f[K.RIVAL_MARKING] = Reading("À zona", 0.9)
            else if (v.contains("homem") || v.contains("individ")) f[K.RIVAL_MARKING] = Reading("Homem-a-homem", 0.9)
        }
        val off = right.firstOrNull { val n = Txt.norm(it.text); n.contains("fora-de-jogo") || n.contains("fora de jogo") }
        if (off != null) {
            val v = belowOf(o, off)?.let { Txt.norm(it.text) } ?: ""
            if (v == "sim") f[K.RIVAL_OFFSIDE] = Reading("Sim", 0.9)
            else if (v == "nao") f[K.RIVAL_OFFSIDE] = Reading("Não", 0.9)
        }
        return Extraction(ScreenType.REPORT, fields = f, needsAi = f.size < 2)
    }

    /** Meu estádio: o nível de cada card é o número de ESTRELAS douradas (o texto "Nível N" mostra o próximo nível). */
    fun stadium(o: OcrResult, img: PixelProbe.Img?): Extraction {
        val f = LinkedHashMap<String, Reading>()
        if (img == null) return Extraction(ScreenType.STADIUM, fields = f)
        val cards = listOf(
            Triple("relvado", K.MY_STAD_PITCH, "Relvado"),
            Triple("capacidade", K.MY_STAD_CAP, "Capacidade"),
            Triple("treino", K.MY_STAD_TRAIN, "Treino")
        )
        val parts = ArrayList<String>()
        for ((word, key, label) in cards) {
            val ln = o.lines.firstOrNull { Txt.norm(it.text) == word && it.yc in 0.20f..0.45f } ?: continue
            val lvl = PixelProbe.starLevel(img, ln.xc, ln.yc + 0.074f)
            if (lvl in 1..3) {
                f[key] = Reading(lvl.toString(), 0.85)
                parts.add("$label $lvl")
            }
        }
        if (parts.size >= 2) f[K.MY_STADIUM] = Reading(parts.joinToString(" • "), 0.85)
        // Situação de cada card (máximo / concluir / custo + tempo do próximo melhoramento).
        val status = ArrayList<String>()
        for ((word, _, label) in cards) {
            val ln = o.lines.firstOrNull { Txt.norm(it.text) == word && it.yc in 0.20f..0.45f } ?: continue
            val region = o.lines.filter { abs(it.xc - ln.xc) <= 0.14f && it.yc in 0.60f..0.95f }
            val text = Txt.norm(region.joinToString(" ") { it.text })
            val st = if (text.contains("maximo")) "máximo"
            else if (text.contains("concluir")) "concluir melhoria"
            else {
                val cost = Regex("(\\d{1,3}(?:[.,]\\d)?\\s*[km])\\b").find(text)?.groupValues?.get(1)?.replace(" ", "")?.uppercase()
                val time = Regex("(\\d{1,3})\\s*h\\b").find(text)?.groupValues?.get(1)
                if (cost != null) cost + (if (time != null) " + ${time}h" else "") else null
            }
            if (st != null) status.add("$label: $st")
        }
        if (status.isNotEmpty()) f[K.MY_STAD_STATUS] = Reading(status.joinToString(" • "), 0.8)
        return Extraction(ScreenType.STADIUM, fields = f)
    }

    // ------------------------------------------------------------------ resultado do jogo

    /** Tela de análise do jogo: placar, eventos, "Homem do jogo", estatísticas, zonas de ação e notas. */
    fun isMatchResult(o: OcrResult): Boolean {
        val t = Txt.norm(o.fullText)
        if (t.contains("estatisticas do jogo") || t.contains("homem do jogo") || t.contains("zonas de acao")) return true
        if (t.contains("primeira parte") && t.contains("segunda parte")) return true
        if (t.contains("jornada")) return false
        val l = o.tokens.count { val n = intTok(it); n != null && n in 1..10 && it.xc in 0.44f..0.49f && it.yc in 0.10f..0.97f }
        val r = o.tokens.count { val n = intTok(it); n != null && n in 1..10 && it.xc in 0.50f..0.56f && it.yc in 0.10f..0.97f }
        return l >= 5 && r >= 5
    }

    private val RX_MINUTE = Regex("^(\\d{1,3})\\s*['’′]$")
    private val RX_SHIRT = Regex("^\\d{1,2}\\.\\s*")

    fun matchResult(o: OcrResult): Extraction {
        var round: Int? = null
        for (ln in o.lines) {
            if (ln.yc < 0.2f && ln.xc in 0.4f..0.6f) {
                val m = RX_JORNADA.find(Txt.norm(ln.text))
                if (m != null) round = m.groupValues[1].toIntOrNull()
            }
        }
        var home: String? = null
        var away: String? = null
        var homeNick: String? = null
        var awayNick: String? = null
        var sh: Int? = null
        var sa: Int? = null
        var referee: String? = null
        var tip: String? = null
        var advice: String? = null
        if (round != null) {
            val names = o.lines.filter { it.yc in 0.19f..0.28f && Txt.letters(it.text) >= 3 }
            home = names.filter { it.xc < 0.35f }.maxByOrNull { it.h }?.text?.trim()
            away = names.filter { it.xc > 0.65f }.maxByOrNull { it.h }?.text?.trim()
            val nicks = o.lines.filter { it.yc in 0.265f..0.33f && Txt.letters(it.text) >= 2 }
            homeNick = nicks.firstOrNull { it.xc < 0.35f }?.text?.trim()
            awayNick = nicks.firstOrNull { it.xc > 0.65f }?.text?.trim()
            val sl = o.lines.firstOrNull { it.xc in 0.4f..0.6f && it.yc in 0.18f..0.34f && RX_SCORE.find(it.text.trim()) != null }
            if (sl != null) {
                val m = RX_SCORE.find(sl.text.trim())
                if (m != null) {
                    sh = m.groupValues[1].toIntOrNull()
                    sa = m.groupValues[2].toIntOrNull()
                }
            } else {
                val digits = o.tokens.filter { val n = intTok(it); n != null && n in 0..15 && it.yc in 0.19f..0.33f && it.xc in 0.40f..0.60f }
                val dl = digits.filter { it.xc < 0.5f }.maxByOrNull { it.xc }
                val dr = digits.filter { it.xc >= 0.5f }.minByOrNull { it.xc }
                if (dl != null && dr != null) {
                    sh = intTok(dl)
                    sa = intTok(dr)
                }
            }
            referee = o.lines.firstOrNull { it.xc > 0.7f && it.yc in 0.34f..0.46f && Txt.letters(it.text) >= 5 }?.text?.trim()
            tip = o.lines.filter { it.yc in 0.49f..0.60f && it.xc in 0.05f..0.8f && Txt.letters(it.text) >= 12 }
                .sortedBy { it.xc }.joinToString(" ") { it.text.trim() }.ifBlank { null }
            advice = o.lines.filter { it.yc in 0.64f..0.76f && it.xc in 0.05f..0.8f && Txt.letters(it.text) >= 12 }
                .sortedBy { it.xc }.joinToString(" ") { it.text.trim() }.ifBlank { null }
        }

        // Estatísticas: rótulo no centro, valor do time da casa à esquerda e do visitante à direita.
        val stats = LinkedHashMap<String, Pair<String, String>>()
        val labels = listOf("golos", "remates", "precisao", "cantos", "faltas", "cartoes", "formacao", "posse de bola")
        for (ln in o.lines) {
            if (ln.xc !in 0.42f..0.58f) continue
            val lab = labels.firstOrNull { it == Txt.norm(ln.text) } ?: continue
            if (lab == "cartoes") {
                val lt = o.tokens.filter { intTok(it) != null && it.xc < 0.15f && abs(it.yc - ln.yc) <= 0.04f }.sortedBy { it.xc }.mapNotNull { intTok(it) }
                val rt = o.tokens.filter { intTok(it) != null && it.xc > 0.85f && abs(it.yc - ln.yc) <= 0.04f }.sortedBy { it.xc }.mapNotNull { intTok(it) }
                if (lt.size >= 2 && rt.size >= 2) stats[lab] = Pair("${lt[0]},${lt[1]}", "${rt[1]},${rt[0]}")
            } else {
                val lv = o.lines.filter { it !== ln && it.xc < 0.15f && abs(it.yc - ln.yc) <= 0.035f }.minByOrNull { it.xc }?.text?.trim()
                val rv = o.lines.filter { it !== ln && it.xc > 0.85f && abs(it.yc - ln.yc) <= 0.035f }.maxByOrNull { it.xc }?.text?.trim()
                if (lv != null && rv != null) stats[lab] = Pair(lv, rv)
            }
        }
        val zones = o.lines.filter { it.yc in 0.72f..0.88f && it.xc in 0.25f..0.75f && it.text.contains("%") }
            .sortedBy { it.xc }.mapNotNull { Regex("(\\d{1,3})\\s*%").find(it.text)?.groupValues?.get(1)?.toIntOrNull() }
        val momHeader = o.lines.firstOrNull { Txt.norm(it.text) == "homem do jogo" }
        val mom = if (momHeader == null) null else o.lines.filter {
            it.yc > momHeader.yc && it.yc - momHeader.yc <= 0.16f && Txt.letters(it.text) >= 3
        }.minByOrNull { it.yc }?.text?.trim()

        // Eventos (minuto no centro; jogador e descrição do lado do time).
        val events = ArrayList<String>()
        for (mn in o.lines) {
            val mm = RX_MINUTE.find(mn.text.trim()) ?: continue
            if (mn.xc !in 0.42f..0.58f) continue
            val band = o.lines.filter { it !== mn && abs(it.yc - mn.yc) <= 0.07f && it.xc !in 0.42f..0.58f && Txt.letters(it.text) >= 2 }.sortedBy { it.yc }
            if (band.isEmpty()) continue
            val side = if (band[0].xc < 0.5f) "casa" else "visitante"
            events.add("${mm.groupValues[1]}' ($side) " + band.joinToString(" — ") { it.text.trim().replace(RX_SHIRT, "") })
        }

        // Notas dos jogadores: bolinhas à esquerda (time da casa) e à direita (visitante).
        val ratingsHome = ArrayList<Pair<String, Int>>()
        val ratingsAway = ArrayList<Pair<String, Int>>()
        val lr = o.tokens.filter { val n = intTok(it); n != null && n in 1..10 && it.xc in 0.44f..0.49f && it.yc in 0.10f..0.97f }
        for (t in lr) {
            val nm = o.lines.filter { it.xc < 0.35f && abs(it.yc - t.yc) <= 0.03f && Txt.letters(it.text) >= 3 }.minByOrNull { abs(it.yc - t.yc) }?.text?.trim()
            if (nm != null) ratingsHome.add(Pair(nm.replace(RX_SHIRT, ""), intTok(t) ?: 0))
        }
        val rr = o.tokens.filter { val n = intTok(it); n != null && n in 1..10 && it.xc in 0.50f..0.56f && it.yc in 0.10f..0.97f }
        for (t in rr) {
            val nm = o.lines.filter { it.xc > 0.65f && abs(it.yc - t.yc) <= 0.03f && Txt.letters(it.text) >= 3 }.minByOrNull { abs(it.yc - t.yc) }?.text?.trim()
            if (nm != null) ratingsAway.add(Pair(nm.replace(RX_SHIRT, ""), intTok(t) ?: 0))
        }

        val rep = MatchReportRead(
            round = round, homeTeam = home, awayTeam = away, homeNick = homeNick, awayNick = awayNick,
            scoreHome = sh, scoreAway = sa, referee = referee, tip = tip, advice = advice, mom = mom,
            stats = stats, zones = if (zones.size == 3) zones else emptyList(),
            ratingsHome = if (ratingsHome.size >= 3) ratingsHome else emptyList(),
            ratingsAway = if (ratingsAway.size >= 3) ratingsAway else emptyList(),
            events = events
        )
        return Extraction(ScreenType.RESULT, matchReport = rep)
    }

    /** Tela com cara de relatório/análise do adversário (será lida pela IA, que confirma se é mesmo). */
    fun hasReportHint(o: OcrResult): Boolean {
        val t = Txt.norm(o.fullText)
        if (t.contains("relatorio do analista") && !isAnalysis(o)) return false
        val words = listOf("formacao", "marcacao", "desarme", "fora de jogo", "impedimento", "estilo de jogo", "estadio", "estagio", "secreto", "relatorio")
        return words.count { t.contains(it) } >= 2
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
            val price = band.filter { it.xc > 0.90f }.mapNotNull { Money.extract(it.text) }.firstOrNull()
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
