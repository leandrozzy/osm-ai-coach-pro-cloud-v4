package com.osmaicoach.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {
    private fun p(name: String, cat: String, pos: String, s: Int, age: Int = 25, value: String = "5,0M", training: Boolean? = null) =
        PlayerEntity(1, "MY", Txt.key(name), name, age, pos, cat, s, value, training, null, 0L)

    private fun squad(): List<PlayerEntity> = listOf(
        p("Kelleher", "GOL", "GR", 88), p("Silva", "GOL", "GR", 86),
        p("Guehi", "DEF", "DC", 98), p("Koundé", "DEF", "DD", 91), p("Timber", "DEF", "DE", 88),
        p("Quansah", "DEF", "DC", 84), p("Kiwior", "DEF", "DC", 83), p("Williams", "DEF", "DE", 83),
        p("Foden", "MEI", "MCO", 91), p("Gibbs", "MEI", "MC", 90), p("Lopez", "MEI", "MC", 88),
        p("Guimaraes", "MEI", "MCD", 88), p("Reijnders", "MEI", "MC", 86), p("Scott", "MEI", "MC", 85, value = "12,5M"),
        p("Mbeumo", "ATA", "ED", 95), p("Saka", "ATA", "ED", 95), p("Barnes", "ATA", "EE", 88),
        p("Gyokeres", "ATA", "PL", 88), p("Zirkzee", "ATA", "PL", 85, value = "13,6M")
    )

    private fun input(my: Int?, rival: Int?, referee: String? = null, history: List<HistRow> = emptyList()) =
        TacticEngine.Input(squad(), my, rival, null, null, null, null, referee, null, history)

    @Test fun weakRivalMeansAttackingFourThreeThree() {
        val r = TacticEngine.recommend(input(91, 61))!!
        assertEquals("4-3-3", r.tactic.formation)
        assertTrue(r.tactic.mentality >= 75)
        assertEquals("Atacar apenas", r.tactic.advAttack)
        assertEquals(30, r.diff)
    }

    @Test fun strongRivalMeansSolidDefenseAndLowMentality() {
        val r = TacticEngine.recommend(input(70, 90))!!
        val lines = Formations.lines(r.tactic.formation)
        assertTrue(lines.first() >= 4)
        assertTrue(lines.last() <= 2)
        assertTrue(r.tactic.mentality <= 42)
        assertEquals("Ajudar a defender", r.tactic.advAttack)
    }

    @Test fun unknownRivalStrengthGivesBalancedTacticAndSaysSo() {
        val r = TacticEngine.recommend(input(91, null))!!
        assertNull(r.diff)
        assertEquals(55, r.tactic.mentality)
        assertTrue(r.tactic.notes.any { it.contains("ainda não lida") })
    }

    @Test fun strictRefereeAvoidsAggressiveTackleAndLenientAllowsIt() {
        assertEquals("Normal", TacticEngine.recommend(input(91, 61, "Rigoroso"))!!.tactic.tackle)
        assertEquals("Agressivo", TacticEngine.recommend(input(91, 61, "Brando"))!!.tactic.tackle)
    }

    @Test fun repeatedLossesWithAFormationMakeTheEngineAvoidIt() {
        val hist = listOf(HistRow("4-3-3", "x", "D"), HistRow("4-3-3", "x", "D"), HistRow("4-3-3", "x", "D"))
        val r = TacticEngine.recommend(input(91, 61, history = hist))!!
        assertFalse(r.tactic.formation == "4-3-3")
        assertTrue(r.tactic.notes.isNotEmpty())
    }

    @Test fun lineupUsesBestPlayersAndRightShape() {
        val (rows, sum) = TacticEngine.lineup("4-3-3", squad())!!
        assertEquals(4, rows.size)
        assertEquals("Kelleher", rows[0][0]?.name)
        assertEquals(4, rows[1].size)
        assertEquals(3, rows[2].size)
        assertEquals(3, rows[3].size)
        assertTrue(rows[1].any { it?.name == "Guehi" })
        assertTrue(sum > 900)
        assertNull(TacticEngine.lineup("9-9-9", squad()))
    }

    @Test fun learningStatsCountOnlyResolvedGames() {
        val h = listOf(HistRow("4-3-3", "a", "V"), HistRow("4-3-3", "a", "E"), HistRow("4-4-2", "a", "D"), HistRow("4-4-2", "a", null))
        val s = Learning.stats(h)
        val f433 = s.first { it.formation == "4-3-3" }
        assertEquals(2, f433.games)
        assertEquals(1, f433.v)
        assertEquals(1, s.first { it.formation == "4-4-2" }.games)
    }

    @Test fun marketSellsSurplusAndSwapsForAnUpgrade() {
        val listings = listOf(
            ListingEntity(1, "star", "Star", 24, "MC", "MEI", 93, "8,0M", "Clube", null, 0L),
            ListingEntity(1, "meh", "Meh", 30, "MC", "MEI", 85, "3,0M", "Clube", null, 0L)
        )
        val plan = MarketEngine.plan(squad(), listings, 5.0, 3)
        assertTrue(plan.sell.any { it.name == "Zirkzee" })
        val buy = plan.buy.first()
        assertEquals("Star", buy.name)
        assertEquals("Scott", buy.replaces)
        assertTrue(plan.sell.any { it.name == "Scott" })
        assertTrue(plan.sell.size <= 3)
        assertTrue(plan.steps.any { it.startsWith("Comprar Star") })
    }

    @Test fun trainingUsesTheRightTrainerPerPositionAndSkipsBusyOnes() {
        val base = squad().map { if (it.name == "Foden") it.copy(training = true) else it }
        val plan = MarketEngine.plan(base, emptyList(), 1.0, 4)
        val cat = base.associate { it.name to it.cat }
        for (t in plan.train) {
            val expected = when (cat[t.name]) {
                "ATA" -> "avançados"
                "MEI" -> "médios"
                "DEF" -> "defesas"
                else -> "guarda-redes"
            }
            assertEquals(expected, t.trainer)
        }
        assertFalse(plan.train.any { it.trainer == "médios" })
        assertTrue(plan.steps.any { it.contains("ocupado com Foden") })
    }

    @Test fun noAffordableUpgradeExplainsTheShortfall() {
        val tight = squad().filter { it.name != "Zirkzee" }
        val listings = listOf(ListingEntity(1, "star", "Star", 24, "MC", "MEI", 93, "20,0M", "Clube", null, 0L))
        val plan = MarketEngine.plan(tight, listings, 1.0, 0)
        assertTrue(plan.buy.isEmpty())
        assertNotNull(plan.steps.firstOrNull { it.contains("faltam") || it.contains("Nenhuma") })
    }
}
