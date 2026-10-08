package com.osmaicoach.collector

import kotlin.math.exp

object Formations {
    /** Formações do OSM com a variante (A/B) como aparece no jogo. */
    val OSM = listOf("4-3-3 A", "4-3-3 B", "4-4-2 A", "4-4-2 B", "4-2-3-1", "4-5-1", "5-3-2", "5-4-1 A")

    /** Formações vistas nas telas do jogo (rivais, análise, meu plantel): entram no catálogo. */
    private val seen = java.util.concurrent.CopyOnWriteArraySet<String>()

    private val RX = Regex("([3-6]-\\d-\\d(?:-\\d)?)\\s*([A-Da-d])?\\b")

    val ALL: List<String> get() = (OSM + seen).distinct()

    /** Bases (sem a letra) que o motor avalia. */
    val BASES: List<String> get() = ALL.map { base(it) }.distinct()

    fun base(f: String): String = f.trim().split(" ")[0]

    fun learn(name: String?): Boolean {
        val c = parse(name) ?: return false
        if (c in OSM || lines(c).isEmpty()) return false
        return seen.add(c)
    }

    fun seenList(): List<String> = seen.toList()

    /** "4-3-3b", "4-3-3 B", "433B" -> "4-3-3 B" (sem checar catálogo). */
    fun parse(s: String?): String? {
        val t = (s ?: "").trim()
        val compact = Regex("^([3-6])([1-6])([0-6])([0-6])?\\s*([A-Da-d])?$").find(t.replace("-", "").replace(" ", ""))
        val m = RX.find(t)
        val baseTxt: String
        val letter: String
        if (m != null) {
            baseTxt = m.groupValues[1]
            letter = m.groupValues[2].uppercase()
        } else if (compact != null && !t.contains("-")) {
            baseTxt = listOf(compact.groupValues[1], compact.groupValues[2], compact.groupValues[3], compact.groupValues[4]).filter { it.isNotEmpty() }.joinToString("-")
            letter = compact.groupValues[5].uppercase()
        } else return null
        if (lines(baseTxt).isEmpty()) return null
        return if (letter.isNotEmpty()) "$baseTxt $letter" else baseTxt
    }

    /** Nome exato do jogo: com a letra certa; sem letra e com variantes, a 1ª do catálogo. */
    fun canonical(s: String?): String? {
        val p = parse(s) ?: return null
        if (p in ALL) return p
        val v = variants(base(p))
        if (!p.contains(" ") && v.isNotEmpty()) return v[0]
        return if (p.contains(" ")) p else null
    }

    fun variants(base: String): List<String> = ALL.filter { base(it) == base }

    /**
     * Variante para o estilo escolhido: "A" para jogo pelas alas/contra-ataque (mais abertas), "B" para posse.
     */
    fun variant(base: String, style: String?): String {
        val v = variants(base)
        if (v.isEmpty()) return base
        if (v.size == 1) return v[0]
        val n = Txt.norm(style ?: "")
        val wantA = n.contains("alas") || n.contains("contra") || n.contains("long") || n.contains("bola")
        return v.firstOrNull { it.endsWith(if (wantA) " A" else " B") } ?: v[0]
    }

    /** "4-2-3-1 B" -> [4,2,3,1]; vazio se a soma não for 10. */
    fun lines(f: String): List<Int> {
        val nums = f.trim().split(" ")[0].split("-").mapNotNull { it.toIntOrNull() }
        return if (nums.isNotEmpty() && nums.sum() == 10) nums else emptyList()
    }
}

data class HistRow(
    val formation: String, val playStyle: String, val result: String?, val human: Boolean? = null, val home: Boolean? = null,
    val fouls: Int? = null, val possession: Int? = null
)

data class FormStat(val formation: String, val games: Int, val v: Int, val e: Int, val d: Int)

/** Estimativa simples de vitória pela diferença de força (logística) — não é promessa, é referência. */
object Forecast {
    fun winPercent(diff: Int, home: Boolean?): Int {
        val d = diff + (if (home == true) 3 else if (home == false) -3 else 0)
        val p = 1.0 / (1.0 + exp(-d / 14.0))
        return (p * 100).toInt().coerceIn(5, 95)
    }
}

