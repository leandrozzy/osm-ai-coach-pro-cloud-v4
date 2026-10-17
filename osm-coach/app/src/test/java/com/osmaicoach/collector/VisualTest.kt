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
            .put("GOL", JSONObject().put("name", "ALISSON").put("state", "active").put("at", now))
            .put("DEF", JSONObject().put("name", JSONObject.NULL).put("state", "empty").put("at", now))
            .put("ATA", JSONObject().put("name", "ERLING HAALAND").put("state", "done").put("at", now - 3L * 86400000L))
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

    @Test fun trainingNamesMatchTheSquad() {
        val now = 10L * 86400000L
        val mine = listOf(p("F. López", "MEI"), p("Guéhi", "DEF"), p("Rui Silva", "GOL"), p("Mbeumo", "ATA"), p("Pau López", "GOL"))
        val screen = JSONObject()
            .put("GOL", JSONObject().put("name", "RUI SILVA").put("state", "active").put("at", now))
            .put("DEF", JSONObject().put("name", "MARC GUEHI").put("state", "active").put("at", now))
            .put("MEI", JSONObject().put("name", "FERMÍN LÓPEZ").put("state", "active").put("at", now))
            .put("ATA", JSONObject().put("name", "BRYAN MBEUMO").put("state", "active").put("at", now))
            // nome em letra normal no universal: veio de outra janela (o usuário só tem 4 treinando)
            .put("UNI", JSONObject().put("name", "Guéhi").put("state", "active").put("at", now - 3600000L))
        val seats = TrainBoard.seats(screen, mine, now)
        assertEquals(Txt.key("Rui Silva"), seats.first { it.role == "GOL" }.key)
        assertEquals(Txt.key("Guéhi"), seats.first { it.role == "DEF" }.key)
        assertEquals(Txt.key("F. López"), seats.first { it.role == "MEI" }.key)
        assertEquals(Txt.key("Mbeumo"), seats.first { it.role == "ATA" }.key)
        assertEquals("unknown", seats.first { it.role == "UNI" }.state)
        assertEquals(4, TrainBoard.activeKeys(seats).size)
        assertFalse(TrainBoard.plausible("Guéhi"))
        assertTrue(TrainBoard.plausible("19 BUKAYO SAKA"))
    }

    @Test fun manualCorrectionWinsOverTheTrainingScreen() {
        val now = 10L * 86400000L
        val mine = listOf(p("Aubameyang", "ATA"), p("Almoez Ali", "ATA"), p("Nsoki", "DEF"))
        val screen = JSONObject()
            .put("ATA", JSONObject().put("name", "ALMOEZ ALI").put("state", "active").put("at", now))
            .put("UNI", JSONObject().put("name", "PIERRE-EMERICK AUBAMEYANG").put("state", "active").put("at", now))
            .put("DEF", JSONObject().put("name", JSONObject.NULL).put("state", "empty").put("at", now))
        // "Não está treinando" no Aubameyang e "Está treinando" no Nsoki
        val seats = TrainBoard.seats(screen, mine, now, mapOf(Txt.key("Aubameyang") to false, Txt.key("Nsoki") to true))
        assertEquals("empty", seats.first { it.role == "UNI" }.state)
        assertEquals(Txt.key("Nsoki"), seats.first { it.role == "DEF" }.key)
        assertEquals(setOf(Txt.key("Almoez Ali"), Txt.key("Nsoki")), TrainBoard.activeKeys(seats))
        // sem correção, a leitura da tela vale
        assertEquals(2, TrainBoard.activeKeys(TrainBoard.seats(screen, mine, now)).size)
    }

    @Test fun samePlayerInTwoTrainersKeepsTheNewestRead() {
        val now = 10L * 86400000L
        val mine = listOf(p("Guéhi", "DEF"))
        val screen = JSONObject()
            .put("UNI", JSONObject().put("name", "MARC GUEHI").put("state", "active").put("at", now - 7200000L))
            .put("DEF", JSONObject().put("name", "MARC GUEHI").put("state", "active").put("at", now))
        val seats = TrainBoard.seats(screen, mine, now)
        assertEquals("active", seats.first { it.role == "DEF" }.state)
        assertEquals("unknown", seats.first { it.role == "UNI" }.state)
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
