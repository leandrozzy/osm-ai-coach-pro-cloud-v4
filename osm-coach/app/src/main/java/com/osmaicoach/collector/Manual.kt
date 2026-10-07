package com.osmaicoach.collector

/** Campos que o usuário pode preencher à mão quando a leitura não conseguiu pegar. */
object ManualFields {
    data class Spec(val key: String, val label: String, val options: List<String>?, val hint: String, val example: String)

    private val YES_NO = listOf("Sim", "Não")
    private val STYLES = listOf("Jogo de passe", "Jogar pelas alas", "Remate à vista", "Contra-ataque", "Bolas longas")
    private const val STADIUM_HINT = "Menu → Estádio: o nível é o número de ESTRELAS douradas do card"
    private const val ANALYSIS = "Analista de dados → Relatório do analista → tela com a nota à esquerda (alterne Tática e Equipa inicial)"

    private val NUMERIC = setOf(
        K.ROUND, K.ROUND_DONE, K.ROUND_TOTAL, K.LEAGUE_POS, K.POINTS, K.MY_STRENGTH, K.RIVAL_STRENGTH,
        K.MY_GOL, K.MY_DEF, K.MY_MID, K.MY_ATK, K.RIVAL_GOL, K.RIVAL_DEF, K.RIVAL_MID, K.RIVAL_ATK
    )
    private val MONEY = setOf(K.MY_VALUE, K.RIVAL_VALUE, K.CASH)

    val SPECS: Map<String, Spec> = listOf(
        Spec(K.TEAM, "Meu time", null, "Central dos slots ou Plantel", "Tobol"),
        Spec(K.COMPETITION, "Competição", null, "Central dos slots", "Cazaquistão"),
        Spec(K.COMP_TYPE, "Tipo", listOf("Liga", "Copa", "Batalha"), "Central dos slots", "Liga"),
        Spec(K.ROUND, "Próxima rodada", null, "Pré-jogo (topo da tela)", "25"),
        Spec(K.ROUND_DONE, "Rodadas concluídas", null, "Central dos slots (ex.: 24/34)", "24"),
        Spec(K.ROUND_TOTAL, "Total de rodadas", null, "Central dos slots (ex.: 24/34)", "34"),
        Spec(K.LEAGUE_POS, "Posição na liga", null, "Classificação", "4"),
        Spec(K.POINTS, "Pontos", null, "Classificação", "47"),
        Spec(K.CASH, "Caixa", null, "Barra superior do jogo", "19,4M"),
        Spec(K.MATCH_AT, "Data/hora do jogo", null, "Pré-jogo (formato dia/mês hora:min)", "07/10 22:18"),
        Spec(K.HOME, "Casa/fora", listOf("Casa", "Fora"), "Pré-jogo (ícone da casa/avião)", "Casa"),
        Spec(K.RIVAL_TEAM, "Adversário", null, "Pré-jogo", "FC Zhenis Astana"),
        Spec(K.RIVAL_HUMAN, "Humano?", YES_NO, "Pré-jogo / calendário (apelido embaixo do time)", "Sim"),
        Spec(K.RIVAL_NICK, "Apelido do rival", null, "Calendário (embaixo do time humano)", "ChinoM10"),
        Spec(K.REFEREE, "Árbitro", listOf("Brando", "Médio", "Rigoroso"), "Pré-jogo (termômetro do árbitro)", "Brando"),
        Spec(K.MY_STRENGTH, "Minha força", null, "Pré-jogo (círculo azul)", "91"),
        Spec(K.RIVAL_STRENGTH, "Força do rival", null, "Pré-jogo (círculo vermelho)", "61"),
        Spec(K.MY_VALUE, "Valor do meu elenco", null, "Plantel (topo)", "289M"),
        Spec(K.RIVAL_VALUE, "Valor do elenco rival", null, "Plantel do rival (topo)", "27,5M"),
        Spec(K.MY_GOL, "Meu GOL", null, "Plantel (bolhas Gr/Def/Méd/Ata)", "88"),
        Spec(K.MY_DEF, "Meu DEF", null, "Plantel (bolhas Gr/Def/Méd/Ata)", "90"),
        Spec(K.MY_MID, "Meu MEI", null, "Plantel (bolhas Gr/Def/Méd/Ata)", "90"),
        Spec(K.MY_ATK, "Meu ATA", null, "Plantel (bolhas Gr/Def/Méd/Ata)", "93"),
        Spec(K.RIVAL_GOL, "GOL do rival", null, "Plantel do rival (bolhas)", "63"),
        Spec(K.RIVAL_DEF, "DEF do rival", null, "Plantel do rival (bolhas)", "63"),
        Spec(K.RIVAL_MID, "MEI do rival", null, "Plantel do rival (bolhas)", "60"),
        Spec(K.RIVAL_ATK, "ATA do rival", null, "Plantel do rival (bolhas)", "61"),
        Spec(K.RIVAL_FORMATION, "Formação do rival", Formations.ALL, "Plantel do rival (topo) ou análise", "4-3-3 A"),
        Spec(K.RIVAL_PLAN, "Plano de jogo do rival", STYLES, ANALYSIS, "Jogar pelas alas"),
        Spec(K.RIVAL_MARKING, "Marcação do rival", listOf("À zona", "Homem a homem"), ANALYSIS, "À zona"),
        Spec(K.RIVAL_OFFSIDE, "Impedimento do rival", YES_NO, ANALYSIS, "Não"),
        Spec(K.RIVAL_TACKLE, "Desarme do rival", listOf("Normal", "Agressivo"), ANALYSIS, "Normal"),
        Spec(K.RIVAL_SECRET, "Treino secreto do rival", YES_NO, ANALYSIS, "Não"),
        Spec(K.RIVAL_CAMP, "Campo de treinamento do rival", YES_NO, ANALYSIS, "Não"),
        Spec(K.RIVAL_LOGIN_BONUS, "Bônus do rival (login)", null, "Pré-jogo (círculo do rival, +N%, só humano)", "+3%"),
        Spec(K.STADIUM, "Nível do estádio do rival", null, ANALYSIS, "Nível 1"),
        Spec(K.MY_STADIUM, "Meu estádio", null, "Menu → Estádio", "Capacidade 3 • Relvado 2 • Treino 1"),
        Spec(K.MY_STAD_CAP, "Estádio: capacidade (estrelas)", listOf("1", "2", "3"), STADIUM_HINT, "3"),
        Spec(K.MY_STAD_PITCH, "Estádio: relvado (estrelas)", listOf("1", "2", "3"), STADIUM_HINT, "2"),
        Spec(K.MY_STAD_TRAIN, "Estádio: treino (estrelas)", listOf("1", "2", "3"), STADIUM_HINT, "1"),
        Spec(K.MY_BONUS, "Meu bônus", null, "Pré-jogo (círculo do seu time, +N%)", "+3%")
    ).associateBy { it.key }

