package com.osmaicoach.collector

object ScreenClassifier {
    data class Result(val type: String, val title: String)

    fun classify(text: String): Result {
        val t = normalize(text)
        if (t.isBlank()) return Result("other", "Tela do OSM")

        val rules = listOf(
            "calendar" to listOf("calendario","calendar","rodada","jornada","proximo jogo","próximo jogo","fixtures"),
            "squad" to listOf("elenco","plantel","squad","jogadores","players","atacante","meio campo","defesa","goleiro"),
            "match" to listOf("analise","análise","analysis","arbitro","árbitro","formacao","formação","marcacao","marcação","impedimento","offside"),
            "market" to listOf("transferencia","transferência","transfer list","mercado","comprar jogador","vender jogador"),
            "training" to listOf("treino","training","treinamento","campo de treinamento","training camp"),
            "club" to listOf("clube","club","estadio","estádio","stadium","financas","finanças","finance"),
            "ranking" to listOf("classificacao","classificação","standings","tabela","ranking"),
            "result" to listOf("resultado","result","estatisticas","estatísticas","statistics","posse de bola"),
            "tactics" to listOf("tatica","tática","tactics","pressao","pressão","ritmo","estilo de jogo")
        )

        val best = rules
            .map { (type, words) -> type to words.count { t.contains(normalize(it)) } }
            .maxByOrNull { it.second }

        val type = if (best != null && best.second > 0) best.first else "other"
        val title = when(type) {
            "calendar" -> "Calendário"
            "squad" -> "Elenco"
            "match" -> "Pré-jogo / Análise"
            "market" -> "Mercado"
            "training" -> "Treinamento"
            "club" -> "Clube"
            "ranking" -> "Classificação"
            "result" -> "Resultado / Estatísticas"
            "tactics" -> "Táticas"
            else -> firstMeaningful(text)
        }
        return Result(type, title)
    }

    private fun normalize(v: String): String =
        java.text.Normalizer.normalize(v.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun firstMeaningful(text: String): String =
        text.split("|").map { it.trim() }.firstOrNull { it.length in 3..50 } ?: "Tela do OSM"
}
