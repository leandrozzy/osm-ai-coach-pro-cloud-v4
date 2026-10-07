package com.osmaicoach.collector

object Formations {
    val ALL = listOf(
        "4-3-3", "4-4-2", "4-2-3-1", "4-5-1", "5-3-2", "3-5-2", "3-4-3", "5-4-1",
        "4-1-4-1", "4-3-2-1", "3-3-2-2", "4-1-3-2"
    )

    /** "4-2-3-1 B" -> [4,2,3,1]; vazio se a soma não for 10. */
    fun lines(f: String): List<Int> {
        val nums = f.trim().split(" ")[0].split("-").mapNotNull { it.toIntOrNull() }
        return if (nums.isNotEmpty() && nums.sum() == 10) nums else emptyList()
    }
}

data class HistRow(val formation: String, val playStyle: String, val result: String?)

data class FormStat(val formation: String, val games: Int, val v: Int, val e: Int, val d: Int)

object Learning {
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
        val history: List<HistRow>
    )

    data class Result(
        val tactic: Tactic,
        val rows: List<List<PlayerEntity?>>,
        val diff: Int?,
        val ranking: List<Pair<String, Double>>
    )

    private fun pool(players: List<PlayerEntity>, cat: String): List<PlayerEntity> =
        players.filter { it.cat == cat && it.strength != null }.sortedByDescending { it.strength }

    private fun side(p: PlayerEntity?): Int {
        val c = p?.posCode?.uppercase() ?: return 1
        return when (c) {
            "DE", "ME", "EE", "LE" -> 0
            "DD", "MD", "ED", "LD" -> 2
            else -> 1
        }
    }

    /** Melhor XI para a formação: linhas de trás para a frente (linha 0 = goleiro), esquerda → direita. */
    fun lineup(formation: String, players: List<PlayerEntity>): Pair<List<List<PlayerEntity?>>, Int>? {
        val lines = Formations.lines(formation)
        if (lines.isEmpty()) return null
        val gk = pool(players, "GOL")
        val def = pool(players, "DEF")
        val mei = pool(players, "MEI")
        val ata = pool(players, "ATA")
        var di = 0
        var mi = 0
        var ai = 0
        val rows = ArrayList<List<PlayerEntity?>>()
        rows.add(listOf(gk.firstOrNull()))
        var sum = gk.firstOrNull()?.strength ?: 0
        for ((i, n) in lines.withIndex()) {
            val chosen: List<PlayerEntity?>
            when (i) {
                0 -> {
                    chosen = (0 until n).map { def.getOrNull(di + it) }
                    di += n
                }
                lines.size - 1 -> {
                    chosen = (0 until n).map { ata.getOrNull(ai + it) }
                    ai += n
                }
                else -> {
                    chosen = (0 until n).map { mei.getOrNull(mi + it) }
                    mi += n
                }
            }
            sum += chosen.sumOf { it?.strength ?: 0 }
            rows.add(chosen.sortedBy { side(it) })
        }
        return Pair(rows, sum)
    }

    private fun bias(f: String, diff: Int?, rivalFw: Int?, history: List<HistRow>): Double {
        val lines = Formations.lines(f)
        val d = lines.first()
        val k = lines.last()
        var b = 0.0
        when {
            diff == null -> b += 0.0
            diff >= 15 -> {
                b += (k - 2) * 6.0
                if (d < 4) b -= 8.0
                if (k > 3) b -= 8.0
            }
            diff >= 5 -> {
                b += (k - 2) * 3.0
                if (d < 4) b -= 6.0
                if (k > 3) b -= 8.0
            }
            diff >= -4 -> {
                if (f == "4-2-3-1" || f == "4-4-2") b += 2.0
                if (k > 3) b -= 6.0
                if (d < 4) b -= 3.0
            }
            diff >= -14 -> b += (d - 4) * 4.0 - (k - 2) * 3.0
            else -> b += (d - 4) * 6.0 - (k - 2) * 4.0
        }
        if (rivalFw != null && rivalFw >= 3 && (diff ?: 0) < 15) b += (d - 4) * 2.0
        val h = history.filter { it.formation == f && it.result != null }
        if (h.isNotEmpty()) {
            val pts = h.sumOf { r ->
                when (r.result) {
                    "V" -> 3
                    "E" -> 0
                    else -> -3
                }
            }
            b += pts.coerceIn(-9, 9) * (minOf(h.size, 3) / 3.0)
        }
        return b
    }

    fun recommend(inp: Input): Result? {
        val diff = if (inp.myStrength != null && inp.rivalStrength != null) inp.myStrength - inp.rivalStrength else null
        val rivalFw = inp.rivalFormation?.let { Formations.lines(it).lastOrNull() }
        val scored = ArrayList<Triple<String, List<List<PlayerEntity?>>, Double>>()
        for (f in Formations.ALL) {
            val l = lineup(f, inp.players) ?: continue
            scored.add(Triple(f, l.first, l.second + bias(f, diff, rivalFw, inp.history)))
        }
        if (scored.isEmpty()) return null
        scored.sortByDescending { it.third }
        val best = scored[0]
        val formation = best.first
        val lines = Formations.lines(formation)
        val notes = ArrayList<String>()

        // Sliders pelo confronto de forças.
        var mentality: Int
        var pressure: Int
        var tempo: Int
        val bucket: String
        when {
            diff == null -> { mentality = 55; pressure = 50; tempo = 55; bucket = "força do rival desconhecida" }
            diff >= 15 -> { mentality = 80; pressure = 70; tempo = 75; bucket = "rival bem mais fraco" }
            diff >= 5 -> { mentality = 68; pressure = 62; tempo = 65; bucket = "rival mais fraco" }
            diff >= -4 -> { mentality = 55; pressure = 50; tempo = 55; bucket = "confronto parelho" }
            diff >= -14 -> { mentality = 42; pressure = 40; tempo = 55; bucket = "rival mais forte" }
            else -> { mentality = 30; pressure = 30; tempo = 60; bucket = "rival bem mais forte" }
        }
        if (inp.home == true) mentality += 3
        if (inp.home == false) mentality -= 3
        mentality = mentality.coerceIn(0, 100)

        val ata = pool(inp.players, "ATA").take(3)
        val wingers = ata.count { it.posCode?.uppercase() in setOf("EE", "ED") }
        val playStyle = when {
            diff != null && diff >= 15 && wingers >= 2 -> "Jogar pelas alas"
            diff != null && diff >= 15 -> "Remate à vista"
            else -> "Jogo de passe"
        }

        val tackle = when {
            inp.referee == "Rigoroso" -> "Normal"
            inp.referee == "Brando" && diff != null && diff >= 5 -> "Agressivo"
            else -> "Normal"
        }
        val marking = if (inp.rivalAtk != null && inp.myDef != null && inp.rivalAtk - inp.myDef >= 5) "Homem a homem" else "À zona"
        val offside = if (inp.rivalAtk != null && inp.myDef != null && inp.myDef - inp.rivalAtk >= 5) "Sim" else "Não"

        val advAttack: String
        val advMid: String
        val advDef = "Defender atrás"
        when {
            diff != null && diff >= 15 -> { advAttack = "Atacar apenas"; advMid = "Pressionar à frente" }
            diff != null && diff >= 5 -> { advAttack = "Atacar apenas"; advMid = "Manter posições" }
            diff != null && diff <= -5 -> { advAttack = "Ajudar a defender"; advMid = "Ajudar a defesa" }
            else -> { advAttack = "Atacar apenas"; advMid = "Manter posições" }
        }

        // Explicação com números.
        if (diff != null) {
            notes.add("Força $diff pontos ${if (diff >= 0) "acima" else "abaixo"} do rival (${inp.myStrength} vs ${inp.rivalStrength}): $bucket.")
        } else {
            notes.add("Força do rival ainda não lida: tática equilibrada. Leia o pré-jogo para eu ajustar.")
        }
        notes.add("Formação $formation: ${lines.first()} defensores, ${lines.last()} atacante(s); melhor XI soma ${best.second.flatten().sumOf { it?.strength ?: 0 }} de força.")
        val second = scored.getOrNull(1)
        if (second != null) notes.add("Segunda opção: ${second.first} (${"%.0f".format(best.third - second.third)} pontos atrás).")
        if (rivalFw != null && rivalFw >= 3) notes.add("Rival joga com $rivalFw atacantes: defesa reforçada na escolha.")
        if (inp.referee == "Rigoroso") notes.add("Árbitro rigoroso: desarme em Normal para evitar cartões.")
        if (inp.referee == "Brando" && tackle == "Agressivo") notes.add("Árbitro brando e rival fraco: desarme Agressivo é seguro.")
        if (marking == "Homem a homem") notes.add("Ataque do rival (${inp.rivalAtk}) supera sua defesa (${inp.myDef}): marcação homem a homem.")
        val stat = Learning.stats(inp.history).firstOrNull { it.formation == formation }
        if (stat != null) notes.add("Histórico de $formation: ${stat.v}V ${stat.e}E ${stat.d}D em ${stat.games} jogo(s) registrados.")
        if (inp.rivalHuman == true) notes.add("Rival humano: ele pode mudar a tática; confira o relatório antes do jogo.")

        val tactic = Tactic(
            formation = formation, playStyle = playStyle, pressure = pressure, mentality = mentality, tempo = tempo,
            marking = marking, offside = offside, tackle = tackle, advAttack = advAttack, advMid = advMid, advDef = advDef,
            notes = notes
        )
        return Result(tactic, best.second, diff, scored.take(4).map { Pair(it.first, it.third) })
    }
}

