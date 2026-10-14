package com.osmaicoach.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Treinamento, árbitro e telas do próprio app: casos reais do diagnóstico de 14/10 (2ª leitura). */
class TrainingRefereeTest {
    private fun ocr(trace: String): OcrResult {
        val lines = trace.split(" | ").mapNotNull { part ->
            val at = part.lastIndexOf('@')
            if (at <= 0) return@mapNotNull null
            val xy = part.substring(at + 1).split(",")
            val x = xy.getOrNull(0)?.toFloatOrNull() ?: return@mapNotNull null
            val y = xy.getOrNull(1)?.toFloatOrNull() ?: return@mapNotNull null
            val t = part.substring(0, at)
            Fx.line(t, x, y, (0.012f * t.length).coerceIn(0.01f, 0.4f), 0.02f)
        }
        return Fx.ocr(*lines.toTypedArray())
    }

    private val ALL_DONE = "24h@0.04,0.36 | Treinador universal@0.09,0.29 | Treina os teus jogadores para melhorares o teu plantel!@0.17,0.17 | PROGRESSÃO ADICIDNALE@0.10,0.79 | TEMPORIZADOR MAIS CURTO!@0.10,0.83 | 34,5M@0.18,0.04 | Reclamar@0.09,0.94 | 142@0.27,0.04 | Treinador de avançados@0.28,0.29 | 19 BUKAYO SAKA@0.28,0.48 | IDADE: 25@0.28,0.51 | FREE@0.33,0.05 | 67%@0.28,0.68 | Treino concluido@0.29,0.79 | Reclamar@0.28,0.94 | ED@0.33,0.57 | 95@0.33,0.62 | Treinador de médios@0.47,0.29 | 10 PHIL FODEN@0.47,0.48 | IDADE: 26@0.47,0.51 | 73%@0.47,0.68 | Treino concluido@0.48,0.79 | Reclamar@0.47,0.94 | MCO@0.52,0.57 | 91@0.52,0.62 | Treinador de defesas@0.66,0.29 | 4 RÚBEN DIAS@0.66,0.48 | IDADE: 29@0.66,0.51 | 90%@0.66,0.68 | Treino concluído@0.67,0.79 | Reclamar@0.66,0.93 | DO@0.71,0.57 | 97@0.71,0.62 | Nível 1@0.85,0.22 | Treinador de guarda-redes@0.85,0.29 | 28 CAOIMHIN KELLEHER@0.85,0.48 | IDADE: 27@0.85,0.51 | 51%@0.85,0.68 | Treino concluído@0.86,0.79 | Reclamar@0.85,0.94 | Usar agora!@0.93,0.22 | GR@0.90,0.57 | 90@0.90,0.62"
    private val THREE_ACTIVE = "O 24h@0.04,0.36 | 34,5M@0.13,0.04 | Treina os teus jogadores para melhorares o teu plantel!@0.17,0.17 | Treinador universal@0.09,0.29 | PROGRESSÃO ADICIONALE@0.10,0.79 | TEMPORIZADOR MAIS CURTO!@0.10,0.83 | Reclamar@0.09,0.94 | 142@0.22,0.04 | FREE@0.28,0.05 | Treinador de avançados@0.28,0.29 | -2h@0.23,0.92 | 7 BRYAN MBEUMO@0.29,0.48 | OAD@0.23,0.95 | IDADE: 27@0.28,0.51 | 63%@0.28,0.68 | 1h 59m 52s@0.29,0.79 | ED@0.33,0.57 | 96@0.33,0.62 | Concluir@0.31,0.92 | -2h@0.42,0.92 | Treinador de médios@0.47,0.29 | OIAD@0.42,0.95 | 10 PHIL FODEN@0.47,0.48 | IDADE: 26@0.47,0.51 | 996@0.47,0.68 | 1h 59m 55s@0.48,0.79 | MCO@0.52,0.57 | 92@0.52,0.62 | Concluir@0.50,0.92 | -2h@0.61,0.92 | Treinador de defesas@0.66,0.29 | 2 JULES KOUNDÉ@0.66,0.48 | OIAD@0.60,0.95 | IDADE: 27@0.66,0.51 | 71%@0.66,0.68 | 1h 59m 585@0.67,0.79 | DO@0.71,0.57 | 91@0.71,0.62 | Concluir@0.69,0.92 | Treinador de guarda-redes@0.85,0.29 | -2h@0.80,0.92 | Nível 1@0.85,0.22 | OAD@0.79,0.95 | 28 CAOIMHIN KELLEHER@0.85,0.48 | IDADE: 27@0.85,0.51 | 91%@0.85,0.68 | 2h 00m 00s@0.86,0.79 | GR@0.90,0.57 | Usar agora!@0.93,0.22 | 90@0.90,0.62 | Concluir@0.88,0.92"
    private val ALL_EMPTY = "24h@0.04,0.36 | Treinador universal@0.09,0.29 | Treina os teus jogadores para melhorares o teu plantel!@0.17,0.17 | PROGRESSÃO ADICIDNALE@0.10,0.79 | TEMPORIZADOR MAIS CURTO!@0.10,0.83 | 12,6M@0.18,0.04 | Reclamar@0.09,0.94 | 141@0.27,0.04 | FREE O@0.33,0.05 | Treinador de avançados@0.28,0.29 | Seleciona@0.28,0.92 | 2h@0.29,0.96 | Treinador de médios@0.47,0.29 | Seleciona@0.47,0.92 | 2h@0.48,0.96 | Treinador de defesas@0.66,0.29 | Seleciona@0.66,0.92 | 9 2h@0.66,0.96 | ANNIVERSsARY@0.72,0.50 | Nível 1@0.85,0.22 | Treinador de guarda-redes@0.85,0.29 | Seleciona@0.85,0.92 | 2h@0.86,0.96 | Usar agora!@0.93,0.22"
    private val REWARD_ANIMATION = "24h@0.04,0.36 | Treinador universal@0.09,0.29 | Treina os teus jogadores para melhorares o teu plantel!@0.17,0.17 | PROGRESSÃO ADICIDNALE@0.10,0.79 | TEMPORIZADOR MAIS CURTO!@0.10,0.83 | 12,6M@0.18,0.04 | Reclamar@0.09,0.94 | 141@0.27,0.04 | FREE O@0.33,0.05 | Treinador de avançados@0.28,0.29 | Seleciona@0.28,0.92 | 2h@0.29,0.96 | Treinador de médios@0.47,0.29 | Seleciona@0.47,0.92 | 2h@0.48,0.96 | Treinador de defesas@0.66,0.29 | Seleciona@0.66,0.92 | 9 2h@0.66,0.96 | NIVERSARY@0.73,0.50 | Nível 1@0.85,0.22 | Treinador de guarda-redes@0.85,0.29 | 1 PAU LÓPEZ@0.85,0.48 | IDADE: 31@0.85,0.51 | 6%@0.85,0.68 | LENDÁRIO@0.85,0.90 | GR@0.90,0.57 | Usar agora!@0.93,0.22 | 88@0.90,0.62"

