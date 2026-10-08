package com.osmaicoach.collector

import kotlin.math.exp
import kotlin.math.roundToInt
import org.json.JSONObject

/**
 * Calendário "inteligente": separa jogos de copa (o card mostra a fase, ex. "Meias finais", no lugar do rival),
 * sabe quando fui eliminado da copa e só considera um jogo disputado depois do horário do card passar.
 */
object Fixtures {
    private val STAGE = Regex(
        "^(oitavos|quartos|meias? ?finais|meia ?final|semi ?finais?|semifinal|finais|final|fase de grupos|" +
            "grupo [a-h]|eliminatoria|pre ?eliminatoria|ronda|1/\\d+|play ?off)\\b"
    )

    /** Texto do card é o nome de uma fase de copa (não é um time). */
    fun isStage(text: String?): Boolean = text != null && STAGE.containsMatchIn(Txt.norm(text))

    fun isCup(m: MatchEntity): Boolean = m.label.startsWith("Copa") || isStage(m.opponent)

    fun stage(m: MatchEntity): String? = when {
        m.label.startsWith("Copa • ") -> m.label.removePrefix("Copa • ")
        isStage(m.opponent) -> m.opponent
        else -> null
    }

    /** Rival de verdade do card (null quando o card só mostra a fase da copa). */
    fun opponent(m: MatchEntity): String? = m.opponent?.takeIf { !isStage(it) && FieldMerge.known(it) }

    /** Rodada da derrota que me tirou da copa (mata-mata); null se ainda estou na copa. */
    fun cupOutRound(ms: List<MatchEntity>): Int? =
        ms.filter { isCup(it) && it.result == "D" && it.round != null }.minOfOrNull { it.round!! }

    /** Jogo de copa depois da eliminação: não vou jogar, não é "próximo" nem pede resultado. */
    fun void(m: MatchEntity, ms: List<MatchEntity>): Boolean {
        val out = cupOutRound(ms) ?: return false
        return isCup(m) && m.result == null && (m.round ?: Int.MAX_VALUE) > out
    }

    /** Início do jogo pelo card (data + hora; só hora = relativo a quando o card foi lido). */
    fun kickoff(m: MatchEntity): Long? {
        val t = m.time ?: return null
        return MatchClock.toMillis(m.date, t, m.updatedAt)
    }

    private fun dayEnd(date: String): Long? {
        val d = Regex("^(\\d{2})/(\\d{2})/(\\d{2})$").find(date.trim()) ?: return null
        val c = java.util.Calendar.getInstance()
        c.set(2000 + d.groupValues[3].toInt(), d.groupValues[2].toInt() - 1, d.groupValues[1].toInt(), 23, 59, 59)
        return c.timeInMillis
    }

    /** O horário do card já passou (ou o dia inteiro do card já passou). */
    fun started(m: MatchEntity, now: Long): Boolean {
        val k = kickoff(m)
        if (k != null) return k <= now
        val de = m.date?.let { dayEnd(it) } ?: return false
        return de < now
    }

    /** Próximo jogo de verdade: sem resultado, não anulado pela eliminação da copa e ainda não começado. */
    fun next(ms: List<MatchEntity>, now: Long): MatchEntity? =
        ms.filter { it.round != null && it.result == null && !void(it, ms) && !started(it, now) }.minByOrNull { it.round!! }

    /**
     * Jogo que já aconteceu pelo calendário (data/hora do card passou) e ainda não tem placar nem análise lida.
     * Só esse pede resultado; sem data/hora no card, nunca pede.
     */
    fun awaitingResult(ms: List<MatchEntity>, scoredRounds: Set<Int>, now: Long): MatchEntity? =
        ms.filter {
            it.round != null && it.result == null && it.scoreMine == null && !void(it, ms) &&
                (it.time != null || it.date != null) && started(it, now) && it.round !in scoredRounds
        }.maxByOrNull { it.round!! }
}

