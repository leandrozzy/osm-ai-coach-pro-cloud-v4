package com.osmaicoach.collector

import kotlin.math.roundToInt

/**
 * "Dicas primeiro": o que fazer agora em cada slot para evoluir rápido, juntando os dados lidos do slot com o que
 * a comunidade do OSM confirma que funciona (osmhelper.com, osmguide.com e o fórum oficial). Cada dica diz o
 * que fazer, com nomes e números, e por quê.
 */
object Playbook {
    data class Tip(val icon: String, val title: String, val why: String, val priority: Int, val tab: Int)

    data class Input(
        val players: List<PlayerEntity>,
        val xi: Set<String>,
        val cashM: Double?,
        val listedUsed: Int?,
        val capLevel: Int?,
        val pitchLevel: Int?,
        val trainLevel: Int?,
        val stadiumBuilding: Boolean,
        val round: Int?,
        val roundTotal: Int?,
        val seats: List<WorkSeat>,
        val matchAt: Long?,
        val now: Long,
        /** Eventos do OSM ativos agora. */
        val events: Set<OsmEvents.Kind> = emptySet(),
        /** Teto de venda da liga (múltiplo do valor), lido na tela; padrão 2,5. */
        val ceiling: Double = MAX_PRICE,
        /** Quanto a CPU pagou de verdade nas suas vendas (múltiplo do valor, nº de vendas). */
        val paid: Pair<Double, Int>? = null,
        /** Ganho de força por dia de treino, por faixa de idade (aprendido). */
        val trainRates: Map<String, Pair<Double, Int>> = emptyMap(),
        /** Patrocínio lido: jogos restantes (null = não lido) e se a tela pedia para escolher/renovar. */
        val sponsorGames: Int? = null,
        val sponsorRenew: Boolean = false,
        val sponsorReadAt: Long? = null,
        /** Nacionalidade por jogador (nameKey -> país), quando lida. */
        val nations: Map<String, String> = emptyMap()
    )

    const val MAX_LISTED = 4
    /** Teto de venda do OSM: 2,5× o valor (algumas ligas testaram 2×; o jogo mostra o máximo ao listar). */
    const val MAX_PRICE = 2.5
    private val MIN_POS = linkedMapOf("GOL" to 2, "DEF" to 4, "MEI" to 4, "ATA" to 3)
    private val CAT_LABEL = mapOf("GOL" to "goleiros", "DEF" to "defensores", "MEI" to "meias", "ATA" to "atacantes")
    private val ROLE_CAT = mapOf("ATA" to "ATA", "MEI" to "MEI", "DEF" to "DEF", "GOL" to "GOL")

    /** % do preço máximo para vender rápido no meio/fim da temporada (tabela do osmguide). */
    fun quickShare(valueM: Double): Double = when {
        valueM <= 2 -> 1.0
        valueM <= 6 -> 0.9
        valueM <= 12 -> 0.8
        valueM <= 18 -> 0.7
        else -> 0.6
    }

    /**
     * (preço máximo, preço para vender rápido) em milhões. No início da competição a CPU paga o máximo; com
     * vendas suas registradas, o "rápido" é o que a CPU pagou de verdade na sua liga.
     */
    fun prices(valueM: Double, early: Boolean, ceiling: Double = MAX_PRICE, paid: Double? = null): Pair<Double, Double> {
        val max = valueM * ceiling
        val quick = when {
            early -> max
            paid != null -> minOf(max, valueM * paid)
            else -> max * quickShare(valueM)
        }
        return Pair(max, quick)
    }

    fun money(m: Double): String = if (m >= 1.0) "%.1fM".format(m).replace('.', ',').replace(",0M", "M") else "${(m * 1000).roundToInt()}K"