    @Test fun finishedTrainingsAreDoneNotActive() {
        val t = Parsers.training(ocr(ALL_DONE)).associateBy { it.role }
        assertEquals("done", t["ATA"]?.state)
        assertEquals("BUKAYO SAKA", t["ATA"]?.name)
        assertEquals("done", t["MEI"]?.state)
        assertEquals("done", t["DEF"]?.state)
        assertEquals("done", t["GOL"]?.state)
        assertNull(t["UNI"])
    }

    @Test fun newTrainingsWithTimerAreActive() {
        val t = Parsers.training(ocr(THREE_ACTIVE)).associateBy { it.role }
        assertEquals("active", t["ATA"]?.state)
        assertEquals("BRYAN MBEUMO", t["ATA"]?.name)
        assertEquals("active", t["MEI"]?.state)
        assertEquals("PHIL FODEN", t["MEI"]?.name)
        assertEquals("active", t["DEF"]?.state)
        assertEquals("JULES KOUNDÉ", t["DEF"]?.name)
        assertEquals("active", t["GOL"]?.state)
    }

    @Test fun emptyTrainersAreFree() {
        val t = Parsers.training(ocr(ALL_EMPTY)).associateBy { it.role }
        for (r in listOf("ATA", "MEI", "DEF", "GOL")) assertEquals(r, "empty", t[r]?.state)
    }