/**
 * Evidências cruzadas: o mesmo dado lido em telas diferentes (pré-jogo, calendário, análise, plantel do rival).
 * Um valor só é "confirmado" quando bate em fontes independentes; texto de fundo (ex. "25 Anniversary") não passa.
 * Formato: {"campo": {"valor": {"FONTE": instante}}}.
 */
object Evidence {
    /** Peso de cada fonte: a etiqueta "Apelido [S3]" da análise é inequívoca; as demais contam 1. */
    private fun weight(source: String): Int = if (source == "REPORT") 2 else 1

    private val JUNK_PREFIX = listOf("anniv", "aniver")
    private val JUNK_WORDS = setOf(
        "season", "temporada", "edition", "edicao", "jornada", "liga", "copa", "batalha", "estadio", "arbitro",
        "treinador", "ranking", "pontos", "jogos", "vitorias", "classificacao", "anos", "years", "aniversario"
    )

    /** Pode ser um apelido de usuário? (rejeita textos de fundo, números soltos e palavras do jogo) */
    fun plausibleNick(s: String?): Boolean {
        val t = s?.trim() ?: return false
        if (t.length !in 3..24 || Txt.letters(t) < 2) return false
        val words = Txt.norm(t).split(" ")
        if (words.any { w -> w in JUNK_WORDS || JUNK_PREFIX.any { w.startsWith(it) } }) return false
        val n = Txt.norm(t)
        if (Regex("^\\d+\\s").containsMatchIn(n)) return false
        return true
    }

    fun add(j: JSONObject, field: String, value: String, source: String, now: Long) {
        val v = value.trim()
        if (!FieldMerge.known(v)) return
        val f = j.optJSONObject(field) ?: JSONObject().also { j.put(field, it) }
        // agrupa leituras quase iguais (erro de OCR de uma letra) sob o primeiro valor visto
        val existing = f.keys().asSequence().firstOrNull { Txt.sim(Txt.key(it), Txt.key(v)) >= 0.85 }
        val bucket = f.optJSONObject(existing ?: v) ?: JSONObject().also { f.put(v, it) }
        bucket.put(source, now)
    }

    data class Best(val value: String, val weight: Int, val sources: List<String>)

    fun best(j: JSONObject, field: String): Best? {
        val f = j.optJSONObject(field) ?: return null
        var out: Best? = null
        for (v in f.keys()) {
            val b = f.optJSONObject(v) ?: continue
            val srcs = b.keys().asSequence().toList()
            val w = srcs.sumOf { weight(it) }
            if (out == null || w > out.weight) out = Best(v, w, srcs)
        }
        return out
    }

    fun confirmed(j: JSONObject, field: String): Best? = best(j, field)?.takeIf { it.weight >= 2 }

    /** Valores diferentes vistos para o mesmo campo (divergência entre telas). */
    fun values(j: JSONObject, field: String): List<Best> {
        val f = j.optJSONObject(field) ?: return emptyList()
        return f.keys().asSequence().mapNotNull { v ->
            val b = f.optJSONObject(v) ?: return@mapNotNull null
            val srcs = b.keys().asSequence().toList()
            Best(v, srcs.sumOf { weight(it) }, srcs)
        }.sortedByDescending { it.weight }.toList()
    }

    fun sourceLabel(s: String): String = when (s) {
        "PREGAME" -> "pré-jogo"
        "CALENDAR" -> "calendário"
        "REPORT" -> "análise"
        "SQUAD" -> "plantel do rival"
        "RESULT" -> "relatório do jogo"
        else -> s.lowercase()
    }
}

/**
 * Previsão de vitória/empate/derrota. Soma "pontos de força" de cada fator (força, setores, mando, rival humano,
 * bônus, forma recente, confronto anterior e o histórico da tática escolhida) e converte em probabilidades.
 */
object WinModel {
    data class Factor(val label: String, val pts: Double)
    data class Prob(val win: Int, val draw: Int, val loss: Int, val factors: List<Factor>, val confidence: String, val edge: Double)

