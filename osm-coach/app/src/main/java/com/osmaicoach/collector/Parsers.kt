package com.osmaicoach.collector

import kotlin.math.abs
import org.json.JSONObject

/**
 * Parsers determinísticos baseados em posição (colunas/linhas) das caixas do OCR.
 * Regra de ouro: o que não for lido com segurança fica ausente (NI) — nunca é chutado.
 */
object Parsers {
    private val RX_HUB_ROUND = Regex("(\\d{1,2})\\s*/\\s*(\\d{1,2})\\s*jornada")
    // "Jornada 7", "Jornada 7 de 10", "Ronda 7" (batalha) — o número logo depois da palavra.
    private val RX_JORNADA = Regex("^(?:jornada|ronda|rodada)\\s*(\\d{1,2})\\b")
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
        // A grade dos 4 cards muda de lugar na tela (o painel do perfil rola/anima): mede a posição pelo título
        // "ESPAÇOS DE TREINADOR" (ou pelo número "1" do primeiro card). Antes as faixas eram fixas e, com a tela
        // deslocada, o card do slot 2 era lido como slot 1 — o que chegou a apagar dados dos slots.
        val label = o.lines.firstOrNull { Txt.norm(it.text).contains("espacos de treinador") }
        val one = o.lines.firstOrNull { it.text.trim() == "1" && it.yc < 0.3f }
        val anchored = label != null || one != null
        val dx = label?.let { it.xc - 0.52f } ?: one?.let { it.xc - 0.52f } ?: 0f
        val dy = label?.let { it.yc - 0.03f } ?: one?.let { it.yc - 0.12f } ?: 0f
        for (slot in 1..4) {
            val col = if (slot % 2 == 1) 0 else 1
            val row = if (slot <= 2) 0 else 1
            val x0 = (if (col == 0) 0.47f else 0.69f) + dx
            val x1 = (if (col == 0) 0.69f else 0.90f) + dx
            val y0 = (if (row == 0) 0.04f else 0.50f) + dy
            val y1 = (if (row == 0) 0.495f else 0.97f) + dy
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
            // card sem competição: "+ COMEÇAR NOVA LIGA" (slot livre) ou "BATALHA … A PROCURAR ADVERSÁRIO" (esperando começar)
            val all = Txt.norm(rest.joinToString(" ") { it.text })
            // sem a referência da grade, só vale card com "x/y JORNADA" (nada de estado nem título solto)
            if (!anchored && done == null) continue
            if (done == null && (all.contains("comecar nova liga") || all.contains("nova liga") && all.contains("comecar"))) {
                cards.add(HubCard(slot, "", "", null, null, "vazio"))
                continue
            }
            // competição nova que ainda não começou: "PRÉ-ÉPOCA" no lugar de "x/y JORNADA"
            if (done == null && Regex("pre\\W?epoca|pretemporada|pre-temporada").containsMatchIn(all)) {
                val nm = names.filter { !Regex("epoca|temporada").containsMatchIn(Txt.norm(it.text)) }
                if (nm.isNotEmpty()) {
                    cards.add(HubCard(slot, nm[0].text.trim(), nm.getOrNull(1)?.text?.trim() ?: "", null, null, "preepoca"))
                    continue
                }
            }
            if (done == null && all.contains("procurar advers")) {
                val title = names.firstOrNull { !Txt.norm(it.text).contains("procurar") }?.text?.trim() ?: "Batalha"
                cards.add(HubCard(slot, "", title, null, null, "procurando"))
                continue
            }
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
            // contagem do pré-jogo: "21h 5m 10s", ou uma unidade só perto do jogo ("45m", "2d")
            val lone = units.size == 1 && Regex("^\\d{1,3}\\s*[dhm]$").matches(n.trim())
            if (units.size >= 2 || lone) {
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
        val nickLines = o.lines.filter { it.yc in 0.39f..0.47f && Txt.letters(it.text) >= 2 }
        val myNick = nickLines.firstOrNull { Txt.sim(Txt.key(it.text), MY_NICK) >= 0.75 }
        // Apelido sob cada time (mesmo sem achar o meu): o Repo casa com o rival guardado.
        val sideNicks = LinkedHashMap<String, String?>()
        for ((teamOrNull, left) in listOf(Pair(leftTeam, true), Pair(rightTeam, false))) {
            val team = teamOrNull ?: continue
            val tk = Txt.key(team.text).takeIf { it.length >= 3 } ?: continue
            val nk = nickLines.filter { (it.xc < 0.5f) == left && it !== myNick && isNick(it.text) }
                .minByOrNull { abs(it.xc - team.xc) }?.text?.trim()
            if (nk != null && Txt.sim(Txt.key(nk), MY_NICK) < 0.75) sideNicks[tk] = nk
        }

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
            }
            // Sem apelido legível NÃO marca CPU: o OCR pode só ter perdido o nome do usuário.
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
                if (yc !in 0.12f..0.42f) continue
                val t = txt.trim().replace(" ", "")
                val m = RX_BONUS.find(t) ?: RX_BONUS_LOOSE.find(t) ?: continue
                val v = m.groupValues[1].toIntOrNull() ?: continue
                // +0% também é leitura válida (humano sem bônus de login hoje)
                if (v !in 0..60) continue
                // fora da área do círculo só vale com o "+" (evita pegar outra porcentagem da tela)
                val inCircle = yc in 0.17f..0.36f && (xc in 0.24f..0.42f || xc in 0.58f..0.76f)
                if (!inCircle && !t.contains("+")) continue
                if (xc in 0.18f..0.46f && leftBonus == null) leftBonus = v
                if (xc in 0.54f..0.82f && rightBonus == null) rightBonus = v
            }
            val myBonus = if (mineLeft) leftBonus else rightBonus
            val rivalBonus = if (mineLeft) rightBonus else leftBonus
            if (myBonus != null) f[K.MY_BONUS] = Reading("+$myBonus%", 0.85)
            if (rivalBonus != null) f[K.RIVAL_LOGIN_BONUS] = Reading("+$rivalBonus%", 0.85)
            val mineStr = if (mineLeft) left else right
            val rivalStr = if (mineLeft) right else left
            if (mineStr != null) f[K.MY_STRENGTH] = Reading(mineStr.toString(), 0.85)
            if (rivalStr != null) f[K.RIVAL_STRENGTH] = Reading(rivalStr.toString(), 0.8)
            // Os círculos alternam força <-> bônus ao mesmo tempo. Se o meu mostra o bônus e o do rival continua
            // com a força (e não há usuário sob o nome dele), o rival é CPU: time de CPU não tem bônus de login.
            if (myBonus != null && rivalBonus == null && rivalStr != null && rivalNick == null) {
                f[K.RIVAL_HUMAN] = Reading("Não", 0.95)
            }
            // Humano (nome de usuário embaixo) sem bônus de login hoje: o círculo dele fica na força -> bônus 0%.
            if (myBonus != null && rivalBonus == null && rivalStr != null && rivalNick != null) {
                f[K.RIVAL_LOGIN_BONUS] = Reading("+0%", 0.8)
            }

