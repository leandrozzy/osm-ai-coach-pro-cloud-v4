package com.osmaicoach.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Texto real lido no celular (diagnóstico de 14/10): fim da batalha do S4 e pré-jogo do S3. */
class RealScreensTest {
    /** "texto@x,y | texto@x,y" (formato do registro do app) -> resultado do OCR. */
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

    private val SUMMARY = "10@0.02,0.76 | D NUBLENSE@0.07,0.58 | O'HIGGINS@0.07,0.62 | COQUIMBO UNIDO@0.08,0.67 | UNIVERSIDAD DE CHILE@0.09,0.71 | 85@0.09,0.83 | UNIVERSIDAD DE CONCEPCIÓN@0.10,0.76 | 52EED@0.08,0.87 | 14.8M@0.13,0.04 | 2/3 MISSÕES@0.08,0.95 | CLASSIFICAÇÃO@0.17,0.53 | 141@0.22,0.04 | CAMPEÃO DA LIGA@0.22,0.21 | COLO-COL0@0.22,0.43 | FABRIG37@0.22,0.46 | DIREÇÃO@0.24,0.88 | FREE@0.28,0.05 | 13@0.32,0.58 | 11@0.32,0.62 | 10@0.32,0.67 | 6@0.32,0.76 | 1@0.35,0.59 | 3@0.35,0.69 | 4@0.35,0.74 | fabrig37@0.40,0.59 | P Samuel Aires 1@0.40,0.64 | Wesley07Fernandes@0.42,0.69 | Fredis Landa@0.41,0.74 | MELHOR MARCADOR@0.56,0.21 | UMA EPOCA ABSOLUTAMENTE MISERAVEL! E CONSIDERAS-TE UM TREINADOR?@0.35,0.91 | LAMINE YAMAL@0.56,0.43 | LISTA DE TREINADORES@0.50,0.54 | 5 GOLOS@0.56,0.46 | BAY@0.64,0.59 | UZM@0.64,0.64 | UZM@0.64,0.69 | BAY@0.64,0.74 | NOVA ÉPOCA@0.83,0.17 | VINGA-TE NA TUA PRÖXIMA ÉPOCA!@0.83,0.28 | Esta liga termina dentro de 12h 55m 395@0.83,0.95"
    private val STANDINGS = "Classificação@0.04,0.14 | 1@0.01,0.34 | 5@0.02,0.45 | 6@0.02,0.55 | 7@0.02,0.65 | 8@0.02,0.75 | 9@0.02,0.85 | 10@0.02,0.95 | Clube@0.07,0.22 | Lista de treinadores@0.12,0.13 | yuTCTIIGITUCS@0.13,0.27 | Huachipato@0.11,0.33 | Fredis Landa@0.11,0.37 | Deportes La Serena@0.12,0.43 | ChinoM10@0.10,0.47 | CD Ñublense@0.11,0.53 | natan bianque 5@0.12,0.57 | O'Higgins@0.10,0.63 | UzM Roron0a Zor0@0.12,0.67 | Coquimbo Unido@0.12,0.75 | Universidad de Chile@0.12,0.83 | leandrozzy@0.11,0.87 | 14,8M@0.18,0.04 | Edson Eduardo MM@0.12,0.97 | Valor do plantel@0.21,0.13 | Universidad de Concepción@0.14,0.93 | 141@0.27,0.04 | FREE@0.33,0.05 | Melhores Marcadores Assistênias Os que mais contribuem@0.40,0.13 | Jogadores melhores cdassificados@0.62,0.14 | Séries@0.73,0.13 | Grupo@0.74,0.22 | BAY@0.74,0.35 | BAY@0.74,0.45 | UZM@0.74,0.55 | UZM@0.74,0.65 | UZM@0.74,0.85 | BAY@0.74,0.95 | Golos@0.79,0.13 | Jog@0.79,0.22 | 10@0.79,0.35 | 10@0.79,0.45 | 10@0.79,0.55 | 10@0.79,0.65 | 10@0.79,0.75 | 10@0.79,0.85 | 10@0.79,0.95 | V@0.81,0.22 | 5@0.81,0.35 | 3@0.81,0.65 | 3@0.81,0.75 | 2@0.81,0.85 | 1@0.81,0.95 | Estatísticas@0.86,0.13 | E@0.84,0.22 | 1@0.84,0.35 | 1@0.84,0.55 | 3 3@0.86,0.45 | 2@0.84,0.65 | D@0.87,0.22 | 1@0.84,0.75 | 4@0.87,0.35 | 5@0.87,0.55 | 5@0.87,0.65 | 6@0.87,0.75 | 1 7@0.86,0.85 | 3 6@0.86,0.95 | Pts@0.91,0.22 | 16@0.91,0.35 | 15@0.91,0.45 | 13@0.91,0.55 | 11@0.91,0.65 | 10@0.91,0.75 | 7@0.91,0.85 | 6@0.91,0.95 | iji@0.96,0.04 | Gm-Gs@0.97,0.22 | 11-14@0.97,0.35 | 16-11@0.97,0.45 | 12-15@0.97,0.55 | 12-15@0.97,0.65 | 9-15@0.97,0.75 | 10-15@0.97,0.85 | 5-19@0.97,0.95"
    private val VALUE = "Classifica,ão@0.04,0.14 | 6@0.02,0.61 | 8@0.02,0.81 | 9@0.02,0.91 | Clube@0.05,0.23 | Lista de treinadores@0.12,0.13 | Audax ltaliano@0.09,0.29 | Samuel Aires 1@0.10,0.33 | Colo-Colo@0.08,0.39 | fabrig37@0.08,0.43 | CD Ñublense@0.09,0.49 | natan biangue 5@0.10,0.53 | Deportes La Serena@0.10,0.59 | ChinoM10@0.09,0.63 | O'Higgins@0.09,0.69 | UzM RoronOa Zor0@0.10,0.73 | Universidad de Chile@0.11,0.79 | leandrozzy@0.09,0.83 | Huachipato@0.09,0.89 | Fredis Landa@0.09,0.93 | Universidad de Concención@0.12,0.99 | 14,8M@0.18,0.04 | Valor do plante!l@0.21,0.13 | 141@0.27,0.04 | FREE@0.33,0.05 | Melhores Marcadores Assistênias@0.34,0.13 | Os que mais contribuem Jogadores melhores classificados@0.56,0.13 | Valor@0.43,0.23 | 223M@0.45,0.31 | (S 149M@0.44,0.41 | 138M@0.45,0.51 | 127M@0.45,0.61 | 101M@0.45,0.71 | 62,1M@0.45,0.81 | 57,8M@0.45,0.91 | Jogadores@0.69,0.23 | 23@0.69,0.31 | 26@0.69,0.41 | 19@0.69,0.51 | 16@0.69,0.61 | 20@0.69,0.71 | 17@0.69,0.81 | 19@0.69,0.91 | Séries@0.73,0.13 | Golos@0.79,0.13 | Estatísticas@0.86,0.13 | Valor médio do jogador@0.86,0.23 | O9,7M@0.96,0.31 | 5,7M@0.98,0.41 | S73M@0.97,0.51 | S8,0M@0.96,0.61 | O5,1M@0.97,0.71 | 6)3,7M@0.96,0.81 | O3,1M@0.97,0.91"
    private val OVER = "14,8M@0.18,0.04 | 141@0.27,0.04 | A época acabou. Os teus jogadores estão a gozar o seu@0.17,0.18 | FREE O@0.33,0.05 | Demissão@0.97,0.95"
    private val PREGAME_S3 = "BRO GOLDyER@0.04,0.66 | JOGO RÄPIDO@0.10,0.65 | 85+@0.08,0.83 | meED@0.10,0.86 | 12,6M@0.13,0.04 | 2/3 MISSÕES@0.09,0.95 | UZM@0.18,0.48 | 141@0.22,0.04 | FREE@0.28,0.05 | Xorazm Urganch@0.23,0.37 | JUNIOR RODRIGUES 2021@0.24,0.41 | GIGPONTOS@0.20,0.72 | 398364@0.28,0.48 | 25 ANNIYERSARY@0.28,0.52 | 6d@0.32,0.62 | +1%@0.34,0.26 | SUPER CORPO TÉCNICO@0.30,0.88 | Jornada 17@0.50,0.13 | 1h 36m 145@0.50,0.18 | -VS.@0.50,0.32 | PREPARAÇÃO PARA O JOGO@0.44,0.65 | Arbitro@0.52,0.47 | BOM@0.52,0.72 | OLHEIRO, TREINADORES, MEDICA, ADVOGADOE ANALISTA DE DADOS MAIS RAPID0S!@0.41,0.91 | +2%@0.66,0.26 | 10h 03m 57s@0.64,0.83 | UZM@0.72,0.48 | 12 D SOGDIANA@0.72,0.83 | 14@0.69,0.92 | 13 NASAF@0.71,0.87 | 15@0.69,0.96 | Nasaf@0.77,0.36 | leandrozzy@0.78,0.41 | 1h 58m 1s@0.75,0.73 | 8@0.76,0.50 | FK ANDIJON@0.74,0.92 | XORAZM URGANCH@0.75,0.96 | 366980@0.82,0.48 | VENDER JOGADORES@0.92,0.74 | 13@0.99,0.83 | 12@0.99,0.87 | 9@0.99,0.92"