object Learning {
    fun points(s: FormStat): Int = s.v * 3 + s.e - s.d * 3

    /** Resultado por estilo de jogo (reaproveita FormStat: o campo formation guarda o nome do estilo). */
    fun byStyle(history: List<HistRow>): List<FormStat> =
        history.filter { it.result != null && it.playStyle.isNotBlank() }.groupBy { it.playStyle }.map { (st, rows) ->
            FormStat(st, rows.size, rows.count { it.result == "V" }, rows.count { it.result == "E" }, rows.count { it.result == "D" })
        }.sortedByDescending { it.games }

    /** Resultado por contexto: contra humano/CPU e em casa/fora. */
    fun byContext(history: List<HistRow>): List<FormStat> {
        val done = history.filter { it.result != null }
        val groups = listOf(
            "Contra humano" to done.filter { it.human == true },
            "Contra CPU" to done.filter { it.human == false },
            "Em casa" to done.filter { it.home == true },
            "Fora" to done.filter { it.home == false }
        )
        return groups.filter { it.second.isNotEmpty() }.map { (name, rows) ->
            FormStat(name, rows.size, rows.count { it.result == "V" }, rows.count { it.result == "E" }, rows.count { it.result == "D" })
        }
    }

    /** Resultado das táticas já usadas, por formação. Só conta jogos com resultado conhecido. */
    fun stats(history: List<HistRow>): List<FormStat> =
        history.filter { it.result != null }.groupBy { it.formation }.map { (f, rows) ->
            FormStat(f, rows.size, rows.count { it.result == "V" }, rows.count { it.result == "E" }, rows.count { it.result == "D" })
        }.sortedByDescending { it.games }
}

object TacticEngine {
    data class Input(
        val players: List<PlayerEntity>,
        val myStrength: Int?,
        val rivalStrength: Int?,
        val rivalHuman: Boolean?,
        val rivalFormation: String?,
        val myDef: Int?,
        val rivalAtk: Int?,
        val referee: String?,
        val home: Boolean?,
        val history: List<HistRow>,
        val myAtk: Int? = null,
        val myMid: Int? = null,
        val rivalMid: Int? = null,
        val rivalDef: Int? = null,
        val fitness: Map<String, Pair<Int, Int>> = emptyMap()
    )

    private data class Sliders(val mentality: Int, val pressure: Int, val tempo: Int, val bucket: String)

    data class Result(
        val tactic: Tactic,
        val rows: List<List<PlayerEntity?>>,
        val diff: Int?,
        val ranking: List<Pair<String, Double>>
    )

    /** Desconto na força por cansaço (condição < 70) e moral baixa (< 50); sem leitura, sem desconto. */
    private fun penalty(p: PlayerEntity, fit: Map<String, Pair<Int, Int>>): Int {
        val f = fit[p.nameKey] ?: return 0
        val c = if (f.first < 70) (70 - f.first) / 5 else 0
        val m = if (f.second < 50) (50 - f.second) / 10 else 0
        return c + m
    }

    private fun eff(p: PlayerEntity, fit: Map<String, Pair<Int, Int>>): Int = (p.strength ?: 0) - penalty(p, fit)

    private fun pool(players: List<PlayerEntity>, cat: String, fit: Map<String, Pair<Int, Int>> = emptyMap()): List<PlayerEntity> =
        players.filter { it.cat == cat && it.strength != null }.sortedByDescending { eff(it, fit) }

    private fun side(p: PlayerEntity?): Int {
        val c = p?.posCode?.uppercase() ?: return 1
        return when (c) {
            "DE", "ME", "EE", "LE" -> 0
            "DD", "MD", "ED", "LD" -> 2
            else -> 1
        }
    }

