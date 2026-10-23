package com.osmaicoach.collector

/**
 * "Telas para atualizar": o app só sabe o que você deixou ele ler. Para cada slot, vê quando cada tela do OSM
 * foi lida pela última vez e diz qual visitar agora (e por quê), com o caminho no menu do jogo.
 * Urgência 2 = agora (muda a decisão do próximo jogo), 1 = em breve, 0 = quando puder.
 */
object Fresh {
    data class Need(val slot: Int, val type: ScreenType, val urgency: Int, val reason: String)

    data class In(
        val slot: Int,
        val seen: Map<String, Long>,
        val timers: List<Timers.Entry>,
        val matchAt: Long?,
        val sponsorEnding: Boolean,
        val now: Long,
        /** Slot com competição em andamento (sem isso não há jogo para preparar). */
        val active: Boolean = true
    )

    val NAME = mapOf(
        ScreenType.TRAINING to "Treinamento", ScreenType.STADIUM to "Estádio", ScreenType.MARKET to "Lista de transferências",
        ScreenType.SQUAD to "Plantel", ScreenType.PREGAME to "Pré-jogo", ScreenType.REPORT to "Análise do rival",
        ScreenType.CALENDAR to "Calendário", ScreenType.SPONSORS to "Patrocinadores", ScreenType.DOCTOR to "Médico",
        ScreenType.LAWYER to "Advogado", ScreenType.SPECIALISTS to "Especialistas", ScreenType.PREP to "Preparação para o jogo",
        ScreenType.SCOUT to "Olheiro", ScreenType.DEALS to "Negociações", ScreenType.STATS to "Estatísticas da liga",
        ScreenType.MISSIONS to "Missões"
    )

    /** Onde tocar no OSM para chegar na tela (nomes como aparecem no menu do jogo). */
    val PATH = mapOf(
        ScreenType.TRAINING to "Menu ☰ → Treino",
        ScreenType.STADIUM to "Menu ☰ → Estádio",
        ScreenType.MARKET to "Menu ☰ → Lista de transferências (role a lista e abra a aba Vender jogadores)",
        ScreenType.SQUAD to "Menu ☰ → Plantel (role até o fim)",
        ScreenType.PREGAME to "Central → toque no card do slot (Próximo jogo)",
        ScreenType.REPORT to "Menu ☰ → Analista de Dados → Ver relatório",
        ScreenType.CALENDAR to "Menu ☰ → Calendário (role até o fim)",
        ScreenType.SPONSORS to "Menu ☰ → Patrocinadores",
        ScreenType.DOCTOR to "Menu ☰ → Médico",
        ScreenType.LAWYER to "Menu ☰ → Advogado",
        ScreenType.SPECIALISTS to "Menu ☰ → Especialistas",
        ScreenType.PREP to "Pré-jogo → toque em Preparação para o jogo (sino)",
        ScreenType.SCOUT to "Menu ☰ → Olheiro",
        ScreenType.DEALS to "Menu ☰ → Lista de transferências → aba Transferências",
        ScreenType.STATS to "Menu ☰ → Classificação → aba Estatísticas",
        ScreenType.MISSIONS to "Menu ☰ → Missões"
    )

    /** Guia fixo: o que cada tela alimenta e de quanto em quanto tempo vale passar por ela. */
    val GUIDE = listOf(
        Triple(ScreenType.SQUAD, "Elenco, forças, condição, moral e cartões → 11 ideal, treino, vendas", "1x ao dia e depois de contratar ou vender"),
        Triple(ScreenType.PREGAME, "Rival, forças, bônus e árbitro → a tática do jogo", "até 20 min antes de cada jogo"),
        Triple(ScreenType.REPORT, "Formação e estilo do rival → contra-tática", "antes do jogo (rival humano muda)"),
        Triple(ScreenType.DOCTOR, "Lesionados → saem do 11", "no dia do jogo"),
        Triple(ScreenType.LAWYER, "Suspensos → saem do 11", "no dia do jogo"),
        Triple(ScreenType.TRAINING, "Quem treina e quando termina → avisos e quem treinar", "a cada sessão (o app avisa quando acaba)"),
        Triple(ScreenType.MARKET, "Vagas na lista, vendas e mercado → dicas de venda/compra", "1x ao dia e depois de vender"),
        Triple(ScreenType.STADIUM, "Níveis e obra em andamento → aviso de fim de obra", "a cada obra"),
        Triple(ScreenType.CALENDAR, "Rodadas, rivais e horários → próximo jogo e alarmes", "a cada 3 dias"),
        Triple(ScreenType.SPONSORS, "Contratos e valores → hora de renovar", "a cada 2–3 dias ou quando um contrato acabar"),
        Triple(ScreenType.SPECIALISTS, "Capitão, pênaltis, faltas, escanteios → sugestão", "quando mudar o time"),
        Triple(ScreenType.PREP, "Itens da preparação do jogo → o que falta fazer", "no dia do jogo"),
        Triple(ScreenType.SCOUT, "Busca do olheiro → aviso quando volta", "ao iniciar uma busca"),
        Triple(ScreenType.DEALS, "Preços reais de venda da CPU → preço sugerido", "de vez em quando"),
        Triple(ScreenType.STATS, "Gols por jogo da liga → calibra a previsão", "1x por semana"),
        Triple(ScreenType.MISSIONS, "Renovação das missões → aviso", "1x por semana")
    )

    fun ago(ms: Long): String {
        val m = ms / 60000L
        return when {
            m < 60 -> "há $m min"
            m < 48 * 60 -> "há ${m / 60} h"
            else -> "há ${m / 1440} dias"
        }
    }

