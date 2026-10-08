package com.osmaicoach.collector

object ScreenClassifier {
    data class Result(val type: String, val title: String)

    fun classify(text: String): Result {
        val t = normalize(text)
        if (t.isBlank()) return Result("other", "Tela do OSM")

        val rules = linkedMapOf(
            "calendar" to listOf("calendario","rodada","jornada","proximo jogo","fixtures","copa"),
            "squad" to listOf("elenco","plantel","jogadores","atacante","meio campo","goleiro","valor do jogador","idade"),
            "match" to listOf("analise","arbitro","formacao","marcacao","impedimento","adversario","forca geral"),
            "tactics" to listOf("tatica","pressao","ritmo","estilo de jogo","desarme","linha de ataque"),
            "market" to listOf("transferencia","lista de transferencias","mercado","comprar jogador","vender jogador","a venda"),
            "training" to listOf("treino","treinamento","campo de treinamento","training camp"),
            "result" to listOf("resultado","estatisticas","posse de bola","chutes","cartoes"),
            "ranking" to listOf("classificacao","tabela","ranking","pontos"),
            "club" to listOf("clube","estadio","financas","objetivo","valor do elenco")
        )

        var bestType = "other"
        var bestScore = 0
        for ((type, words) in rules) {
            var score = 0
            for (w in words) if (t.contains(normalize(w))) score++
            if (score > bestScore) {
                bestScore = score
                bestType = type
            }
        }

        val title = when(bestType) {
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
        return Result(bestType, title)
    }

    private fun normalize(v: String): String =
        java.text.Normalizer.normalize(v.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun firstMeaningful(text: String): String =
        text.lines().map { it.trim() }.firstOrNull { it.length in 3..50 } ?: "Tela do OSM"
}