    /** Melhor XI para a formação: linhas de trás para a frente (linha 0 = goleiro), esquerda → direita. */
    fun lineup(formation: String, players: List<PlayerEntity>, fit: Map<String, Pair<Int, Int>> = emptyMap()): Pair<List<List<PlayerEntity?>>, Int>? {
        val lines = Formations.lines(formation)
        if (lines.isEmpty()) return null
        val gk = pool(players, "GOL", fit)
        val def = pool(players, "DEF", fit)
        val mei = pool(players, "MEI", fit)
        val ata = pool(players, "ATA", fit)
        var di = 0
        var mi = 0
        var ai = 0
        val rows = ArrayList<List<PlayerEntity?>>()
        rows.add(listOf(gk.firstOrNull()))
        var sum = gk.firstOrNull()?.let { eff(it, fit) } ?: 0
        for ((i, n) in lines.withIndex()) {
            val pick: List<PlayerEntity?>
            if (i == 0) {
                pick = (0 until n).map { def.getOrNull(di + it) }
                di += n
            } else if (i == lines.size - 1) {
                pick = (0 until n).map { ata.getOrNull(ai + it) }
                ai += n
            } else {
                pick = (0 until n).map { mei.getOrNull(mi + it) }
                mi += n
            }
            sum += pick.map { if (it == null) 0 else eff(it, fit) }.sum()
            rows.add(pick.sortedBy { side(it) })
        }
        return Pair(rows, sum)
    }

    private fun gap(a: Int, b: Int): Int = if (a >= b) a - b else b - a

    private fun bias(f: String, diff: Int?, rivalFw: Int?, inp: Input): Double {
        val lines = Formations.lines(f)
        val d = lines.first()
        val k = lines.last()
        val m = 10 - d - k
        var b = 0.0
        if (diff != null) {
            if (diff >= 15) {
                b += (k - 2) * 6.0
                if (d < 4) b -= 8.0
                if (k > 3) b -= 8.0
            } else if (diff >= 5) {
                b += (k - 2) * 3.0
                if (d < 4) b -= 6.0
                if (k > 3) b -= 8.0
            } else if (diff >= -4) {
                // parelho: formas equilibradas, sempre com 4 defensores
                if (f == "4-3-3" || f == "4-4-2" || f == "4-2-3-1") b += 3.0
                if (d != 4) b -= 8.0
                if (k > 3) b -= 8.0
            } else if (diff >= -14) {
                b += (d - 4) * 4.0 - (k - 2) * 3.0
                if (d > 5) b -= 10.0
            } else {
                b += (d - 4) * 6.0 - (k - 2) * 4.0
                if (d > 5) b -= 10.0
            }
        }
        // Confronto por setor (quando os setores do rival foram lidos).
        if (inp.myMid != null && inp.rivalMid != null) {
            val midEdge = inp.myMid - inp.rivalMid
            if (midEdge >= 4) b += (m - 3) * 1.5 else if (midEdge <= -4) b -= (m - 3) * 1.0
        }
        if (inp.myAtk != null && inp.rivalDef != null) {
            val atkEdge = inp.myAtk - inp.rivalDef
            if (atkEdge >= 4) b += (k - 2) * 1.5
        }
        if (inp.rivalAtk != null && inp.myDef != null) {
            val defRisk = inp.rivalAtk - inp.myDef
            if (defRisk >= 4) b += (d - 4) * 2.0 else if (defRisk <= -4) b += (k - 2) * 1.0
        }
        // Formação do rival: contra 3 atacantes domina o meio; contra 1 atacante dá para atacar mais.
        if (rivalFw != null) {
            if (rivalFw >= 3 && (diff ?: 0) < 5) b += (m - 3) * 1.5
            if (rivalFw <= 1 && (diff ?: 0) > -5) b += (k - 1) * 1.5
        }
        val h = inp.history.filter { Formations.base(it.formation) == f && it.result != null }
        if (h.isNotEmpty()) {
            var pts = 0
            for (r in h) {
                if (r.result == "V") pts += 3 else if (r.result == "D") pts -= 3
            }
            b += pts.coerceIn(-9, 9) * (minOf(h.size, 3) / 3.0)
        }
        return b
    }