    fun needs(i: In): List<Need> {
        if (!i.active) return emptyList()
        val H = 3600000L
        val out = ArrayList<Need>()
        fun age(t: ScreenType): Long? = i.seen[t.name]?.let { i.now - it }
        fun add(t: ScreenType, u: Int, r: String) { out.add(Need(i.slot, t, u, r)) }
        val gameIn = i.matchAt?.let { it - i.now }
        val soon = gameIn != null && gameIn in 0L..(24 * H)

        val tEnd = i.timers.filter { it.training && it.at <= i.now }
        val a = age(ScreenType.TRAINING)
        when {
            tEnd.isNotEmpty() -> add(ScreenType.TRAINING, 2, "${tEnd.size} treino(s) terminou: reclame e coloque outro jogador")
            a == null -> add(ScreenType.TRAINING, 1, "ainda não li os treinadores deste slot")
            a > 12 * H -> add(ScreenType.TRAINING, 1, "última leitura ${ago(a)}")
        }
        val sEnd = i.timers.filter { !it.training && !it.scout && !it.mission && !it.lawyer && it.at <= i.now }
        val s = age(ScreenType.STADIUM)
        when {
            sEnd.isNotEmpty() -> add(ScreenType.STADIUM, 2, "a obra terminou: conclua e comece a próxima")
            s == null -> add(ScreenType.STADIUM, 1, "ainda não li o estádio")
            s > 24 * H -> add(ScreenType.STADIUM, 0, "última leitura ${ago(s)}")
        }
        val m = age(ScreenType.MARKET)
        when {
            m == null -> add(ScreenType.MARKET, 1, "ainda não li a lista de transferências")
            m > 12 * H -> add(ScreenType.MARKET, 1, "última leitura ${ago(m)}: vagas na lista e vendas")
        }
        val q = age(ScreenType.SQUAD)
        when {
            q == null -> add(ScreenType.SQUAD, 2, "ainda não li o plantel do seu time")
            q > 24 * H -> add(ScreenType.SQUAD, 1, "última leitura ${ago(q)}: contratações, forma e cartões")
        }
        val p = age(ScreenType.PREGAME)
        if (gameIn != null && gameIn in 0L..(90 * 60000L) && (p == null || p > 60 * 60000L)) {
            add(ScreenType.PREGAME, 2, "o jogo é daqui a ${gameIn / 60000} min: releia o pré-jogo (o rival pode ter mudado)")
        } else if (p == null) add(ScreenType.PREGAME, 1, "ainda não li o próximo jogo")
        val r = age(ScreenType.REPORT)
        if (gameIn != null && gameIn in 0L..(90 * 60000L) && (r == null || r > 90 * 60000L)) {
            add(ScreenType.REPORT, 1, "veja a análise do rival antes do jogo")
        }
        val c = age(ScreenType.CALENDAR)
        when {
            c == null -> add(ScreenType.CALENDAR, 1, "ainda não li o calendário")
            c > 3 * 24 * H -> add(ScreenType.CALENDAR, 1, "última leitura ${ago(c)}")
        }
        val sp = age(ScreenType.SPONSORS)
        when {
            sp == null -> add(ScreenType.SPONSORS, 1, "ainda não li os contratos de patrocínio")
            i.sponsorEnding && sp > 2 * 24 * H -> add(ScreenType.SPONSORS, 1, "contrato acabando: veja se já dá para renovar")
            sp > 3 * 24 * H -> add(ScreenType.SPONSORS, 0, "última leitura ${ago(sp)}: a comunidade revê os patrocínios a cada 2–3 dias")
        }
        for ((t, why) in listOf(ScreenType.DOCTOR to "lesões", ScreenType.LAWYER to "suspensões")) {
            val x = age(t)
            when {
                soon && (x == null || x > 12 * H) -> add(t, 2, "o jogo é hoje: $why mudam o 11")
                x == null -> add(t, 1, "ainda não li ($why tiram jogadores do 11)")
                x > 48 * H -> add(t, 0, "última leitura ${ago(x)}")
            }
        }
        val sc = age(ScreenType.SPECIALISTS)
        when {
            sc == null -> add(ScreenType.SPECIALISTS, 1, "ainda não li capitão, pênaltis, faltas e escanteios")
            sc > 7 * 24 * H -> add(ScreenType.SPECIALISTS, 0, "última leitura ${ago(sc)}")
        }
        val pr = age(ScreenType.PREP)
        if (soon && (pr == null || pr > 6 * H)) add(ScreenType.PREP, 1, "veja o que falta na preparação do jogo (treinos, estágio, 11 inicial…)")
        if (i.timers.any { it.scout && it.at <= i.now }) add(ScreenType.SCOUT, 2, "o olheiro terminou a busca")
        val st = age(ScreenType.STATS)
        when {
            st == null -> add(ScreenType.STATS, 1, "ainda não li as estatísticas da liga (gols por jogo calibram a previsão)")
            st > 7 * 24 * H -> add(ScreenType.STATS, 0, "última leitura ${ago(st)}")
        }
        val d = age(ScreenType.DEALS)
        if (d == null) add(ScreenType.DEALS, 0, "ainda não li o histórico de transferências (preço real que a CPU paga)")
        return out.sortedWith(compareByDescending<Need> { it.urgency }.thenBy { it.type.ordinal })
    }

    /** Global (não é de slot): missões. */
    fun global(timers: List<Timers.Entry>, seen: Map<String, Long>, now: Long): List<Need> {
        val out = ArrayList<Need>()
        val t = timers.filter { it.mission }
        when {
            t.any { it.at <= now } -> out.add(Need(0, ScreenType.MISSIONS, 1, "novas missões já devem estar disponíveis"))
            seen[ScreenType.MISSIONS.name] == null -> out.add(Need(0, ScreenType.MISSIONS, 0, "passe pelas Missões: eu aviso quando renovarem"))
        }
        return out
    }
}