    @Test fun rewardAnimationFrameIsIgnored() {
        assertTrue(Parsers.training(ocr(REWARD_ANIMATION)).isEmpty())
    }

    @Test fun squadListNoLongerSaysWhoIsTraining() {
        val rows = ArrayList<OcrLine>()
        rows += Fx.line("Tobol", 0.05f, 0.228f, 0.05f)
        rows += Fx.line("leandrozzy", 0.06f, 0.263f, 0.08f)
        rows += Fx.line("Posição: 4", 0.08f, 0.149f, 0.1f)
        rows += Fx.line("Mbeumo", 0.07f, 0.67f); rows += Fx.line("27", 0.555f, 0.67f, 0.03f); rows += Fx.line("ED", 0.62f, 0.67f, 0.03f)
        rows += Fx.line("95", 0.663f, 0.67f, 0.025f); rows += Fx.line("28", 0.70f, 0.67f, 0.025f); rows += Fx.line("61", 0.737f, 0.67f, 0.025f)
        val img = PixelProbe.Img(100, 100, IntArray(100 * 100) { 0xFFFF8C00.toInt() }) // tudo laranja
        assertNull(Parsers.squad(Fx.ocr(*rows.toTypedArray()), img).players.single().training)
    }

    // ------------------------------------------------------------------ árbitro

    private fun img(name: String): PixelProbe.Img {
        val bi = javax.imageio.ImageIO.read(javaClass.getResourceAsStream("/referee/$name.jpg"))
        val px = IntArray(bi.width * bi.height)
        bi.getRGB(0, 0, bi.width, bi.height, px, 0, bi.width)
        return PixelProbe.Img(bi.width, bi.height, px)
    }

    @Test fun orangeThermometerIsMedium() {
        assertEquals("Médio", PixelProbe.refereeSeverity(img("orange")))
        assertEquals("Médio", PixelProbe.refereeSeverity(img("orange"), 0.505f, 0.47f))
    }

    @Test fun greenThermometerIsLenient() {
        assertEquals("Brando", PixelProbe.refereeSeverity(img("green")))
    }

    @Test fun blueTransitionBackgroundIsNotARead() {
        // quadro de transição com fundo azul e termômetro laranja: nunca "Brando"
        val r = PixelProbe.refereeSeverity(img("bluebg"))
        assertTrue(r == null || r == "Médio")
    }

    @Test fun orangeOrRedRefereeMeansCarefulTackle() {
        assertEquals(listOf("Cuidadoso"), Osm.allowedTackles("Médio"))
        assertEquals(listOf("Cuidadoso"), Osm.allowedTackles("Rigoroso"))
        assertEquals("Cuidadoso", Osm.clampTackle("Normal", "Médio"))
        assertEquals("Agressivo", Osm.clampTackle("Agressivo", "Brando"))
    }

    // ------------------------------------------------------------------ telas do próprio app

    @Test fun ownAppScreensAreRecognized() {
        assertTrue(OwnApp.looksLikeApp("Elenco Controles O número na camisa é a força do titular. Marcação"))
        assertTrue(OwnApp.looksLikeApp("S2• Levski Sofia Bulgária Hoje Sessões Slots Diretor Ajustes"))
        assertTrue(!OwnApp.looksLikeApp("Estilo de jogo Jogar pelas alas Táticas por sector Pressão Equilibrado"))
    }

    @Test fun squadHeaderIsNotSeasonEnd() {
        assertTrue(!SeasonScreens.isSeasonScreen("Posição: 13 Nasaf Nota mais alta: Jogador estrela: Melhor marcador: Número 22 atribuido!"))
    }
}