    fun recommend(inp: Input): Result? {
        val diff = if (inp.myStrength != null && inp.rivalStrength != null) inp.myStrength - inp.rivalStrength else null
        val rivalFw = inp.rivalFormation?.let { Formations.lines(it).lastOrNull() }
        val scored = ArrayList<Triple<String, List<List<PlayerEntity?>>, Double>>()
        for (f in Formations.BASES) {
            val l = lineup(f, inp.players, inp.fitness) ?: continue
            scored.add(Triple(f, l.first, l.second + bias(f, diff, rivalFw, inp)))
        }
        if (scored.isEmpty()) return null
        scored.sortByDescending { it.third }
        val best = scored[0]
        val formation = best.first
        val lines = Formations.lines(formation)
        val notes = ArrayList<String>()

        // Sliders pelo confronto de forças.
        val sl: Sliders = if (diff == null) Sliders(55, 50, 55, "força do rival desconhecida")
        else if (diff >= 15) Sliders(80, 70, 75, "rival bem mais fraco")
        else if (diff >= 5) Sliders(68, 62, 65, "rival mais fraco")
        else if (diff >= -4) Sliders(55, 50, 55, "confronto parelho")
        else if (diff >= -14) Sliders(42, 40, 55, "rival mais forte")
        else Sliders(30, 30, 60, "rival bem mais forte")
        var mentality = sl.mentality
        val pressure = sl.pressure
        val tempo = sl.tempo
        val bucket = sl.bucket
        if (inp.home == true) mentality += 3
        if (inp.home == false) mentality -= 3
        mentality = mentality.coerceIn(0, 100)

        val ata = pool(inp.players, "ATA").take(3)
        val wingers = ata.count { it.posCode?.uppercase() in setOf("EE", "ED") }
        val defaultStyle = if (diff != null && diff >= 15 && wingers >= 2) "Jogar pelas alas"
        else if (diff != null && diff >= 15) "Remate à vista"
        else if (diff != null && diff <= -5) "Contra-ataque"
        else "Jogo de passes"
        // Aprendizado: se outro estilo já deu resultado claramente melhor (2+ jogos), usa o que funcionou.
        val styleStats = Learning.byStyle(inp.history)
        val allowedStyles = listOf("Jogo de passes", "Jogar pelas alas", "Remate à vista", "Contra-ataque")
        val curPts = styleStats.firstOrNull { it.formation == defaultStyle }?.let { Learning.points(it) } ?: 0
        val bestStyle = styleStats.filter { it.games >= 2 && it.formation in allowedStyles }.maxByOrNull { Learning.points(it) }
        var playStyle = defaultStyle
        var styleNote: String? = null
        if (bestStyle != null && bestStyle.formation != defaultStyle && Learning.points(bestStyle) >= curPts + 4) {
            playStyle = bestStyle.formation
            styleNote = "Estilo ${bestStyle.formation} escolhido pelo histórico: ${bestStyle.v}V ${bestStyle.e}E ${bestStyle.d}D em ${bestStyle.games} jogos."
        }

        val tackle = when {
            inp.referee == "Rigoroso" -> "Normal"
            inp.referee == "Brando" && diff != null && diff >= 5 -> "Agressivo"
            else -> "Normal"
        }
        // Disciplina: se nos últimos jogos o time cometeu muitas faltas, desarme Normal mesmo contra rival fraco.
        val foulRows = inp.history.filter { it.fouls != null }.takeLast(3)
        var tackleFinal = tackle
        var disciplineNote: String? = null
        if (foulRows.isNotEmpty()) {
            var total = 0
            for (r in foulRows) total += r.fouls ?: 0
            val avg = total / foulRows.size
            if (avg >= 16 && tackle == "Agressivo") {
                tackleFinal = "Normal"
                disciplineNote = "Média de $avg faltas nos últimos ${foulRows.size} jogos: desarme Normal para evitar cartões."
            }
        }
        val marking = if (inp.rivalAtk != null && inp.myDef != null && inp.rivalAtk - inp.myDef >= 5) "Individual" else "À zona"
        val offside = if (inp.rivalAtk != null && inp.myDef != null && inp.myDef - inp.rivalAtk >= 5) "Sim" else "Não"

        val roles: Pair<String, String> = if (diff != null && diff >= 15) Pair("Atacar apenas", "Pressionar na frente")
        else if (diff != null && diff >= 5) Pair("Atacar apenas", "Manter posição")
        else if (diff != null && diff <= -5) Pair("Ajudar a defesa", "Ajudar a defesa")
        else Pair("Atacar apenas", "Manter posição")
        val advAttack = roles.first
        val advMid = roles.second
        val advDef = "Defender atrás"

        // Explicação com números.
        if (diff != null) {
            notes.add("Força $diff pontos ${if (diff >= 0) "acima" else "abaixo"} do rival (${inp.myStrength} vs ${inp.rivalStrength}): $bucket.")
        } else {
            notes.add("Força do rival ainda não lida: tática equilibrada. Leia o pré-jogo para eu ajustar.")
        }
        notes.add("Formação $formation: ${lines.first()} defensores, ${lines.last()} atacante(s); melhor XI soma ${best.second.flatten().map { it?.strength ?: 0 }.sum()} de força.")
        val second = scored.getOrNull(1)
        if (second != null) notes.add("Segunda opção: ${second.first} (${"%.0f".format(best.third - second.third)} pontos atrás).")
        if (rivalFw != null && rivalFw >= 3) notes.add("Rival joga com $rivalFw atacantes: priorizei controle do meio-campo.")
        if (inp.myMid != null && inp.rivalMid != null) {
            notes.add("Meio-campo: ${inp.myMid} vs ${inp.rivalMid} do rival (${if (inp.myMid >= inp.rivalMid) "vantagem" else "desvantagem"} de ${gap(inp.myMid, inp.rivalMid)}).")
        }
        if (inp.myAtk != null && inp.rivalDef != null) {
            notes.add("Ataque ${inp.myAtk} vs defesa rival ${inp.rivalDef} (${if (inp.myAtk >= inp.rivalDef) "+" else "-"}${gap(inp.myAtk, inp.rivalDef)}).")
        }
        if (inp.referee == "Rigoroso") notes.add("Árbitro rigoroso: desarme em Normal para evitar cartões.")
        if (inp.referee == "Brando" && tackle == "Agressivo") notes.add("Árbitro brando e rival fraco: desarme Agressivo é seguro.")
        if (marking == "Individual") notes.add("Ataque do rival (${inp.rivalAtk}) supera sua defesa (${inp.myDef}): marcação homem a homem.")
        if (inp.fitness.isNotEmpty()) {
            val rawKeys = lineup(formation, inp.players)?.first?.flatten()?.filterNotNull()?.map { it.nameKey }?.toSet() ?: emptySet()
            val effKeys = best.second.flatten().filterNotNull().map { it.nameKey }.toSet()
            for (p in inp.players.filter { it.nameKey in rawKeys && it.nameKey !in effKeys }.take(2)) {
                val ft = inp.fitness[p.nameKey]
                notes.add("Rotação: ${p.name} fora do XI (condição ${ft?.first ?: "?"}%, moral ${ft?.second ?: "?"}%).")
            }
        }
        if (styleNote != null) notes.add(styleNote)
        if (disciplineNote != null) notes.add(disciplineNote)
        val stat = Learning.stats(inp.history).firstOrNull { it.formation == formation }
        if (stat != null) notes.add("Histórico de $formation: ${stat.v}V ${stat.e}E ${stat.d}D em ${stat.games} jogo(s) registrados.")
        if (inp.rivalHuman == true) notes.add("Rival humano: ele pode mudar a tática; confira o relatório antes do jogo.")

        val tactic = Tactic(
            formation = Formations.variant(formation, playStyle), playStyle = playStyle, pressure = pressure, mentality = mentality, tempo = tempo,
            marking = marking, offside = offside, tackle = tackleFinal, advAttack = advAttack, advMid = advMid, advDef = advDef,
            notes = notes
        )
        return Result(tactic, best.second, diff, scored.take(4).map { Pair(it.first, it.third) })
    }
}

