package com.osmaicoach.collector

object ScreenClassifier {
    private val hubRound = Regex("\\d{1,2}\\s*/\\s*\\d{1,2}\\s*jornada")
    private val jornada = Regex("jornada\\s*\\d{1,2}")

    /**
     * Whitelist: só é tela do OSM o que tem a barra superior do OSM ou é a central dos slots.
     * Qualquer anúncio (de qualquer rede) cai em NON_OSM e é descartado.
     */
    fun classify(ocr: OcrResult, osmTopBar: Boolean): ScreenType {
        val t = Txt.norm(ocr.fullText)
        val hubHits = hubRound.findAll(t).count()
        if (hubHits >= 2 || (hubHits >= 1 && t.contains("espacos de treinador"))) return ScreenType.HUB
        if (ocr.tokens.size < 4) return ScreenType.NOISE
        if (!osmTopBar) return ScreenType.NON_OSM

        if (t.contains("para treinar") || t.contains("treinador universal") || t.contains("treinador de ")) {
            return ScreenType.TRAINING
        }
        if (t.contains("vender jogadores") || t.contains("lista de transferencias") ||
            (t.contains("olheiro") && t.contains("negociacoes"))
        ) return ScreenType.MARKET
        if (t.contains("define a tua tatica") || (t.contains("temporizacao") && t.contains("pressao"))) {
            return ScreenType.TACTIC
        }
        if (jornada.findAll(t).count() >= 4) return ScreenType.CALENDAR
        if (t.contains("idade") && t.contains("valor") && t.contains("jogador")) return ScreenType.SQUAD
        // Lista rolada: o cabeçalho some, mas continuam as colunas de idade e valor.
        val moneyRight = ocr.tokens.count { it.xc > 0.90f && Money.valid(it.text) }
        val ages = ocr.tokens.count { tk ->
            val n = tk.text.toIntOrNull()
            n != null && n in 15..45 && tk.xc in 0.50f..0.60f
        }
        if (moneyRight >= 3 && ages >= 3) return ScreenType.SQUAD
        if (t.contains("preparacao para o jogo") || (t.contains("jogo rapido") && t.contains("treino"))) {
            return ScreenType.PREGAME
        }
        val reportWords = listOf("formacao", "marcacao", "desarme", "fora de jogo", "impedimento", "estilo de jogo")
        if (reportWords.count { t.contains(it) } >= 3) return ScreenType.REPORT
        return ScreenType.OTHER_OSM
    }
}