            // Cadeado no círculo do rival = TREINO SECRETO: força, bônus e relatório do analista ficam escondidos.
            // Só humano faz treino secreto.
            if (img != null && rivalStr == null && rivalBonus == null && PixelProbe.lockedCircle(img, !mineLeft)) {
                f[K.RIVAL_SECRET] = Reading("Sim", 0.9)
                f[K.RIVAL_HUMAN] = Reading("Sim", 0.9)
            } else if (rivalStr != null || rivalBonus != null) {
                // número (força ou bônus) no círculo do rival: neste jogo não há cadeado
                f[K.RIVAL_SECRET] = Reading("Não", 0.9)
            }

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
            // termômetro ancorado no texto "Árbitro" quando o OCR o acha (a posição muda um pouco entre telas)
            val label = o.lines.firstOrNull { Txt.norm(it.text).startsWith("arbitro") && it.xc in 0.40f..0.65f }
            PixelProbe.refereeSeverity(img, label?.l, label?.yc)?.let { f[K.REFEREE] = Reading(it, 0.8) }
        }
        return Extraction(
            ScreenType.PREGAME,
            fields = f,
            teamCandidates = candidates,
            roundRead = roundRead,
            humans = sideNicks
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
            val rawName = band.filter { it.xc in 0.04f..0.34f && Txt.letters(it.text) >= 1 && intTok(it) == null }
                .sortedBy { it.l }.joinToString(" ") { it.text.trim() }.trim()
            val name = PlayerName.clean(rawName) ?: continue
            val posTok = band.firstOrNull { abs(it.xc - posX) <= 0.035f && it.text.trim().length in 1..4 && it.text.trim().all { c -> c.isLetter() } }
            fun col(x: Float): Int? = band.mapNotNull { if (abs(it.xc - x) <= 0.03f) intTok(it) else null }.firstOrNull { it in 1..130 }
            val sectionCat = sections.filter { it.first <= a.yc }.maxByOrNull { it.first }?.second
            val cat = Pos.resolve(Pos.cat(posTok?.text), sectionCat, Pos.catFromStats(col(ataX), col(defX), col(medX)))
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
                    // a camisa no plantel NÃO diz quem treina: isso vem só da tela de Treinamento
                    training = null,
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
                // resto do "25 ANNIVERSARY" do fundo do estádio ("LRSARY", "SARY", "ANNYEANS"): não é o rival
                val junk = Txt.norm(t).let {
                    it.contains("anniv") || it.startsWith("anive") || it.startsWith("annive") || it.endsWith("sary") ||
                        it.contains("versar") || it.contains("annyea")
                }
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
            // apelido = 2ª linha do card, mas nunca o próprio nome do time repetido nem rótulo ("ED:")
            val nick = alpha.getOrNull(1)?.text?.trim()?.takeIf { n ->
                isNick(n) && !n.contains(':') && (opponent == null || Txt.sim(Txt.key(n), Txt.key(opponent)) < 0.8)
            }
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
    /**
     * Confere a tela de tática do OSM (a que você está montando) com a tática gerada. Conservador: só reclama
     * de um item quando a tela mostra UM valor daquele tipo (lista aberta com todas as opções não conta).
     */
    fun tacticCheck(o: OcrResult, plan: JSONObject, referee: String?): List<String> {
        val out = ArrayList<String>()
        val texts = o.lines.map { it.text.trim() }
        fun only(options: List<String>): String? {
            val seen = options.filter { opt -> texts.any { Txt.norm(it) == Txt.norm(opt) } }
            return seen.singleOrNull()
        }
        val forms = texts.mapNotNull { t -> RX_FORMATION.find(t)?.let { m ->
            val v = m.groupValues[2].uppercase()
            Formations.canonical(m.groupValues[1] + if (v.isNotEmpty()) " $v" else "")
        } }.distinct()
        val want = Formations.canonical(plan.optString("formation"))
        if (forms.size == 1 && want != null && forms[0] != want) out.add("Formação ${forms[0]} — a gerada é $want")
        val st = only(Osm.STYLES)
        val wantSt = Osm.style(plan.optString("playStyle"))
        if (st != null && wantSt != null && st != wantSt) out.add("Estilo $st — a gerada é $wantSt")
        val tk = only(Osm.TACKLES)
        val wantTk = Osm.tackle(plan.optString("tackle"))
        if (tk != null) {
            if (tk !in Osm.allowedTackles(referee)) out.add("Desarme $tk com árbitro ${referee ?: "não lido"}: risco de expulsão")
            else if (wantTk != null && tk != wantTk) out.add("Desarme $tk — a gerada é $wantTk")
        }
        val mk = only(Osm.MARKING)
        val wantMk = Osm.marking(plan.optString("marking"))
        if (mk != null && wantMk != null && mk != wantMk) out.add("Marcação $mk — a gerada é $wantMk")
        return out
    }

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
            val stars = PixelProbe.starLevel(img, ln.xc, ln.yc + 0.074f)
            // Clube novo (batalha) começa no nível 0: sem estrela nenhuma. Antes o nível 0 era descartado e o estádio
            // do slot nunca era lido. Confirma pelo número da esquerda da barra (nível atual → próximo) ou pelo
            // "Nível N" do próximo melhoramento.
            val left = o.lines.firstOrNull {
                it.yc in 0.70f..0.78f && it.xc in (ln.xc - 0.14f)..(ln.xc - 0.04f) && it.text.trim().matches(Regex("\\d"))
            }?.text?.trim()?.toIntOrNull()
            val next = o.lines.firstOrNull { abs(it.xc - ln.xc) <= 0.12f && it.yc in 0.55f..0.70f }
                ?.let { Regex("n[iı]vel\\s*(\\d)").find(Txt.norm(it.text))?.groupValues?.get(1)?.toIntOrNull() }
            val lvl = when {
                stars in 1..3 -> stars
                left != null && left in 0..5 -> left
                next != null -> next - 1
                else -> null
            }
            if (lvl != null && lvl in 0..5) {
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
        // Topo do relatório (Jornada N, placar no centro, botão Rever, dicas): é aqui que estão os times e a
        // rodada que dizem de qual slot é o resultado. Antes era descartado por conter "Jornada".
        val scoreCenter = o.lines.any { it.xc in 0.40f..0.60f && it.yc in 0.12f..0.40f && RX_SCORE.find(it.text.trim()) != null }
        if (scoreCenter && (t.contains("rever") || t.contains("primeira parte") || t.contains("segunda parte"))) return true
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
            it.yc > momHeader.yc && it.yc - momHeader.yc <= 0.16f && Txt.letters(it.text) >= 3 && !Overlay.isChip(it.text)
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
        // Meu time é o do lado do meu apelido: é ele que diz de qual slot é o relatório (vindo direto da central).
        val mine = when {
            homeNick != null && Txt.sim(Txt.key(homeNick), MY_NICK) >= 0.75 -> home
            awayNick != null && Txt.sim(Txt.key(awayNick), MY_NICK) >= 0.75 -> away
            else -> null
        }
        return Extraction(ScreenType.RESULT, matchReport = rep, teamCandidates = listOfNotNull(home, away), ownerTeam = mine)
    }

    /** Tela com cara de relatório/análise do adversário (será lida pela IA, que confirma se é mesmo). */
    fun hasReportHint(o: OcrResult): Boolean {
        val t = Txt.norm(o.fullText)
        if (t.contains("relatorio do analista") && !isAnalysis(o)) return false
        val words = listOf("formacao", "marcacao", "desarme", "fora de jogo", "impedimento", "estilo de jogo", "estadio", "estagio", "secreto", "relatorio")
        return words.count { t.contains(it) } >= 2
    }

    // ---------------------------------------------------------------- TREINAMENTO

    private val TRAINER_ROLES = listOf(
        "universal" to "UNI", "avancados" to "ATA", "medios" to "MEI", "defesas" to "DEF", "guarda-redes" to "GOL", "guarda redes" to "GOL"
    )
    private val RARITY = setOf("epico", "lendario", "raro", "elite", "comum")
    private val RX_TIMER = Regex("\\d{1,2}\\s*h\\s*\\d{1,2}\\s*m")

    /**
     * Tela de Treinamento: uma coluna por treinador (universal, avançados, médios, defesas, guarda-redes). O estado vem
     * do que a coluna mostra: cronômetro/"Concluir" = treinando agora; "Treino concluído" = terminou (falta
     * reclamar); "Seleciona" = livre. Quadros com a animação da recompensa (Épico/Lendário...) são ignorados, e
     * coluna sem estado claro fica de fora. É esta tela, não a camisa no plantel, que diz quem está treinando.
     */
    fun training(o: OcrResult): List<TraineeRead> {
        fun n(t: String) = Txt.norm(t)
        if (o.lines.any { n(it.text) in RARITY }) return emptyList()
        val heads = o.lines.mapNotNull { ln ->
            val t = n(ln.text)
            if (!t.startsWith("treinador")) null else TRAINER_ROLES.firstOrNull { t.contains(it.first) }?.let { Pair(ln, it.second) }
        }.distinctBy { it.second }
        val out = ArrayList<TraineeRead>()
        for ((h, role) in heads) {
            val col = o.lines.filter { it !== h && abs(it.xc - h.xc) <= 0.055f && it.yc > h.yc && it.yc - h.yc <= 0.72f }
            val texts = col.map { n(it.text) }
            val nameLn = col.filter { ln ->
                val t = n(ln.text)
                ln.yc - h.yc in 0.12f..0.26f && Txt.letters(ln.text) >= 3 && !t.contains("idade") && !t.contains("dade:") &&
                    !t.contains("anniv") && !t.contains("nivers") &&
                    !t.startsWith("nivel") && !RX_TIMER.containsMatchIn(t)
            }.minByOrNull { it.yc }
            // a tela escreve "19 BUKAYO SAKA" em maiúsculas; nome em letra normal é de outra janela por cima
            val name = nameLn?.text?.trim()?.takeIf { TrainBoard.plausible(it) }?.replace(Regex("^\\d+\\s*"), "")?.takeIf { Txt.letters(it) >= 3 }
            val timer = texts.any { RX_TIMER.containsMatchIn(it) || it == "concluir" }
            val done = texts.any { it.contains("treino conclu") || it.contains("treino concu") }
            // "Seleciona" = livre; universal bloqueado ("Desbloquear treinador") também não tem ninguém treinando
            val empty = texts.any { it.startsWith("seleciona") || it.contains("desbloquear") }
            val state = when {
                timer && name != null -> "active"
                done && name != null -> "done"
                empty && name == null -> "empty"
                else -> null
            } ?: continue
            out.add(TraineeRead(role, name, state))
        }
        return out
    }

    // ---------------------------------------------------------------- FIM DA COMPETIÇÃO

    data class SeasonRead(
        val rank: Int?, val points: Int?, val goals: String?, val champion: String?, val board: String?,
        val squadValue: String?, val valueRank: Int?, val over: Boolean
    )

    private val SEASON_UI = listOf(
        "lista de treinadores", "melhores marcadores", "os que mais contribuem", "jogadores melhores", "valor do plantel",
        "esta liga termina", "vinga-te", "valor medio", "missoes", "nova epoca", "campeao da liga", "melhor marcador", "classifica"
    )

    /**
     * Resumo da época, classificação final e valor do plantel. Lê só o que dá para ancorar: a linha do MEU time
     * (pelo nome) e as colunas pelos cabeçalhos ("Pts", "Gm-Gs", "Valor"); a mensagem da direção é a frase longa
     * com "!"/"?"; o campeão é o nome logo abaixo de "Campeão da liga".
     */
    fun seasonSummary(o: OcrResult, myTeam: String?): SeasonRead {
        fun n(t: String) = Txt.norm(t)
        val board = o.lines.filter { ln ->
            val t = n(ln.text)
            Txt.letters(ln.text) >= 15 && (ln.text.contains('!') || ln.text.contains('?') || t.contains("epoca")) &&
                SEASON_UI.none { t.contains(it) }
        }.sortedBy { it.yc }.joinToString(" ") { it.text.trim() }.ifBlank { null }
        val champHead = o.lines.firstOrNull { n(it.text).contains("campeao da liga") }
        val champion = champHead?.let { h ->
            o.lines.filter { it !== h && abs(it.xc - h.xc) <= 0.05f && it.yc > h.yc && it.yc - h.yc <= 0.35f && Txt.letters(it.text) >= 3 }
                .minByOrNull { it.yc }?.text?.trim()
                // "COLO-COL0": zero no meio de letras é a letra O
                ?.replace(Regex("(?<=[A-Za-z])0|0(?=[A-Za-z])"), "O")
        }
        var rank: Int? = null
        var points: Int? = null
        var goals: String? = null
        var value: String? = null
        var valueRank: Int? = null
        val key = myTeam?.let { Txt.key(it) }?.takeIf { it.length >= 4 }
        val mine = if (key == null) null else o.lines.filter { Txt.sim(Txt.key(it.text), key) >= 0.85 }.minByOrNull { it.xc }
        if (mine != null) {
            // o número da posição fica entre a linha do time e a do apelido, na coluna da esquerda
            val rowY = mine.yc + 0.02f
            fun near(t: OcrToken, x: Float) = abs(t.xc - x) <= 0.025f && abs(t.yc - rowY) <= 0.045f
            val rankTok = o.tokens.filter { it.xc < 0.06f && abs(it.yc - rowY) <= 0.045f && intTok(it) != null }.minByOrNull { abs(it.yc - rowY) }
            // cabeçalho de coluna é uma linha sozinha ("Valor"), não a palavra dentro de "Valor do plantel"
            val ptsHead = o.lines.firstOrNull { n(it.text) == "pts" }
            val gmHead = o.lines.firstOrNull { n(it.text).replace(" ", "") == "gm-gs" }
            val valHead = o.lines.firstOrNull { n(it.text) == "valor" }
            if (ptsHead != null) {
                rank = rankTok?.let { intTok(it) }?.takeIf { it in 1..40 }
                points = o.tokens.filter { near(it, ptsHead.xc) && intTok(it) != null }.minByOrNull { abs(it.yc - rowY) }?.let { intTok(it) }
                goals = gmHead?.let { h -> o.tokens.filter { near(it, h.xc) && RX_SCORE.matches(it.text.trim()) }.minByOrNull { abs(it.yc - rowY) }?.text?.trim() }
            } else if (valHead != null) {
                valueRank = rankTok?.let { intTok(it) }?.takeIf { it in 1..40 }
                value = o.tokens.filter { abs(it.xc - valHead.xc) <= 0.04f && abs(it.yc - rowY) <= 0.045f }.mapNotNull { Money.extract(it.text) }.firstOrNull()
            }
        }
        return SeasonRead(rank, points, goals, champion, board, value, valueRank, SeasonScreens.seasonOver(o.fullText))
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