private data class BuyCand(val l: ListingEntity, val price: Double, val ref: PlayerEntity?, val gain: Int, val score: Double)

object MarketEngine {
    data class SellItem(val name: String, val cat: String, val strength: Int?, val valueM: Double?, val reason: String)
    data class BuyItem(val name: String, val cat: String, val strength: Int, val priceM: Double, val gain: Int, val replaces: String?, val reason: String)
    data class TrainItem(val name: String, val trainer: String, val reason: String)
    data class RadarItem(val name: String, val cat: String, val strength: Int, val priceM: Double, val gain: Int, val affordable: Boolean)
    data class Plan(
        val sell: List<SellItem>,
        val buy: List<BuyItem>,
        val train: List<TrainItem>,
        val steps: List<String>,
        val summary: String,
        val cashM: Double?,
        val budgetM: Double?,
        val radar: List<RadarItem> = emptyList(),
        val trainingActive: Int = 0
    )

    private val CORE = mapOf("GOL" to 1, "DEF" to 4, "MEI" to 4, "ATA" to 3)
    private val TRAINER = mapOf("ATA" to "avançados", "MEI" to "médios", "DEF" to "defesas", "GOL" to "guarda-redes")

    private fun fmt(m: Double?): String = if (m == null) NI else if (m >= 1.0) "%.1fM".format(m).replace('.', ',') else "%.0fK".format(m * 1000)