    fun spec(key: String, fallbackLabel: String): Spec = SPECS[key] ?: Spec(key, fallbackLabel, null, "", "")

    /** Valida e padroniza o que o usuário digitou; null = inválido. */
    fun normalize(key: String, raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        if (key in NUMERIC) return if (Regex("^\\d{1,3}$").matches(t)) t else null
        if (key in MONEY) {
            val m = Regex("^(\\d{1,3}(?:[.,]\\d{1,2})?)\\s*([kKmM])$").find(t) ?: return null
            return m.groupValues[1].replace('.', ',') + m.groupValues[2].uppercase()
        }
        if (key == K.RIVAL_LOGIN_BONUS || key == K.MY_BONUS) {
            val m = Regex("^\\+?(\\d{1,2})\\s*%$").find(t) ?: return null
            return "+" + m.groupValues[1] + "%"
        }
        if (key == K.MATCH_AT) {
            val m = Regex("^(\\d{1,2})/(\\d{1,2})\\s+(\\d{1,2}):(\\d{2})$").find(t) ?: return null
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.MONTH, m.groupValues[2].toInt() - 1)
            cal.set(java.util.Calendar.DAY_OF_MONTH, m.groupValues[1].toInt())
            cal.set(java.util.Calendar.HOUR_OF_DAY, m.groupValues[3].toInt())
            cal.set(java.util.Calendar.MINUTE, m.groupValues[4].toInt())
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis.toString()
        }
        val opts = SPECS[key]?.options
        if (opts != null && key != K.RIVAL_FORMATION) {
            val n = Txt.norm(t)
            return opts.firstOrNull { Txt.norm(it) == n }
        }
        return if (t.length <= 60) t else null
    }
}
