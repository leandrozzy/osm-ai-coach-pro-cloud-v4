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

    /**
     * O jogo já aconteceu? A ordem das rodadas manda: a "próxima rodada" do pré-jogo (curRound) ainda não foi
     * jogada até o horário dela (curAt) passar; rodadas antes dela já foram; rodadas depois, não. O card só com
     * a hora ("22:18") não diz o dia, então nunca decide sozinho quando a rodada atual é conhecida.
     */
    fun happened(m: MatchEntity, now: Long, curRound: Int?, curAt: Long?): Boolean {
        if (m.result != null || m.scoreMine != null) return true
        val r = m.round
        if (curRound != null && r != null) {
            return when {
                r < curRound -> true
                r > curRound -> false
                else -> curAt != null && curAt <= now
            }
        }
        return started(m, now)
    }

    /** Rodada atual (a próxima a ser jogada) e o horário dela, já conferidos com os placares e o calendário. */
    data class Clock(val round: Int?, val at: Long?, val lastDone: Int?)

    /** Última rodada com placar (card com resultado ou análise do jogo lida). */
    fun lastDone(ms: List<MatchEntity>, scored: Set<Int>): Int? =
        (ms.filter { it.round != null && (it.result != null || it.scoreMine != null) }.map { it.round!! } + scored).maxOrNull()

    /**
     * Fonte única da verdade para "qual é o próximo jogo e quando": Hoje, Resultado, Calendário, Diretor e
     * notificações usam isto. Corrige três incoerências que apareciam juntas:
     *  - a rodada guardada ficou para trás de um placar já lido (vira a seguinte);
     *  - o horário guardado já passou quando a rodada mudou (era do jogo anterior: descarta);
     *  - a rodada pulou uma a mais, mas o rival lido no pré-jogo é o da rodada anterior ainda sem placar (volta uma).
     */
    fun clock(
        ms: List<MatchEntity>, scored: Set<Int>, now: Long,
        round: Int?, roundAt: Long?, matchAt: Long?, rival: String?
    ): Clock {
        val done = lastDone(ms, scored)
        var r = round
        var at = matchAt
        if (at != null && at <= now && roundAt != null && roundAt > at + 60000L) at = null
        // rodada 1-3 com placares bem mais à frente = competição nova (o Repo arquiva a antiga): não "pula" para done+1
        val newSeason = r != null && done != null && r <= 3 && done >= r + 5
        if (done != null && !newSeason && (r == null || r <= done)) {
            r = done + 1
            if (at != null && at <= now) at = null
        }
        val rk = rival?.takeIf { FieldMerge.known(it) }?.let { Txt.key(it) }
        val cur = r
        if (done != null && cur != null && cur - 1 > done && rk != null && rk.length >= 3) {
            fun vs(m: MatchEntity?): Boolean = m?.let { opponent(it) }?.let { Txt.sim(Txt.key(it), rk) >= 0.85 } == true
            val prev = ms.firstOrNull { it.round == cur - 1 && !isCup(it) && it.result == null && it.scoreMine == null }
            if (prev != null && vs(prev) && !vs(ms.firstOrNull { it.round == cur && !isCup(it) })) r = cur - 1
        }
        return Clock(r, at, done)
    }

    fun clock(ms: List<MatchEntity>, scored: Set<Int>, now: Long, f: Map<String, StoredField>): Clock =
        clock(
            ms, scored, now, f[K.ROUND]?.value?.toIntOrNull(), f[K.ROUND]?.updatedAt,
            f[K.MATCH_AT]?.value?.toLongOrNull(), f[K.RIVAL_TEAM]?.value
        )

    /** Próximo jogo de verdade: sem resultado, não anulado pela eliminação da copa e que ainda não aconteceu. */
    fun next(ms: List<MatchEntity>, now: Long, curRound: Int? = null, curAt: Long? = null): MatchEntity? =
        ms.filter { it.round != null && it.result == null && !void(it, ms) && !happened(it, now, curRound, curAt) }
            .minByOrNull { it.round!! }

    /**
     * Alerta "Registrar resultado" (Hoje, Diretor e notificação): só o jogo da rodada atual depois do horário
     * dele, enquanto ainda não tem placar. Se o próximo jogo já está marcado no futuro, não há o que pedir —
     * rodadas antigas sem placar ficam só na aba Resultado, sem alerta. Sem rodada atual conhecida, vale a
     * data/hora do próprio card.
     */
    fun resultDue(ms: List<MatchEntity>, scored: Set<Int>, now: Long, curRound: Int?, curAt: Long?): MatchEntity? {
        if (curRound == null) return awaitingResult(ms, scored, now, null, null)
        if (curAt == null || curAt > now) return null
        return ms.firstOrNull {
            it.round == curRound && it.result == null && it.scoreMine == null && curRound !in scored && !void(it, ms)
        }
    }

    /**
     * Jogo que acabou de acontecer (a rodada atual depois do horário, ou a anterior) e ainda não tem placar nem
     * análise lida. Só esse pede resultado; rodadas antigas sem placar ficam na aba Resultado, sem alerta.
     */
    fun awaitingResult(ms: List<MatchEntity>, scoredRounds: Set<Int>, now: Long, curRound: Int? = null, curAt: Long? = null): MatchEntity? =
        ms.filter {
            val r = it.round
            r != null && it.result == null && it.scoreMine == null && !void(it, ms) && r !in scoredRounds &&
                (it.time != null || it.date != null || curRound != null) && happened(it, now, curRound, curAt) &&
                (curRound == null || r >= curRound - 1)
        }.maxByOrNull { it.round!! }
}