    fun plan(players: List<PlayerEntity>, listings: List<ListingEntity>, cashM: Double?, sellSlots: Int): Plan {
        val cats = MarketPlanner.TARGET.keys.toList()
        val byCat = cats.associateWith { c -> players.filter { it.cat == c && it.strength != null }.sortedByDescending { it.strength } }
        val sells = ArrayList<SellItem>()
        val soldKeys = HashSet<String>()

        // 1) Excesso acima da meta de cada posição (os mais fracos).
        for (c in cats) {
            val list = byCat[c] ?: continue
            val extra = list.size - (MarketPlanner.TARGET[c] ?: 0)
            if (extra > 0) {
                for (p in list.takeLast(extra)) {
                    if (sells.size >= sellSlots) break
                    sells.add(SellItem(p.name, c, p.strength, Money.parse(p.valueText), "excesso de $c (meta ${MarketPlanner.TARGET[c]}): é o mais fraco do setor"))
                    soldKeys.add(p.nameKey)
                }
            }
        }
        val proceeds = sells.map { it.valueM ?: 0.0 }.sum()
        var budget = (cashM ?: 0.0) + proceeds

        // 2) Compras e trocas por ganho no time titular.
        val have = cats.associateWith { (byCat[it]?.size ?: 0) - sells.count { s -> s.cat == it } }.toMutableMap()
        val buys = ArrayList<BuyItem>()
        val cands = listings.filter { it.cat in cats && it.strength != null && Money.parse(it.priceText) != null }
        val ranked = cands.mapNotNull { l ->
            val c = l.cat ?: return@mapNotNull null
            val list = byCat[c] ?: emptyList()
            val core = CORE[c] ?: 3
            val ref = list.getOrNull(core - 1)
            val gain = l.strength!! - (ref?.strength ?: (l.strength - 5))
            if (gain < 2) return@mapNotNull null
            val price = Money.parse(l.priceText) ?: return@mapNotNull null
            val ageBonus = (28 - (l.age ?: 28)).coerceIn(-6, 6)
            BuyCand(l, price, ref, gain, gain * 10.0 + ageBonus - price * 0.4)
        }.sortedByDescending { it.score }

        var shortfall: Double? = null
        for (cd in ranked) {
            if (buys.size >= 4) break
            val c = cd.l.cat ?: continue
            val missing = (MarketPlanner.TARGET[c] ?: 0) - (have[c] ?: 0)
            if (missing > 0) {
                if (cd.price <= budget) {
                    buys.add(BuyItem(cd.l.name, c, cd.l.strength!!, cd.price, cd.gain, null, "preenche vaga de $c e supera o titular mais fraco (${cd.ref?.strength ?: "?"})"))
                    budget -= cd.price
                    have[c] = (have[c] ?: 0) + 1
                } else if (shortfall == null) shortfall = cd.price - budget
            } else {
                val out = (byCat[c] ?: emptyList()).lastOrNull { it.nameKey !in soldKeys }
                if (out == null || sells.size >= sellSlots) continue
                val vOut = Money.parse(out.valueText) ?: 0.0
                val net = cd.price - vOut
                if (net <= budget) {
                    sells.add(SellItem(out.name, c, out.strength, Money.parse(out.valueText), "sai para a entrada de ${cd.l.name} (+${cd.gain} de força no setor)"))
                    soldKeys.add(out.nameKey)
                    buys.add(BuyItem(cd.l.name, c, cd.l.strength!!, cd.price, cd.gain, out.name, "troca por ${out.name}: +${cd.gain} sobre o titular mais fraco"))
                    budget -= net
                } else if (shortfall == null) shortfall = net - budget
            }
        }

        // 3) Treino: até 5 treinos ao mesmo tempo (4 treinadores de posição + 1 universal).
        val trains = ArrayList<TrainItem>()
        val infos = ArrayList<String>()
        val activeAll = players.filter { it.training == true }
        val busyCats = HashSet<String>()
        var extraTrainees = 0
        for (c in cats) {
            val busy = (byCat[c] ?: emptyList()).filter { it.training == true }
            if (busy.isNotEmpty()) {
                busyCats.add(c)
                infos.add("Treinador de ${TRAINER[c]} ocupado com ${busy[0].name}.")
                for (extra in busy.drop(1)) {
                    extraTrainees++
                    infos.add("Treinador universal ocupado com ${extra.name}.")
                }
            }
        }
        val universalBusy = extraTrainees > 0 || activeAll.size >= 5
        if (universalBusy && extraTrainees == 0) infos.add("Treinador universal ocupado.")
        val picked = HashSet<String>()
        fun potential(p: PlayerEntity): Double = (p.strength ?: 0) + maxOf(0, 28 - (p.age ?: 28)) * 0.8
        for (c in cats) {
            if (c in busyCats) continue
            val list = byCat[c] ?: continue
            val core = list.take(CORE[c] ?: 3).filter { it.nameKey !in soldKeys }
            val pick = core.maxByOrNull { potential(it) } ?: continue
            picked.add(pick.nameKey)
            trains.add(TrainItem(pick.name, TRAINER[c] ?: "universal", "titular de $c com melhor potencial (força ${pick.strength}, ${pick.age ?: "?"} anos)"))
        }
        if (!universalBusy && activeAll.size < 5) {
            val pool = ArrayList<PlayerEntity>()
            for (c in cats) {
                val list = byCat[c] ?: continue
                pool.addAll(list.take(CORE[c] ?: 3).filter { it.nameKey !in soldKeys && it.nameKey !in picked && it.training != true })
            }
            val u = pool.maxByOrNull { potential(it) }
            if (u != null) {
                trains.add(TrainItem(u.name, "universal", "melhor potencial entre os titulares livres (${u.cat} ${u.strength}, ${u.age ?: "?"} anos)"))
            }
        }
        infos.add(0, "Treinos ocupados: ${minOf(activeAll.size, 5)} de 5.")

        val steps = ArrayList<String>()
        for (s in sells) steps.add("Vender ${s.name} (${s.cat} ${s.strength ?: "?"}, ≈ ${fmt(s.valueM)}): ${s.reason}.")
        for (b in buys) steps.add("Comprar ${b.name} (${b.cat} ${b.strength}, ${fmt(b.priceM)}): ${b.reason}.")
        for (t in trains) steps.add("Treinar ${t.name} com o " + (if (t.trainer == "universal") "treinador universal" else "treinador de ${t.trainer}") + ": ${t.reason}.")
        steps.addAll(infos)
        if (buys.isEmpty()) {
            steps.add(
                if (shortfall != null) "Nenhuma compra cabe no caixa agora: faltam ≈ ${fmt(shortfall)} para a melhor melhoria disponível. Venda os excedentes primeiro."
                else "Nenhuma melhoria clara no mercado lido. Role mais a Lista de transferências para eu ver outros jogadores."
            )
        }
        val radar = ArrayList<RadarItem>()
        val totalBudget = (cashM ?: 0.0) + proceeds
        for (c in cats) {
            val top = ranked.firstOrNull { it.l.cat == c } ?: continue
            radar.add(RadarItem(top.l.name, c, top.l.strength ?: 0, top.price, top.gain, top.price <= totalBudget))
        }
        val summary = "Caixa ${fmt(cashM)} • vendas previstas ≈ ${fmt(proceeds)} • ${sells.size} venda(s), ${buys.size} compra(s), ${trains.size} treino(s)."
        return Plan(sells, buys, trains, steps, summary, cashM, budget, radar, minOf(activeAll.size, 5))
    }
}
