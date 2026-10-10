package com.osmaicoach.collector

import org.junit.Assert.assertEquals
import org.junit.Test

class ClassifierTest {
    @Test fun hubIsRecognizedWithoutTopBar() {
        assertEquals(ScreenType.HUB, ScreenClassifier.classify(Fx.ocr(*Fx.hubLines().toTypedArray()), false))
    }

    @Test fun adWithoutOsmTopBarIsNonOsm() {
        val ad = Fx.ocr(Fx.line("Candy Crush Saga", 0.5f, 0.3f), Fx.line("Jogar Agora", 0.8f, 0.9f), Fx.line("Install now", 0.5f, 0.6f))
        assertEquals(ScreenType.NON_OSM, ScreenClassifier.classify(ad, false))
    }

    @Test fun nearlyEmptyScreenIsNoise() {
        assertEquals(ScreenType.NOISE, ScreenClassifier.classify(Fx.ocr(Fx.line("OK", 0.5f, 0.5f)), true))
    }

    @Test fun marketTabs() {
        val o = Fx.ocr(Fx.line("Lista de transferências", 0.1f, 0.13f, 0.15f), Fx.line("Olheiro", 0.17f, 0.13f), Fx.line("Vender jogadores 1 / 4", 0.3f, 0.13f, 0.2f), Fx.line("Negociações", 0.4f, 0.13f))
        assertEquals(ScreenType.MARKET, ScreenClassifier.classify(o, true))
    }

    @Test fun calendarNeedsManyRounds() {
        val o = Fx.ocr(Fx.line("Jornada 1", 0.08f, 0.6f), Fx.line("Jornada 2", 0.25f, 0.6f), Fx.line("Jornada 3", 0.41f, 0.6f), Fx.line("Jornada 4", 0.58f, 0.6f))
        assertEquals(ScreenType.CALENDAR, ScreenClassifier.classify(o, true))
    }

    @Test fun squadHeader() {
        val o = Fx.ocr(Fx.line("Jogador", 0.08f, 0.5f), Fx.line("Idade", 0.55f, 0.5f), Fx.line("Pos", 0.62f, 0.5f), Fx.line("Valor", 0.95f, 0.5f))
        assertEquals(ScreenType.SQUAD, ScreenClassifier.classify(o, true))
    }

    @Test fun pregameScreen() {
        val o = Fx.ocr(Fx.line("Jornada 25", 0.5f, 0.12f), Fx.line("PREPARAÇÃO PARA O JOGO", 0.5f, 0.7f, 0.2f), Fx.line("JOGO RÁPIDO", 0.2f, 0.7f), Fx.line("TREINO", 0.8f, 0.7f))
        assertEquals(ScreenType.PREGAME, ScreenClassifier.classify(o, true))
    }

    @Test fun openSideMenuDoesNotTurnAnyScreenIntoMarket() {
        val o = Fx.ocr(
            Fx.line("Plantel", 0.9f, 0.3f), Fx.line("Equipa inicial", 0.9f, 0.35f, 0.1f), Fx.line("Tática", 0.9f, 0.4f),
            Fx.line("Especialistas", 0.9f, 0.45f), Fx.line("Lista de transferências", 0.9f, 0.55f, 0.15f), Fx.line("Olheiro", 0.9f, 0.6f)
        )
        assertEquals(ScreenType.OTHER_OSM, ScreenClassifier.classify(o, true))
    }

    @Test fun analysisScreenWithoutTopBarIsAReport() {
        val o = Fx.ocr(
            Fx.line("Mashal Mubarek", 0.2f, 0.27f, 0.2f), Fx.line("Pelo que pude ver, Mashal Mubarek deu ordens aos jogadores", 0.2f, 0.38f, 0.4f),
            Fx.line("Nível do estádio: 1", 0.1f, 0.51f, 0.12f), Fx.line("Formação: 4-3-3 A", 0.7f, 0.03f, 0.2f), Fx.line("Suplentes", 0.7f, 0.74f)
        )
        assertEquals(ScreenType.REPORT, ScreenClassifier.classify(o, false))
    }

    @Test fun matchResultScreenIsRecognized() {
        val o = Fx.ocr(
            Fx.line("Casa", 0.02f, 0.125f), Fx.line("Jornada 3", 0.5f, 0.125f, 0.08f), Fx.line("Fora", 0.98f, 0.125f),
            Fx.line("Primeira parte", 0.5f, 0.79f, 0.1f), Fx.line("Segunda parte", 0.5f, 0.95f, 0.1f), Fx.line("Rever", 0.5f, 0.4f)
        )
        assertEquals(ScreenType.RESULT, ScreenClassifier.classify(o, true))
    }

    @Test fun stadiumScreen() {
        val o = Fx.ocr(Fx.line("Capacidade", 0.5f, 0.3f), Fx.line("Nível 2", 0.5f, 0.35f), Fx.line("+414K receitas de bilheteria", 0.5f, 0.42f, 0.3f), Fx.line("Estádio", 0.2f, 0.1f))
        assertEquals(ScreenType.STADIUM, ScreenClassifier.classify(o, true))
    }

    @Test fun trainingPickerIsNotSquad() {
        val o = Fx.ocr(Fx.line("Seleciona um Avançado para treinar", 0.5f, 0.1f, 0.3f), Fx.line("Jogador", 0.1f, 0.2f), Fx.line("Idade", 0.55f, 0.2f), Fx.line("Valor", 0.9f, 0.2f))
        assertEquals(ScreenType.TRAINING, ScreenClassifier.classify(o, true))
    }

    @Test fun battlePregameWithSellButtonIsNotMarket() {
        val o = Fx.ocr(
            Fx.line("Jornada 7", 0.5f, 0.12f), Fx.line("18h 03m 42s", 0.5f, 0.18f), Fx.line("VS", 0.5f, 0.31f),
            Fx.line("Árbitro", 0.52f, 0.47f), Fx.line("JOGO RÁPIDO", 0.2f, 0.65f), Fx.line("PREPARAÇÃO PARA O JOGO", 0.5f, 0.65f, 0.2f),
            Fx.line("VENDER JOGADORES", 0.9f, 0.74f, 0.12f)
        )
        assertEquals(ScreenType.PREGAME, ScreenClassifier.classify(o, true))
    }
}