/**
 * Evidências cruzadas: o mesmo dado lido em telas diferentes (pré-jogo, calendário, análise, plantel do rival).
 * Um valor só é "confirmado" quando bate em fontes independentes; texto de fundo (ex. "25 Anniversary") não passa.
 * Formato: {"campo": {"valor": {"FONTE": instante}}}.
 */
object Evidence {
    /** Peso de cada fonte: a etiqueta "Apelido [S3]" da análise é inequívoca; as demais contam 1. */
    /**
     * Peso de cada fonte. O nome do usuário escrito sob o time do rival no pré-jogo, no plantel dele ou na
     * análise do jogo é inequívoco (só humano tem): basta uma dessas telas. O calendário (texto pequeno no card,
     * com fundo decorado) precisa de outra tela junto.
     */
    private fun weight(source: String): Int = if (source == "REPORT" || source == "PREGAME" || source == "SQUAD") 2 else 1

    private val JUNK_PREFIX = listOf("anniv", "aniver")
    private val JUNK_WORDS = setOf(
        "season", "temporada", "edition", "edicao", "jornada", "liga", "copa", "batalha", "estadio", "arbitro",
        "treinador", "ranking", "pontos", "jogos", "vitorias", "classificacao", "anos", "years", "aniversario",
        // textos dos botões flutuantes do próprio app (aparecem nas capturas da tela)
        "encerrar", "toque", "captura", "app", "osm", "vers", "versus", "vs"
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
            if (field == K.RIVAL_NICK && !plausibleNick(v)) continue
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
            if (field == K.RIVAL_NICK && !plausibleNick(v)) return@mapNotNull null
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
 * Simulação do jogo: gols esperados de cada lado (Poisson) a partir do MEU XI e da TÁTICA gerada contra tudo o
 * que se sabe do rival (setores, formação, estilo, marcação, impedimento, desarme, estágio, treino secreto, bônus),
 * mais árbitro, mando e aprendizado. Cada fator mostra quanto muda a chance de vitória (em pontos percentuais).
 */
object WinModel {
    data class Factor(val label: String, val pts: Double)
    data class Prob(
        val win: Int, val draw: Int, val loss: Int, val factors: List<Factor>, val confidence: String,
        val xgMine: Double, val xgOpp: Double,
        /** Placar mais provável (meus gols, gols do rival). */
        val likely: Pair<Int, Int> = Pair(1, 1)
    ) {
        /** Resumo em palavras para quem não quer ler números. */
        val verdict: String get() = when {
            win >= 65 -> "Favorito claro"
            win >= 50 -> "Favorito"
            kotlin.math.abs(win - loss) <= 10 -> "Jogo equilibrado"
            loss >= 50 -> "Azarão — cuidado"
            else -> "Leve desvantagem"
        }

        /** Pontos esperados (3 por vitória, 1 por empate): critério para comparar táticas. */
        val points: Double get() = (3.0 * win + draw) / 100.0
    }

    /** Tática avaliada (a gerada pelo app ou uma alternativa). */
    data class Plan(
        val formation: String, val style: String? = null, val pressure: Int = 50, val mentality: Int = 50, val tempo: Int = 50,
        val marking: String? = null, val offside: String? = null, val tackle: String? = null,
        val advAttack: String? = null, val advMid: String? = null,
        /** Força média do XI por setor (GOL, DEF, MEI, ATA), se a escalação for conhecida. */
        val xiGol: Double? = null, val xiDef: Double? = null, val xiMid: Double? = null, val xiAtk: Double? = null
    )

    data class Input(
        val myStrength: Int?,
        val rivalStrength: Int?,
        val myAtk: Int? = null, val myMid: Int? = null, val myDef: Int? = null, val myGol: Int? = null,
        val rivalAtk: Int? = null, val rivalMid: Int? = null, val rivalDef: Int? = null, val rivalGol: Int? = null,
        val plan: Plan? = null,
        val rivalFormation: String? = null,
        val rivalStyle: String? = null,
        val rivalMarking: String? = null,
        val rivalOffside: String? = null,
        val rivalTackle: String? = null,
        val rivalSecret: Boolean? = null,
        val rivalCamp: Boolean? = null,
        val home: Boolean? = null,
        val rivalHuman: Boolean? = null,
        val referee: String? = null,
        val myBonus: Int? = null,
        val rivalBonus: Int? = null,
        /** Últimos resultados (mais recente primeiro): "V", "E", "D". */
        val recent: List<String> = emptyList(),
        /** Resultados anteriores contra este mesmo rival. */
        val headToHead: List<String> = emptyList(),
        /** Resultados já obtidos com esta formação/estilo. */
        val tacticRecord: List<String> = emptyList(),
        /** Correção aprendida deste slot: o modelo vem prevendo gols a mais (<0) ou a menos (>0). */
        val calib: Double = 0.0
    )

    /** Efeito de um fator: no log dos meus gols esperados, no log dos gols do rival e na imprevisibilidade. */
    private data class Term(val label: String, val my: Double, val opp: Double, val upset: Double = 0.0)

    private fun score(r: String): Double = when (r) {
        "V" -> 1.0
        "D" -> -1.0
        else -> 0.0
    }

    private fun n(s: String?): String = Txt.norm(s ?: "")

    private fun terms(i: Input): List<Term>? {
        val my = i.myStrength ?: listOfNotNull(i.myAtk, i.myMid, i.myDef).takeIf { it.isNotEmpty() }?.average()?.toInt() ?: return null
        val rv = i.rivalStrength ?: listOfNotNull(i.rivalAtk, i.rivalMid, i.rivalDef).takeIf { it.isNotEmpty() }?.average()?.toInt() ?: return null
        val p = i.plan
        val atk = p?.xiAtk ?: i.myAtk?.toDouble() ?: my.toDouble()
        val mid = p?.xiMid ?: i.myMid?.toDouble() ?: my.toDouble()
        val def = p?.xiDef ?: i.myDef?.toDouble() ?: my.toDouble()
        val gk = p?.xiGol ?: i.myGol?.toDouble() ?: def
        val rAtk = (i.rivalAtk ?: rv).toDouble()
        val rMid = (i.rivalMid ?: rv).toDouble()
        val rDef = (i.rivalDef ?: rv).toDouble()
        val rGk = (i.rivalGol ?: i.rivalDef ?: rv).toDouble()
        val t = ArrayList<Term>()

        // forças (com o XI escolhido, quando há tática)
        val atkGap = atk - (0.8 * rDef + 0.2 * rGk)
        val defGap = rAtk - (0.8 * def + 0.2 * gk)
        t.add(Term("Meu ataque × defesa rival", atkGap / 28.0, 0.0))
        t.add(Term("Ataque rival × minha defesa", 0.0, defGap / 28.0))
        val midGap = mid - rMid
        t.add(Term("Meio-campo", midGap / 70.0, -midGap / 70.0))
        if (i.myBonus != null || i.rivalBonus != null) {
            val b = (my * (i.myBonus ?: 0) - rv * (i.rivalBonus ?: 0)) / 100.0 / 28.0
            t.add(Term("Bônus de login", b, -b))
        }
        when (i.home) {
            true -> t.add(Term("Jogo em casa", 0.10, -0.08))
            false -> t.add(Term("Jogo fora", -0.08, 0.10))
            null -> {}
        }

        // formação contra formação
        val mine = p?.let { Formations.lines(it.formation) }?.takeIf { it.isNotEmpty() }
        val theirs = i.rivalFormation?.let { Formations.lines(it) }?.takeIf { it.isNotEmpty() }
        if (mine != null && theirs != null) {
            val myM = 10 - mine.first() - mine.last()
            val rM = 10 - theirs.first() - theirs.last()
            if (myM != rM) t.add(Term("Meio: $myM × $rM jogadores", (myM - rM) * 0.04, -(myM - rM) * 0.03))
            val press = (mine.last() - theirs.first() + 2) * 0.03
            if (press != 0.0) t.add(Term("Atacantes × defensores rivais", press, 0.0))
            val risk = (theirs.last() - mine.first() + 2) * 0.03
            if (risk != 0.0) t.add(Term("Atacantes rivais × meus defensores", 0.0, risk))
        }

        if (i.calib != 0.0) t.add(Term("Ajuste pelo histórico de previsões deste slot", i.calib, 0.0))
        // Contra-tática da comunidade (OSM Guide / fórum) para a formação do rival.
        if (p != null) {
            val ct = CounterBook.matches(i.rivalFormation, my - rv, p.formation, p.style)
            if (ct) t.add(Term("Contra-tática recomendada para ${i.rivalFormation}", 0.05, -0.02))
        }
        // Volume ofensivo: contra rival mais fraco, cada atacante a mais vira gol (mais gols no jogo favorecem o
        // mais forte); contra mais forte, atacante a mais só expõe a defesa.
        if (mine != null) {
            val edge = (my - rv).toDouble()
            val k = mine.last() - 2
            val d = mine.first() - 4
            if (k != 0) {
                val gain = 0.05 * k * (edge / 8.0).coerceIn(-1.0, 2.0)
                t.add(Term(if (k > 0) "${mine.last()} atacantes" else "Só ${mine.last()} atacante", gain, 0.025 * k))
            }
            if (d > 0 && edge >= 5) t.add(Term("Defensores a mais contra rival fraco", -0.04 * d, -0.02 * d))
        }

        if (p != null) {
            val style = n(p.style)
            val rStyle = n(i.rivalStyle)
            val rAttacking = (theirs?.last() ?: 2) >= 3 || rStyle.contains("passe") || rStyle.contains("remate")
            when {
                style.contains("alas") -> {
                    var v = 0.0
                    if ((theirs?.first() ?: 4) <= 3) v += 0.06
                    if (Osm.marking(i.rivalMarking) == "Homem-a-homem") v += 0.04
                    if ((theirs?.first() ?: 4) >= 5) v -= 0.05
                    if (v != 0.0) t.add(Term("Jogar pelas alas × defesa rival", v, 0.0))
                }
                style.contains("contra") -> {
                    var v = if (rAttacking) 0.08 else -0.02
                    if (atkGap > 10) v -= 0.08
                    t.add(Term("Contra-ataque × rival " + (if (rAttacking) "ofensivo" else "fechado"), v, -0.03))
                }
                style.contains("remate") -> {
                    val v = if (atkGap >= 5) 0.06 else if (rGk > atk) -0.05 else 0.0
                    if (v != 0.0) t.add(Term("Remate à vista × goleiro rival", v, 0.0))
                }
                style.contains("passe") -> {
                    val v = if (midGap >= 3) 0.07 else if (midGap <= -3) -0.06 else 0.0
                    if (v != 0.0) t.add(Term("Jogo de passe × meio rival", v, 0.0))
                }
                style.contains("long") || style.contains("bola") -> {
                    val v = if (n(i.rivalOffside) == "sim") -0.07 else if (atkGap > 0) 0.05 else 0.0
                    if (v != 0.0) t.add(Term("Bola longa × linha rival", v, 0.0))
                }
            }
            val m = (p.mentality - 50) / 50.0
            if (m != 0.0) t.add(Term("Mentalidade ${p.mentality}", 0.12 * m, 0.10 * m))
            // Antídotos ao estilo do rival (o que mais pesa contra humanos, que escolhem o estilo a dedo).
            val myD = mine?.first()
            when (Osm.style(i.rivalStyle)) {
                "Contra-ataque" -> {
                    var opp = 0.0
                    if (m > 0) opp += 0.06 * m
                    if (Osm.midfield(p.advMid) == "Pressionar na frente") opp += 0.03
                    if (Osm.midfield(p.advMid) == "Ajudar a defesa" || Osm.midfield(p.advMid) == "Manter posições") opp -= 0.02
                    if (opp != 0.0) t.add(Term(if (opp > 0) "Linha alta contra o contra-ataque dele" else "Bloco seguro contra o contra-ataque dele", 0.0, opp))
                }
                "Jogar pelas alas" -> {
                    val opp = (if (myD != null && myD >= 5) -0.05 else if (myD != null && myD <= 3) 0.05 else 0.0) +
                        (if (Osm.marking(p.marking) == "À zona") -0.02 else 0.0)
                    if (opp != 0.0) t.add(Term(if (opp < 0) "Laterais fechando as alas dele" else "Alas dele contra 3 defensores", 0.0, opp))
                }
                "Remate à vista", "Jogo de passe" -> {
                    val pr = (p.pressure - 50) / 50.0
                    if (pr > 0) t.add(Term("Pressão alta tira o tempo de ${Osm.style(i.rivalStyle)}", 0.0, -0.03 * pr))
                }
                else -> {}
            }
            val pr = (p.pressure - 50) / 50.0
            if (pr != 0.0) {
                var opp = if (midGap >= 0) -0.03 * pr else 0.04 * pr
                if (rStyle.contains("passe")) opp -= 0.04 * pr
                t.add(Term("Pressão ${p.pressure}", 0.05 * pr, opp))
            }
            val tp = (p.tempo - 50) / 50.0
            if (tp != 0.0) t.add(Term("Ritmo ${p.tempo}", if (atkGap >= 0) 0.04 * tp else -0.02 * tp, 0.0))
            if (Osm.marking(p.marking) == "Homem-a-homem") {
                t.add(Term("Marcação individual", 0.0, if (def >= rAtk) -0.05 else 0.07))
            }
            if (n(p.offside) == "sim") {
                val opp = if (rStyle.contains("contra") || rStyle.contains("long")) 0.10 else if (def >= rAtk) -0.04 else 0.05
                t.add(Term("Linha de impedimento", 0.0, opp))
            }
            // Desarme × árbitro: cartões (expulsão) custam muito mais do que a bola roubada a mais.
            when (Osm.tackle(p.tackle)) {
                "Extremo" -> when (i.referee) {
                    "Brando" -> t.add(Term("Desarme extremo (risco de expulsão)", 0.0, 0.08))
                    "Rigoroso" -> t.add(Term("Desarme extremo × árbitro rigoroso", -0.10, 0.22))
                    else -> t.add(Term("Desarme extremo × árbitro ${i.referee ?: "não lido"}", -0.06, 0.15))
                }
                "Agressivo" -> when (i.referee) {
                    "Brando" -> t.add(Term("Desarme agressivo × árbitro brando", 0.05, -0.04))
                    "Rigoroso" -> t.add(Term("Desarme agressivo × árbitro rigoroso", -0.05, 0.12))
                    else -> t.add(Term("Desarme agressivo × árbitro ${i.referee ?: "não lido"}", -0.02, 0.06))
                }
                "Cuidadoso" -> t.add(Term("Desarme cuidadoso", 0.0, if (i.referee == "Rigoroso") -0.01 else 0.03))
                else -> {}
            }
            when {
                n(p.advAttack).contains("atacar") -> t.add(Term("Atacantes: atacar apenas", 0.03, 0.02))
                n(p.advAttack).contains("ajudar") -> t.add(Term("Atacantes: ajudar a defender", -0.03, -0.05))
            }
            when {
                n(p.advMid).contains("pression") -> t.add(Term("Meias: pressionar à frente", 0.04, 0.03))
                n(p.advMid).contains("ajudar") -> t.add(Term("Meias: ajudar a defesa", -0.03, -0.05))
            }
        }
        if (n(i.rivalTackle).contains("agress") && i.referee == "Rigoroso") t.add(Term("Rival agressivo × árbitro rigoroso", 0.06, -0.02))
        if (i.rivalCamp == true) t.add(Term("Rival fez estágio", -0.03, 0.04))
        if (i.rivalSecret == true) t.add(Term("Treino secreto do rival", 0.0, 0.02, 0.06))
        when (i.rivalHuman) {
            true -> t.add(Term("Rival humano", 0.0, 0.02, 0.03))
            false -> t.add(Term("Rival CPU", 0.02, 0.0))
            null -> {}
        }
        if (i.recent.isNotEmpty()) {
            val f = i.recent.take(5).map { score(it) }.average()
            t.add(Term("Forma recente " + i.recent.take(5).joinToString(""), 0.06 * f, -0.03 * f))
        }
        if (i.headToHead.isNotEmpty()) t.add(Term("Confronto anterior", 0.05 * i.headToHead.map { score(it) }.average(), 0.0))
        if (i.tacticRecord.size >= 2) {
            val wr = i.tacticRecord.count { it == "V" }.toDouble() / i.tacticRecord.size
            t.add(Term("Histórico desta tática (${i.tacticRecord.size} jogos)", ((wr - 0.45) * 0.3).coerceIn(-0.15, 0.15), 0.0))
        }
        return t
    }

    private fun poisson(l: Double): DoubleArray {
        val out = DoubleArray(11)
        var v = exp(-l)
        for (k in 0..10) {
            out[k] = v
            v = v * l / (k + 1)
        }
        return out
    }

    /** (vitória, empate, derrota, gols meus, gols rival) para um conjunto de fatores. */
    private fun outcome(ts: List<Term>): DoubleArray {
        val lm = (1.35 * exp(ts.sumOf { it.my })).coerceIn(0.1, 6.0)
        val lo = (1.35 * exp(ts.sumOf { it.opp })).coerceIn(0.1, 6.0)
        val a = poisson(lm)
        val b = poisson(lo)
        var w = 0.0
        var d = 0.0
        var l = 0.0
        for (x in 0..10) for (y in 0..10) {
            val pr = a[x] * b[y]
            if (x > y) w += pr else if (x == y) d += pr else l += pr
        }
        val tot = w + d + l
        // futebol tem zebra: parte da chance é sempre imprevisível (mais com rival humano ou treino secreto)
        val u = (0.12 + ts.sumOf { it.upset }).coerceAtMost(0.25)
        return doubleArrayOf(
            (1 - u) * w / tot + u * 0.36, (1 - u) * d / tot + u * 0.28, (1 - u) * l / tot + u * 0.36, lm, lo
        )
    }

    /** Pontos esperados (3V + 1E) sem detalhar fatores: rápido para comparar centenas de táticas. */
    fun expectedPoints(i: Input): Double? {
        val o = outcome(terms(i) ?: return null)
        return 3.0 * o[0] + o[1]
    }

    fun predict(i: Input): Prob? {
        val ts = terms(i) ?: return null
        val all = outcome(ts)
        val win = (all[0] * 100).roundToInt().coerceIn(1, 98)
        val draw = (all[1] * 100).roundToInt().coerceIn(1, 99 - win)
        val loss = 100 - win - draw
        // quanto cada fator muda a chance de vitória (tirando só ele)
        val factors = ts.map { term -> Factor(term.label, (all[0] - outcome(ts.filter { it !== term })[0]) * 100.0) }
            .filter { kotlin.math.abs(it.pts) >= 0.5 }
        val known = listOf(
            i.plan != null, i.rivalAtk != null && i.rivalDef != null, i.rivalFormation != null, i.home != null,
            i.rivalStyle != null, i.referee != null, i.recent.isNotEmpty()
        ).count { it }
        val conf = when {
            known >= 6 -> "alta"
            known >= 3 -> "média"
            else -> "baixa"
        }
        val a = poisson(all[3])
        val b = poisson(all[4])
        var best = Pair(0, 0)
        var bp = -1.0
        for (x in 0..6) for (y in 0..6) if (a[x] * b[y] > bp) {
            bp = a[x] * b[y]
            best = Pair(x, y)
        }
        return Prob(win, draw, loss, factors, conf, all[3], all[4], best)
    }
}

/**
 * O "treinador": lê cada jogo (placar, posse, remates, faltas, formação do rival e a tática usada), diz onde
 * errou e o que deu certo, e transforma isso em ajustes concretos que a geração da tática aplica.
 */
object Coach {
    data class Game(
        val round: Int, val rival: String, val result: String?, val gf: Int?, val ga: Int?,
        val formation: String?, val style: String?, val poss: Int?, val shots: Int?, val oppShots: Int?, val fouls: Int?,
        val oppFormation: String?, val advice: String?
    )

    data class Review(val game: Game, val wrong: List<String>, val right: List<String>)

    data class Lessons(
        val midNeed: Int = 0, val defNeed: Int = 0, val atkNeed: Int = 0, val discipline: Boolean = false,
        val avoid: Set<String> = emptySet(), val plan: List<String> = emptyList()
    )

    /** Número de uma estatística ("54%", "540" lido sem o %, "12"); valor impossível vira null. */
    fun num(s: String?, pct: Boolean = false): Int? {
        val v = s?.let { Regex("(\\d{1,3})").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: return null
        if (!pct) return v
        if (v in 0..100) return v
        return if (v % 10 == 0 && v / 10 <= 100) v / 10 else null
    }

    private fun same(a: String?, b: String?): Boolean {
        val x = Txt.key(a ?: "")
        val y = Txt.key(b ?: "")
        return x.length >= 3 && y.length >= 3 && SlotMatcher.nameSim(x, y) >= 0.8
    }

    private fun opt(j: JSONObject, k: String): String? = if (j.has(k) && !j.isNull(k)) j.optString(k).ifBlank { null } else null

    /** Junta análises do jogo (estatísticas) com as táticas usadas (pela rodada ou pelo rival, se a rodada não bate). */
    fun games(logs: List<JSONObject>, reports: List<JSONObject>): List<Game> {
        val used = HashSet<JSONObject>()
        val out = ArrayList<Game>()
        for (r in reports) {
            if (!r.has("sh") || !r.has("sa")) continue
            val round = r.optInt("round", -1).takeIf { it > 0 } ?: continue
            val mh = r.optBoolean("mineHome", true)
            val gf = if (mh) r.optInt("sh") else r.optInt("sa")
            val ga = if (mh) r.optInt("sa") else r.optInt("sh")
            val opp = (if (mh) r.optString("awayTeam") else r.optString("homeTeam")).ifBlank { null }
            val st = r.optJSONObject("stats")
            fun side(label: String, mine: Boolean): String? = st?.optJSONArray(label)?.optString(if (mh == mine) 0 else 1)?.ifBlank { null }
            val log = logs.firstOrNull { it !in used && it.optInt("round") == round && (opp == null || same(it.optString("rival"), opp)) }
                ?: logs.filter { it !in used && opp != null && same(it.optString("rival"), opp) && it.optInt("round") in (round - 2)..round }
                    .maxByOrNull { it.optInt("round") }
                ?: logs.firstOrNull { it !in used && it.optInt("round") == round }
            if (log != null) used.add(log)
            out.add(
                Game(
                    round, opp ?: log?.optString("rival")?.ifBlank { null } ?: "adversário",
                    if (gf > ga) "V" else if (gf == ga) "E" else "D", gf, ga,
                    log?.let { opt(it, "formation") }, log?.let { opt(it, "playStyle") },
                    num(side("posse de bola", true), true), num(side("remates", true)), num(side("remates", false)),
                    num(side("faltas", true)), side("formacao", false), opt(r, "advice")
                )
            )
        }
        for (l in logs) {
            if (l in used) continue
            val res = opt(l, "result") ?: continue
            out.add(
                Game(
                    l.optInt("round"), l.optString("rival").ifBlank { "adversário" }, res,
                    if (l.has("scoreMine") && !l.isNull("scoreMine")) l.optInt("scoreMine") else null,
                    if (l.has("scoreOpp") && !l.isNull("scoreOpp")) l.optInt("scoreOpp") else null,
                    opt(l, "formation"), opt(l, "playStyle"),
                    if (l.has("myPossession")) num(l.optString("myPossession"), true) else null,
                    if (l.has("myShots")) l.optInt("myShots") else null, if (l.has("oppShots")) l.optInt("oppShots") else null,
                    if (l.has("myFouls")) l.optInt("myFouls") else null, opt(l, "oppFormation"), null
                )
            )
        }
        return out.sortedByDescending { it.round }
    }

    private fun mids(f: String?): Int? = f?.let { Formations.lines(it) }?.takeIf { it.isNotEmpty() }?.let { 10 - it.first() - it.last() }

    /** Onde errou e o que funcionou num jogo, com os números do próprio jogo. */
    fun review(g: Game): Review {
        val wrong = ArrayList<String>()
        val right = ArrayList<String>()
        val myM = mids(g.formation)
        val rM = mids(g.oppFormation)
        val p = g.poss
        if (p != null && p < 42) {
            wrong.add(
                "Perdeu o meio-campo (posse $p%)" +
                    (if (myM != null && rM != null && rM > myM) ": o rival (${g.oppFormation}) tinha $rM meias contra seus $myM." else ".")
            )
        } else if (p != null && p >= 58) right.add("Dominou a bola (posse $p%).")
        val s = g.shots
        val os = g.oppShots
        if (s != null && os != null && s * 10 < os * 6) wrong.add("Criou pouco: $s remates contra $os do rival.")
        else if (s != null && p != null && p >= 55 && s <= 6) wrong.add("Teve a bola ($p%) mas finalizou pouco ($s remates): faltou presença na área.")
        if (g.gf == 0 && g.result != "V") wrong.add("Não marcou: ataque sem efeito.")
        if ((os != null && os >= 10) || (g.ga ?: 0) >= 2) {
            wrong.add("Defesa exposta: " + listOfNotNull(os?.let { "$it remates" }, g.ga?.let { "$it gol(s)" }).joinToString(" e ") + " do rival.")
        } else if (g.ga == 0) right.add("Defesa segura (não sofreu gol).")
        val f = g.fouls
        if (f != null && f >= 16) wrong.add("Muitas faltas ($f): risco de cartão e expulsão.")
        if (g.result == "V" && g.formation != null) right.add("${g.formation} • ${g.style ?: "?"} funcionou.")
        if (g.result == "D" && g.formation != null) wrong.add("${g.formation} • ${g.style ?: "?"} perdeu para ${g.rival}.")
        return Review(g, wrong, right)
    }

    /** Ajustes para o próximo jogo a partir dos últimos 5 (o mais recente pesa mais). */
    fun lessons(games: List<Game>, nextRival: String?): Lessons {
        val recent = games.sortedByDescending { it.round }.take(5)
        if (recent.isEmpty()) return Lessons()
        var mid = 0.0
        var def = 0.0
        var atk = 0.0
        var discipline = false
        val avoid = LinkedHashSet<String>()
        val lostCount = HashMap<String, Int>()
        for ((i, g) in recent.withIndex()) {
            val w = listOf(1.0, 0.75, 0.55, 0.4, 0.3)[i]
            val bad = g.result != "V"
            val p = g.poss
            if (bad && p != null && p < 42) mid += w
            if (bad && ((g.oppShots ?: 0) >= 10 || (g.ga ?: 0) >= 2)) def += w
            if (bad && (g.gf == 0 || (g.shots != null && g.oppShots != null && g.shots * 10 < g.oppShots * 6))) atk += w
            if (i < 3 && (g.fouls ?: 0) >= 16) discipline = true
            val key = g.formation?.let { Formations.base(it) + "|" + (Osm.style(g.style) ?: "") }
            if (g.result == "D" && key != null) {
                lostCount[key] = (lostCount[key] ?: 0) + 1
                if (nextRival != null && same(g.rival, nextRival)) avoid.add(key)
            }
        }
        for ((k, n) in lostCount) if (n >= 2) avoid.add(k)
        fun need(x: Double): Int = if (x >= 1.6) 2 else if (x >= 0.7) 1 else 0
        val l = Lessons(need(mid), need(def), need(atk), discipline, avoid)
        val plan = ArrayList<String>()
        if (l.midNeed > 0) plan.add("Reforçar o meio-campo: formação com mais meias e meias em \"Manter posições\" para não perder a posse de novo.")
        if (l.defNeed > 0) plan.add("Proteger a defesa: mentalidade mais baixa, sem linha de impedimento arriscada e atacantes ajudando quando o rival for forte.")
        if (l.atkNeed > 0) plan.add("Mais presença na área: um atacante a mais ou \"Remate à vista\" quando a defesa rival for fraca.")
        if (l.discipline) plan.add("Disciplina: desarme Normal (as faltas recentes passaram de 16 por jogo).")
        for (k in avoid) plan.add("Não repetir ${k.replace("|", " • ")}" + (if (nextRival != null) " contra $nextRival" else "") + ": já perdeu com ela.")
        if (plan.isEmpty()) plan.add("Manter a linha que vem funcionando e ajustar só ao rival da vez.")
        return l.copy(plan = plan)
    }
}


/**
 * Contra-táticas publicadas pela comunidade do OSM (OSM Guide e fórum oficial) para a formação do rival.
 * Peso pequeno no simulador: é um ponto de partida que os resultados do próprio slot confirmam ou derrubam.
 */
object CounterBook {
    /** formação do rival -> (quando sou mais forte, quando sou mais fraco/parelho): formação e estilo. */
    private val BOOK = mapOf(
        "4-3-3 A" to Pair(Pair("4-3-3 B", "Jogar pelas alas"), Pair("4-5-1", "Remate à vista")),
        "4-4-2 B" to Pair(Pair("4-3-3 B", "Jogar pelas alas"), Pair("4-2-3-1", "Remate à vista")),
        "3-4-3 B" to Pair(Pair("4-4-2 A", null), Pair("6-3-1 A", "Contra-ataque")),
        "4-4-2 A" to Pair(Pair("5-3-2", "Contra-ataque"), Pair("5-3-2", "Contra-ataque"))
    )

    fun suggestion(rivalFormation: String?, edge: Int): Pair<String, String?>? {
        val rf = rivalFormation?.let { Formations.canonical(it) } ?: return null
        val e = BOOK[rf] ?: return null
        return if (edge >= 3) e.first else e.second
    }

    fun matches(rivalFormation: String?, edge: Int, formation: String, style: String?): Boolean {
        val s = suggestion(rivalFormation, edge) ?: return false
        val fOk = Formations.canonical(formation) == s.first || Formations.base(formation) == s.first
        val stOk = s.second == null || Osm.style(style) == s.second
        return fOk && stOk
    }
}

/**
 * Calibração das previsões com os resultados reais de cada slot: se o modelo vem prometendo mais pontos do que
 * o time faz (ou menos), corrige; e mede quantas vezes o resultado mais provável foi o que aconteceu.
 */
object Calib {
    private fun pts(r: String): Int = when (r) { "V" -> 3; "E" -> 1; else -> 0 }

    private fun graded(logs: List<JSONObject>): List<JSONObject> = logs.filter {
        !it.isNull("result") && it.optString("result") in setOf("V", "E", "D") && it.has("predPts")
    }.sortedByDescending { it.optInt("round") }.take(8)

    /** Ajuste no log dos meus gols esperados (entre -0.15 e +0.15); 0 com menos de 3 jogos previstos. */
    fun offset(logs: List<JSONObject>): Double {
        val g = graded(logs)
        if (g.size < 3) return 0.0
        val err = g.map { pts(it.optString("result")) - it.optDouble("predPts") }.average()
        return (err * 0.12).coerceIn(-0.15, 0.15)
    }

    /** (acertos, jogos): o resultado mais provável previsto foi o que aconteceu. */
    fun accuracy(logs: List<JSONObject>): Pair<Int, Int> {
        val g = graded(logs).filter { it.has("predW") }
        var hit = 0
        for (x in g) {
            val w = x.optInt("predW")
            val e = x.optInt("predE")
            val d = x.optInt("predD")
            val fav = if (w >= e && w >= d) "V" else if (d >= e) "D" else "E"
            if (fav == x.optString("result")) hit++
        }
        return Pair(hit, g.size)
    }
}