    data class Input(
        val myStrength: Int?,
        val rivalStrength: Int?,
        val myAtk: Int? = null, val myMid: Int? = null, val myDef: Int? = null,
        val rivalAtk: Int? = null, val rivalMid: Int? = null, val rivalDef: Int? = null,
        val home: Boolean? = null,
        val rivalHuman: Boolean? = null,
        val myBonus: Int? = null,
        val rivalBonus: Int? = null,
        /** Últimos resultados (mais recente primeiro): "V", "E", "D". */
        val recent: List<String> = emptyList(),
        /** Resultados anteriores contra este mesmo rival. */
        val headToHead: List<String> = emptyList(),
        /** Resultados já obtidos com esta formação/estilo. */
        val tacticRecord: List<String> = emptyList()
    )

    private fun score(r: String): Double = when (r) {
        "V" -> 1.0
        "D" -> -1.0
        else -> 0.0
    }

    fun predict(i: Input): Prob? {
        val my = i.myStrength ?: return null
        val rv = i.rivalStrength ?: return null
        val fs = ArrayList<Factor>()
        fs.add(Factor("Força geral", (my - rv).toDouble()))
        if (listOf(i.myAtk, i.myMid, i.myDef, i.rivalAtk, i.rivalMid, i.rivalDef).all { it != null }) {
            val s = ((i.myAtk!! - i.rivalDef!!) + (i.myMid!! - i.rivalMid!!) + (i.myDef!! - i.rivalAtk!!)) / 3.0
            fs.add(Factor("Duelo dos setores", s * 0.4))
        }
        when (i.home) {
            true -> fs.add(Factor("Jogo em casa", 3.0))
            false -> fs.add(Factor("Jogo fora", -3.0))
            null -> {}
        }
        when (i.rivalHuman) {
            true -> fs.add(Factor("Rival humano", -1.5))
            false -> fs.add(Factor("Rival CPU", 1.0))
            null -> {}
        }
        if (i.myBonus != null || i.rivalBonus != null) {
            val b = ((i.myBonus ?: 0) - (i.rivalBonus ?: 0)) * 0.25
            if (b != 0.0) fs.add(Factor("Bônus de login", b))
        }
        if (i.recent.isNotEmpty()) {
            val f = i.recent.take(5).map { score(it) }.average() * 2.5
            fs.add(Factor("Forma recente " + i.recent.take(5).joinToString(""), f))
        }
        if (i.headToHead.isNotEmpty()) {
            fs.add(Factor("Confronto anterior", i.headToHead.map { score(it) }.average() * 2.0))
        }
        if (i.tacticRecord.size >= 2) {
            val wr = i.tacticRecord.count { it == "V" }.toDouble() / i.tacticRecord.size
            fs.add(Factor("Histórico desta tática (${i.tacticRecord.size} jogos)", ((wr - 0.45) * 8.0).coerceIn(-4.0, 4.0)))
        }
        val d = fs.sumOf { it.pts }
        val pDraw = 0.27 * exp(-(d / 16.0) * (d / 16.0)) + 0.03
        val pWin = (1.0 - pDraw) / (1.0 + exp(-d / 9.0))
        val win = (pWin * 100).roundToInt().coerceIn(2, 96)
        val draw = (pDraw * 100).roundToInt().coerceIn(2, 40).coerceAtMost(99 - win)
        val loss = 100 - win - draw
        val known = listOf(i.home != null, i.rivalHuman != null, i.myAtk != null && i.rivalDef != null, i.recent.isNotEmpty(), i.tacticRecord.size >= 2).count { it }
        val conf = when {
            known >= 4 -> "alta"
            known >= 2 -> "média"
            else -> "baixa"
        }
        return Prob(win, draw, loss, fs.filter { kotlin.math.abs(it.pts) >= 0.3 }, conf, d)
    }
}