    fun tips(i: Input): List<Tip> {
        val out = ArrayList<Tip>()
        val mine = i.players.filter { it.owner == "MY" }
        if (mine.isEmpty()) {
            out.add(Tip("📋", "Leia o Plantel do seu time", "Sem o elenco eu não sei quem vender, comprar ou treinar. Abra o Plantel e role a lista inteira.", 100, 2))
            return out
        }
        val early = (i.round ?: 99) <= 3 || OsmEvents.Kind.TRANSFER in i.events
        val late = i.round != null && i.roundTotal != null && i.round > i.roundTotal / 2
        val maxListed = if (OsmEvents.Kind.TRANSFER in i.events) 6 else MAX_LISTED
        val sessionH = if (OsmEvents.Kind.STAFF in i.events) 4 else 8
        fun hhmm(t: Long) = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))
        for (e in i.events) out.add(Tip("🎉", "Evento ativo: ${e.label}", "Aproveite: ${e.tip}.", 96, 6))

        // 1) mínimo por posição (o jogo exige 2 GL, 4 DEF, 4 MEI, 3 ATA)
        val byCat = mine.groupingBy { it.cat }.eachCount()
        val short = MIN_POS.filter { (c, n) -> (byCat[c] ?: 0) < n }
        if (short.isNotEmpty()) {
            out.add(
                Tip(
                    "🚨", "Faltam jogadores: " + short.entries.joinToString { (c, n) -> "${n - (byCat[c] ?: 0)} ${CAT_LABEL[c]}" },
                    "O mínimo do OSM é 2 goleiros, 4 defensores, 4 meias e 3 atacantes. Compre os que faltam antes do próximo jogo.", 98, 6
                )
            )
        }

        // 2) treinadores livres e titular treinando na hora do jogo
        val training = mine.filter { it.training == true }.map { it.nameKey }.toSet()
        for (s in i.seats.filter { it.role != "UNI" && (it.state == "empty" || it.state == "done" || (it.endAt != null && it.endAt <= i.now)) }) {
            val cat = ROLE_CAT[s.role] ?: continue
            // jovem evolui mais rápido: o mais novo do setor que ainda não está treinando (empate: o mais forte)
            // quem rende mais pontos por dia de treino (aprendido nos seus jogadores; sem dados, o mais jovem).
            // Se a sessão acabaria depois do jogo, prefere um reserva (titular treinando joga pior).
            val endIfNow = i.now + sessionH * 3600000L
            val clashes = i.matchAt != null && i.matchAt > i.now && i.matchAt < endIfNow
            val pool = mine.filter { it.cat == cat && it.nameKey !in training }
            val pool2 = if (clashes) pool.filter { it.nameKey !in i.xi }.ifEmpty { pool } else pool
            val picked = TrainLearn.pick(pool2, i.trainRates)
            val pick = picked?.first
            val done = s.state == "done" || s.endAt != null
            val why = StringBuilder("Treinador parado é evolução perdida ($sessionH h por sessão; termina às ${hhmm(endIfNow)}). ")
            if (picked?.second != null) why.append("Na faixa de idade de ${pick!!.name} (${pick.age} anos) seus jogadores ganharam ${"%.1f".format(picked.second).replace('.', ',')} ponto(s) por dia de treino, a melhor taxa medida. ")
            else if (pick?.age != null) why.append("Jovens sobem mais rápido: ${pick.name} tem ${pick.age} anos. ")
            if (clashes) why.append("O jogo é às ${hhmm(i.matchAt!!)}, antes do fim: escolhi um reserva.")
            out.add(Tip("🏋", (if (done) "Reclame o treino e " else "") + "coloque ${pick?.name ?: "alguém"} no treinador de ${Timers.roleLabel(s.role)}", why.toString().trim(), 90, 2))
        }
        // agenda: 2 sessões por dia sem pegar o jogo (uma logo depois do jogo, que termina de manhã)
        if (i.matchAt != null && i.matchAt > i.now && i.seats.isNotEmpty()) {
            val after = i.matchAt + 15 * 60000L
            out.add(
                Tip(
                    "🗓", "Agenda de treino: comece a próxima sessão às ${hhmm(after)} (logo após o jogo)",
                    "Ela termina às ${hhmm(after + sessionH * 3600000L)}. Assim os titulares não jogam treinando e você encaixa ~2 sessões por dia.", 50, 2
                )
            )
        }
        if (i.matchAt != null && i.matchAt > i.now) {
            val clash = i.seats.filter { it.endAt != null && it.endAt > i.matchAt && it.name != null }
                .filter { s -> mine.any { p -> p.nameKey in i.xi && Txt.sim(Txt.key(p.name), Txt.key(s.name!!)) >= 0.8 } }
            if (clash.isNotEmpty()) {
                out.add(
                    Tip(
                        "⏰", "Titular treinando na hora do jogo: " + clash.joinToString { it.name!! },
                        "Comece os treinos logo depois do jogo (terminam de manhã) e outro que acabe antes do próximo. Quem treina não joga bem.", 80, 2
                    )
                )
            }
        }

        // 3) lista de transferências: máximo de 4, sempre cheia; quem vender e por quanto
        val left = i.listedUsed?.let { (maxListed - it).coerceAtLeast(0) }
        val reserves = mine.filter { it.nameKey !in i.xi && it.forSale != true }
        val size = mine.size
        val extra = (size - 21).coerceAtLeast(0)
        // vender: reservas mais velhos e mais fracos primeiro; jovens bons ficam (valor sobe com o treino)
        val sellOrder = reserves.sortedWith(
            compareByDescending<PlayerEntity> { (it.age ?: 0) >= 29 }.thenBy { it.strength ?: 0 }.thenByDescending { it.age ?: 0 }
        ).filter { p -> (byCat[p.cat] ?: 0) > (MIN_POS[p.cat] ?: 0) }
        if (left != null && left > 0 && sellOrder.isNotEmpty()) {
            val names = sellOrder.take(left).joinToString("; ") { p ->
                val v = Money.parse(p.valueText)
                val pr = v?.let { prices(it, early, i.ceiling, i.paid?.first) }
                p.name + " (${p.age ?: "?"}a, ${p.strength ?: "?"})" + (pr?.let { " — máx. ${money(it.first)}" + (if (!early) ", rápido ${money(it.second)}" else "") } ?: "")
            }
            out.add(
                Tip(
                    "💰", "Lista com $left vaga(s) livre(s): liste $names",
                    "Até $maxListed jogadores na lista ao mesmo tempo, e a CPU compra até 9 vezes por dia. Vaga vazia é dinheiro parado. " +
                        (i.paid?.let { "Nas suas ${it.second} vendas a CPU pagou em média ${"%.2f".format(it.first).replace('.', ',')}× o valor: o preço rápido usa isso. " } ?: "") +
                        "Venda reservas mais velhos e fracos; mantenha jovens bons e titulares." +
                        (if (early) " Agora a CPU paga o preço máximo (${"%.2f".format(i.ceiling).replace('.', ',').removeSuffix("0").removeSuffix(",0")}× o valor)." else " Para vender rápido, peça menos que o máximo."), 85, 6
                )
            )
        }
        if (early) {
            out.add(
                Tip(
                    "🔁", "Início da competição: compre barato e revenda pelo máximo",
                    "No começo da temporada e nos eventos de transferência a CPU compra quase tudo pelo preço máximo. Jogadores com valor abaixo de 2M vendem mais rápido.", 75, 6
                )
            )
        } else if (late) {
            out.add(
                Tip(
                    "📉", "Segunda metade: venda pedindo menos que o máximo",
                    "As vendas para a CPU caem depois do meio da liga. Peça 90% do máximo para jogadores de até 6M, 80% até 12M, 70% até 18M e 60% acima.", 45, 6
                )
            )
        }

        // 4) tamanho do elenco: 18–21
        if (size < 18) {
            out.add(Tip("➕", "Elenco curto ($size): o ideal é 18 a 21", "Com menos de 18 faltam peças para lesões, suspensões e cansaço ao longo da liga.", 70, 6))
        } else if (extra > 0) {
            out.add(
                Tip(
                    "➖", "Elenco grande ($size): venda $extra reserva(s)",
                    "Acima de 21 jogadores sobra gente sem jogar e sem treinar. Venda primeiro os mais velhos e fracos: " +
                        sellOrder.take(extra).joinToString { it.name } + ".", 65, 6
                )
            )
        }

        // olheiro: compra pelo valor (+15 BC) e revende até o teto
        if ((i.cashM ?: 0.0) >= 1.0) {
            val v = minOf(i.cashM!!, 10.0)
            val pr = prices(v, early, i.ceiling, i.paid?.first)
            out.add(
                Tip(
                    "🔎", "Olheiro: jogador de ~${money(v)} custa ${money(v)} + 15 BC e revende por ${money(pr.second)}",
                    "O olheiro traz o jogador pelo valor (mais barato que a lista). Lucro estimado: ${money(pr.second - v)} por jogador" +
                        (if (OsmEvents.Kind.SCOUT in i.events) " — e o olheiro está em promoção agora." else ". Abaixo de ~10M vende mais rápido."),
                    if (OsmEvents.Kind.SCOUT in i.events) 80 else 40, 6
                )
            )
        }
        when {
            i.sponsorRenew || i.sponsorGames == 0 -> out.add(Tip("🤝", "Escolha/renove o patrocinador", "O contrato acabou. Escolha a oferta que paga mais (ela melhora se você está acima da meta).", 88, 6))
            i.sponsorReadAt == null || i.now - i.sponsorReadAt > 10L * 86400000L ->
                out.add(Tip("🤝", "Confira o patrocínio", "Abra a tela de patrocinadores para eu ler quantos jogos faltam no contrato e avisar quando renovar.", 30, 6))
        }
        Nations.best(i.xi, i.nations)?.let { (country, n) ->
            if (n in 4..5) out.add(Tip("🌍", "Faltam ${6 - n} titular(es) de $country para o bônus de nacionalidade", "A comunidade fala em bônus escondido com 6 titulares do mesmo país (algumas fontes dizem 5). Você tem $n.", 45, 2))
        }

        // 5) estádio: Treino até 2★ → Capacidade → Relvado 1★ → Treino 3★ → equilibrar
        if (!i.stadiumBuilding && (i.capLevel != null || i.trainLevel != null || i.pitchLevel != null)) {
            val order = listOf(
                Triple("Treino", 2, "o centro de treino dá +25% de progresso no nível 2"),
                Triple("Capacidade", 1, "mais público = mais renda em cada jogo em casa"),
                Triple("Relvado", 1, "o gramado dá +2% de desempenho em casa por nível"),
                Triple("Treino", 3, "nível 3 do treino dá +50% de progresso"),
                Triple("Capacidade", 2, "mais renda"),
                Triple("Relvado", 2, "+4% em casa"),
                Triple("Capacidade", 3, "mais renda"),
                Triple("Relvado", 3, "+6% em casa")
            )
            fun lvl(n: String) = when (n) { "Treino" -> i.trainLevel; "Capacidade" -> i.capLevel; else -> i.pitchLevel } ?: 0
            order.firstOrNull { (n, target, _) -> lvl(n) < target }?.let { (n, target, why) ->
                out.add(Tip("🏟", "Próxima obra do estádio: $n (nível ${lvl(n)} → $target)" + (if (OsmEvents.Kind.FACILITIES in i.events) " — em promoção" else ""), "Ordem da comunidade: Treino até 2★, Capacidade, Relvado 1★, Treino 3★ e depois equilibrar. Por quê: $why.", 55, 6))
            }
        }
        return out.sortedByDescending { it.priority }
    }

    /** As regras gerais (com a fonte), para a seção "O que funciona". */
    val GUIDE = listOf(
        "💰 Venda: o preço máximo é 2,5× o valor (algumas ligas testaram 2×); o mínimo é 0,75×. Até 4 na lista (6 em eventos)." to "osmhelper.com • fórum oficial",
        "🕘 A CPU tenta comprar até 9 vezes por dia, em horários aleatórios; o preço listado cai um pouco a cada tentativa." to "fórum oficial (community manager)",
        "⚡ Barato vende rápido: abaixo de ~10–13M sai fácil; acima de 30M é lento." to "fórum oficial • wiki do OSM",
        "📅 Início da temporada e eventos de transferência: a CPU paga quase sempre o máximo. Compre e revenda o máximo possível." to "osmguide.com",
        "🔎 Olheiro: traz o jogador pelo valor (+15 BC), mais barato que a lista; dá para revender até 2,5×." to "osmhelper.com",
        "👥 Elenco: 18 a 21 jogadores; mínimo 2 GL, 4 DEF, 4 MEI, 3 ATA." to "osmguide.com • osmhelper.com",
        "🏋 Treino: 4 treinadores (GL, DEF, MEI, ATA), 8 h por sessão, ~2 sessões por dia. Jovens sobem mais rápido; não deixe titular treinando na hora do jogo." to "wiki do OSM • osmhelper.com",
        "🏟 Estádio: Treino até 2★ → Capacidade → Relvado 1★ → Treino → equilibrar. Treino +10/25/50% de progresso; Relvado +2/4/6% em casa." to "osmguide.com • osmhelper.com",
        "🤝 Patrocínio: a oferta melhora se você está acima da meta; conta só jogos em casa; renove assim que vencer." to "osmhelper.com",
        "🔒 Treino secreto (3 BC) esconde sua tática do analista do rival; estágio dá +25% no próximo jogo, mas avisa o rival (a menos que haja treino secreto antes)." to "osmhelper.com • wiki do OSM",
        "🇧🇷 Bônus escondido de nacionalidade: 6 titulares do mesmo país (fontes divergem entre 5 e 6)." to "osmguide.com • osmhelper.com"
    )
}
