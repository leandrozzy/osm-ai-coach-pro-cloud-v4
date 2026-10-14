package com.osmaicoach.collector

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dados dos desenhos: quadro dos treinadores e cartão de fim de competição. */
class VisualTest {
    private fun p(name: String, cat: String?, training: Boolean? = null) =
        PlayerEntity(1, "MY", Txt.key(name), name, 25, cat, cat, 80, "1M", training, null, 0L)

    @Test fun trainingBoardShowsTheFiveTrainers() {
        val now = 10L * 86400000L
        val screen = JSONObject()
            .put("GOL", JSONObject().put("name", "Alisson").put("state", "active").put("at", now))
            .put("DEF", JSONObject().put("name", JSONObject.NULL).put("state", "empty").put("at", now))
            .put("ATA", JSONObject().put("name", "Haaland").put("state", "done").put("at", now - 3L * 86400000L))
        val seats = TrainBoard.seats(screen, emptyList(), now)
        assertEquals(listOf("GOL", "DEF", "MEI", "ATA", "UNI"), seats.map { it.role })
        assertEquals("active", seats[0].state)
        assertEquals("Alisson", seats[0].name)
        assertEquals("empty", seats[1].state)
        assertNull(seats[1].name)
        assertEquals("unknown", seats[2].state)
        assertEquals("done", seats[3].state)
        assertFalse(seats[3].fresh)
        assertEquals("unknown", seats[4].state)
    }

    @Test fun manualTrainingFillsUnreadSeats() {
        val seats = TrainBoard.seats(null, listOf(p("Rodri", "MEI", true), p("Saka", "ATA", true), p("Foden", "MEI", true), p("Dias", "DEF")), 0L)
        assertEquals("Rodri", seats.first { it.role == "MEI" }.name)
        assertEquals("Saka", seats.first { it.role == "ATA" }.name)
        // segundo MEI vai para o universal
        assertEquals("Foden", seats.first { it.role == "UNI" }.name)
        assertEquals("unknown", seats.first { it.role == "DEF" }.state)
    }

    @Test fun seasonCardFromStoredSentences() {
        // leitura antiga (só as frases guardadas) do S4 da época passada
        val board = listOf(
            "Direção: \"Estou desiludido com os resultados. Aqui está a sua carta de demissão!\"",
            "Valor do plantel no fim: 62,1M (8º da liga).",
            "Campeão da liga: Colo-Colo.",
            "Fim da competição: Universidad terminou em 9º com 7 pts (gols 10-15)."
        )
        val i = SeasonCard.from(null, board, emptyMap())
        assertNotNull(i)
        i!!
        assertEquals(9, i.rank)
        assertEquals(7, i.points)
        assertEquals("10-15", i.goals)
        assertEquals("Colo-Colo", i.champion)
        assertEquals("62,1M", i.value)
        assertEquals(8, i.valueRank)
        assertTrue(i.fired)
        assertEquals(-1, SeasonCard.mood(i.board))
    }

    @Test fun seasonCardPrefersStructuredSummary() {
        val s = JSONObject().put("rank", 2).put("points", 21).put("champion", "Santos").put("value", "80M")
        val i = SeasonCard.from(s, emptyList(), emptyMap())!!
        assertEquals(2, i.rank)
        assertEquals(21, i.points)
        assertEquals("Santos", i.champion)
        assertFalse(i.fired)
        assertNull(SeasonCard.from(null, emptyList(), emptyMap()))
        assertEquals(1, SeasonCard.mood("Parabéns, o objetivo foi cumprido!"))
    }
}