object MarketEngine {
    data class SellItem(val name: String, val cat: String, val strength: Int?, val valueM: Double?, val reason: String)
    data class BuyItem(val name: String, val cat: String, val strength: Int, val priceM: Double, val gain: Int, val replaces: String?, val reason: String)
    data class TrainItem(val name: String, val trainer: String, val reason: String)
    data class Plan(
        val sell: List<SellItem>,
        val buy: List<BuyItem>,
        val train: List<TrainItem>,
        val steps: List<String>,
        val summary: String,
        val cashM: Double?,
        val budgetM: Double?
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
        val proceeds = sells.sumOf { it.valueM ?: 0.0 }
        var budget = (cashM ?: 0.0) + proceeds

        // 2) Compras e trocas por ganho no time titular.
        val have = cats.associateWith { (byCat[it]?.size ?: 0) - sells.count { s -> s.cat == it } }.toMutableMap()
        val buys = ArrayList<BuyItem>()
        val cands = listings.filter { it.cat in cats && it.strength != null && Money.parse(it.priceText) != null }
        data class Cand(val l: ListingEntity, val price: Double, val ref: PlayerEntity?, val gain: Int, val score: Double)
        val ranked = cands.mapNotNull { l ->
            val c = l.cat ?: return@mapNotNull null
            val list = byCat[c] ?: emptyList()
            val core = CORE[c] ?: 3
            val ref = list.getOrNull(core - 1)
            val gain = l.strength!! - (ref?.strength ?: (l.strength - 5))
            if (gain < 2) return@mapNotNull null
            val price = Money.parse(l.priceText) ?: return@mapNotNull null
            val ageBonus = (28 - (l.age ?: 28)).coerceIn(-6, 6)
            Cand(l, price, ref, gain, gain * 10.0 + ageBonus - price * 0.4)
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

        // 3) Treino: um jogador por treinador, o titular com maior potencial (força + juventude).
        val trains = ArrayList<TrainItem>()
        val infos = ArrayList<String>()
        for (c in cats) {
            val list = byCat[c] ?: continue
            val busy = list.firstOrNull { it.training == true }
            if (busy != null) {
                infos.add("Treinador de ${TRAINER[c]} ocupado com ${busy.name}.")
                continue
            }
            val core = list.take(CORE[c] ?: 3).filter { it.nameKey !in soldKeys }
            val pick = core.maxByOrNull { (it.strength ?: 0) + maxOf(0, 28 - (it.age ?: 28)) * 0.8 } ?: continue
            trains.add(TrainItem(pick.name, TRAINER[c] ?: "universal", "titular de $c com melhor potencial (força ${pick.strength}, ${pick.age ?: "?"} anos)"))
        }

        val steps = ArrayList<String>()
        for (s in sells) steps.add("Vender ${s.name} (${s.cat} ${s.strength ?: "?"}, ≈ ${fmt(s.valueM)}): ${s.reason}.")
        for (b in buys) steps.add("Comprar ${b.name} (${b.cat} ${b.strength}, ${fmt(b.priceM)}): ${b.reason}.")
        for (t in trains) steps.add("Treinar ${t.name} com o treinador de ${t.trainer}: ${t.reason}.")
        steps.addAll(infos)
        if (buys.isEmpty()) {
            steps.add(
                if (shortfall != null) "Nenhuma compra cabe no caixa agora: faltam ≈ ${fmt(shortfall)} para a melhor melhoria disponível. Venda os excedentes primeiro."
                else "Nenhuma melhoria clara no mercado lido. Role mais a Lista de transferências para eu ver outros jogadores."
            )
        }
        val summary = "Caixa ${fmt(cashM)} • vendas previstas ≈ ${fmt(proceeds)} • ${sells.size} venda(s), ${buys.size} compra(s), ${trains.size} treino(s)."
        return Plan(sells, buys, trains, steps, summary, cashM, budget)
    }
}