    @Test fun seasonSummaryGivesBoardMessageChampionAndEnd() {
        val o = ocr(SUMMARY)
        assertTrue(SeasonScreens.isSeasonScreen(o.fullText))
        val r = Parsers.seasonSummary(o, "Universidad de Chile")
        assertTrue(r.over)
        assertEquals("COLO-COL0", r.champion)
        assertNotNull(r.board)
        assertTrue(r.board!!.contains("MISERAVEL"))
        assertFalse(r.board!!.contains("VINGA-TE"))
    }

    @Test fun finalStandingsGiveMyPositionPointsAndGoals() {
        val r = Parsers.seasonSummary(ocr(STANDINGS), "Universidad de Chile")
        assertEquals(9, r.rank)
        assertEquals(7, r.points)
        assertEquals("10-15", r.goals)
    }

    @Test fun squadValueTableGivesMyValue() {
        val r = Parsers.seasonSummary(ocr(VALUE), "Universidad de Chile")
        assertEquals("62,1M", r.squadValue)
        assertEquals(8, r.valueRank)
        assertNull(r.rank)
    }

    @Test fun seasonOverMessageWithoutTeamNames() {
        val o = ocr(OVER)
        assertTrue(SeasonScreens.isSeasonScreen(o.fullText))
        assertTrue(Parsers.seasonSummary(o, null).over)
    }

    @Test fun pregameOfS3ReadsMyBonusOnTheRight() {
        val ex = Parsers.pregame(ocr(PREGAME_S3), null, 0L)
        assertEquals("Nasaf", ex.fields[K.TEAM]?.value)
        assertEquals("+2%", ex.fields[K.MY_BONUS]?.value)
        assertEquals("+1%", ex.fields[K.RIVAL_LOGIN_BONUS]?.value)
    }

    @Test fun typedBonusFromLastNightDoesNotHoldTodaysReading() {
        val now = 100L * 86400000L
        val typed = StoredField("+1%", 2.0, now - 9L * 3600000L)
        assertEquals("+2%", FieldMerge.merge(K.MY_BONUS, typed, Reading("+2%", 0.85), now)?.value)
    }
}
