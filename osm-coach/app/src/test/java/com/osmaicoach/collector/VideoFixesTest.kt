package com.osmaicoach.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Casos vistos no vídeo de navegação (S1 a S4). */
class VideoFixesTest {
    // ------------------------------------------------------------------ S3: jogadores fantasmas

    @Test fun buttonTextGluedToNamesIsRemoved() {
        assertEquals("B. Abdikholikov", PlayerName.clean("B. Abdikholikov Atribuir numero"))
        assertEquals("B. Abdikholikov", PlayerName.clean("B. Abdikholikov MAIS EM BREVE"))
        assertEquals("Aubameyang", PlayerName.clean("Aubameyang Ata"))
        assertEquals("Aubameyang", PlayerName.clean("189GOLAubameyang JOGO RAPIDO"))
    }

    @Test fun ocrGarbageIsNotAPlayer() {
        assertNull(PlayerName.clean("MdLK"))
        assertNull(PlayerName.clean("D. AUUIKIIUIKUy"))
        assertNull(PlayerName.clean("ohl"))
        assertNull(PlayerName.clean("RAbdikhoikov"))
    }

    @Test fun realNamesStayAsTheyAre() {
        for (n in listOf("Cepeda", "Gibbs-White", "de Paula", "J. Timber", "Mac Allister", "B. Fernandes", "Alexander-Arnold", "Ødegaard", "M. Gómez", "Sylla")) {
            assertEquals(n, PlayerName.clean(n))
        }
    }

    // ------------------------------------------------------------------ S4: competição encerrada

    private fun f(vararg kv: Pair<String, String>) = kv.associate { it.first to StoredField(it.second, 0.9, 0L) }

    private fun m(round: Int, result: String?) = MatchEntity(
        4, "L$round", "Jornada $round", round, "0$round/10/26", null, true, if (result != null) 1 else null,
        if (result != null) 0 else null, result, "Rival $round", null, 0L
    )

    @Test fun battleWithAllRoundsPlayedHasNoNextGame() {
        val now = System.currentTimeMillis()
        val ms = (1..10).map { m(it, "V") }
        // central "10/10" e o app achando que há a rodada 11 contra o último rival
        assertTrue(Fixtures.seasonEnded(f(K.ROUND_TOTAL to "10", K.ROUND_DONE to "10", K.ROUND to "11"), ms, now))
        // só a rodada "seguinte" além do total já basta
        assertTrue(Fixtures.seasonEnded(f(K.ROUND_TOTAL to "10", K.ROUND to "11"), emptyList(), now))
        // calendário todo com placar
        assertTrue(Fixtures.seasonEnded(f(K.ROUND_TOTAL to "10"), ms, now))
    }

    @Test fun leagueInProgressIsNotEnded() {
        val now = System.currentTimeMillis()
        val ms = (1..31).map { m(it, "V") } + (32..34).map { m(it, null) }
        assertFalse(Fixtures.seasonEnded(f(K.ROUND_TOTAL to "34", K.ROUND_DONE to "31", K.ROUND to "32"), ms, now))
        // jogo marcado para daqui a pouco mantém aberto mesmo com a central dizendo "10/10"
        assertFalse(
            Fixtures.seasonEnded(f(K.ROUND_TOTAL to "10", K.ROUND_DONE to "10", K.MATCH_AT to (now + 3600000L).toString()), emptyList(), now)
        )
        // sem total de rodadas não dá para dizer que acabou
        assertFalse(Fixtures.seasonEnded(f(K.ROUND_DONE to "10"), emptyList(), now))
    }

    @Test fun seasonEndAndBoardScreensAreRecognized() {
        assertTrue(SeasonScreens.isSeasonScreen("A época acabou. Os teus jogadores estão a gozar o seu merecido descanso"))
        assertTrue(SeasonScreens.seasonOver("A época acabou. Os teus jogadores estão a gozar"))
        assertTrue(SeasonScreens.isSeasonScreen("Mensagem da direção: o objetivo foi cumprido"))
        assertFalse(SeasonScreens.isSeasonScreen("Formação 4-3-3 A Marcação À zona"))
    }

    // ------------------------------------------------------------------ S1: apelido de rival CPU

    @Test fun garbageNicknameIsRejected() {
        assertFalse(Evidence.plausibleNick("NNIV sARY,K"))
        assertTrue(Evidence.plausibleNick("JUNIOR RODRIGUES 2021"))
        assertTrue(Evidence.plausibleNick("el chiri_2"))
        assertTrue(Evidence.plausibleNick("Fredis Landa"))
    }

    @Test fun screenLabelsAreNotNicknames() {
        // plantel de um time de CPU: a linha sob o nome do time é um rótulo
        assertFalse(Evidence.plausibleNick("Melhor marcador:"))
        assertFalse(Evidence.plausibleNick("Melhor marcador"))
        assertFalse(Evidence.plausibleNick("Valor do plantel"))
        assertFalse(Evidence.plausibleNick("Jogador estrela"))
        val ev = org.json.JSONObject()
        Evidence.add(ev, K.RIVAL_NICK, "Melhor marcador:", "SQUAD", 1L)
        Evidence.add(ev, K.RIVAL_NICK, "el chiri_2", "CALENDAR", 1L)
        assertTrue(Evidence.purgeNicks(ev))
        assertEquals("el chiri_2", Evidence.best(ev, K.RIVAL_NICK)?.value)
    }
}
