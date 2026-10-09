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
        assertEquals("4-3-3", Formations.base(r.tactic.formation))
        // nome exato do OSM, com a variante
        assertTrue(r.tactic.formation in Formations.ALL)
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
        assertFalse(Formations.base(r.tactic.formation) == "4-3-3")
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

    @Test fun evenMatchAgainstHumanKeepsFourDefenders() {
        val inp = TacticEngine.Input(squad(), 74, 73, true, "4-3-3 B", null, null, null, false, emptyList())
        val r = TacticEngine.recommend(inp)!!
        val lines = Formations.lines(r.tactic.formation)
        assertEquals(4, lines.first())
        assertTrue(lines.last() <= 3)
        assertEquals("Jogo de passe", r.tactic.playStyle)
    }

    @Test fun sectorAdvantageInMidfieldFavorsMoreMidfielders() {
        val base = TacticEngine.Input(squad(), 74, 73, null, null, null, null, null, null, emptyList())
        val withEdge = base.copy(myMid = 90, rivalMid = 70)
        val r = TacticEngine.recommend(withEdge)!!
        val lines = Formations.lines(r.tactic.formation)
        assertTrue(10 - lines.first() - lines.last() >= 3)
        assertTrue(r.tactic.notes.any { it.contains("Meio-campo") })
    }

    @Test fun styleThatKeepsWinningIsChosenByTheHistory() {
        val hist = listOf(
            HistRow("4-4-2", "Remate à vista", "V"), HistRow("4-4-2", "Remate à vista", "V"), HistRow("4-4-2", "Remate à vista", "V")
        )
        val r = TacticEngine.recommend(input(74, 73, history = hist))!!
        assertEquals("Remate à vista", r.tactic.playStyle)
        assertTrue(r.tactic.notes.any { it.contains("pelo histórico") })
    }

    @Test fun contextStatsSeparateHumanAndHomeResults() {
        val h = listOf(
            HistRow("4-3-3", "a", "V", human = true, home = true),
            HistRow("4-3-3", "a", "D", human = true, home = false),
            HistRow("4-3-3", "a", "V", human = false, home = true)
        )
        val ctx = Learning.byContext(h)
        assertEquals(2, ctx.first { it.formation == "Contra humano" }.games)
        assertEquals(1, ctx.first { it.formation == "Contra CPU" }.games)
        assertEquals(2, ctx.first { it.formation == "Em casa" }.v)
        assertEquals(1, Learning.byStyle(h).size)
    }

    @Test fun formationNamesFollowTheGame() {
        assertEquals("4-3-3 B", Formations.canonical("4-3-3b"))
        assertEquals("4-4-2 A", Formations.canonical("4-4-2"))
        assertEquals("4-5-1", Formations.canonical("451"))
        assertEquals("4-3-3 A", Formations.variant("4-3-3", "Jogar pelas alas"))
        assertEquals("4-3-3 B", Formations.variant("4-3-3", "Jogo de passe"))
        assertNull(Formations.canonical("9-9-9"))
        Formations.learn("3-3-2-2")
        assertTrue("3-3-2-2" in Formations.ALL)
    }

    @Test fun sliderLabelsMatchTheGame() {
        // valores vistos no vídeo da tela "Define a tua tática"
        assertEquals("Não pressionar", Osm.pressureLabel(9))
        assertEquals("Ficar atrás", Osm.pressureLabel(27))
        assertEquals("Equilibrado", Osm.pressureLabel(51))
        assertEquals("Chegar perto do adversário", Osm.pressureLabel(70))
        assertEquals("Pressionar alto", Osm.pressureLabel(97))
        assertEquals("Super defensivo", Osm.mentalityLabel(6))
        assertEquals("Neutro", Osm.mentalityLabel(45))
        assertEquals("Ofensivo", Osm.mentalityLabel(77))
        assertEquals("Tudo ao ataque", Osm.mentalityLabel(81))
        assertEquals("Jogar atrás", Osm.tempoLabel(3))
        assertEquals("Construir de trás para a frente", Osm.tempoLabel(28))
        assertEquals("Fazer posse", Osm.tempoLabel(51))
        assertEquals("Passes rápidos", Osm.tempoLabel(75))
        assertEquals("Futebol ao primeiro toque", Osm.tempoLabel(100))
        assertEquals("Homem-a-homem", Osm.marking("Individual"))
        assertEquals("Extremo", Osm.tackle("Imprudente"))
        assertEquals(24, Formations.OSM.size)
    }

    @Test fun underdogPlaysCounterAttack() {
        assertEquals("Contra-ataque", TacticEngine.recommend(input(70, 90))!!.tactic.playStyle)
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
        for (t in plan.train.filter { it.trainer != "universal" }) {
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

    @Test fun radarShowsBestTargetPerPositionAndWhetherItFits() {
        val listings = listOf(
            ListingEntity(1, "star", "Star", 24, "MC", "MEI", 93, "8,0M", "Clube", null, 0L),
            ListingEntity(1, "gk", "Keeper", 27, "GR", "GOL", 95, "90,0M", "Clube", null, 0L)
        )
        val plan = MarketEngine.plan(squad(), listings, 2.0, 3)
        val mei = plan.radar.first { it.cat == "MEI" }
        assertEquals("Star", mei.name)
        assertTrue(mei.affordable)
        val gol = plan.radar.first { it.cat == "GOL" }
        assertFalse(gol.affordable)
    }

    @Test fun fiveTrainingsMeansUniversalTrainerIsBusy() {
        val busy = setOf("Foden", "Gibbs", "Mbeumo", "Guehi", "Kelleher")
        val base = squad().map { if (it.name in busy) it.copy(training = true) else it }
        val plan = MarketEngine.plan(base, emptyList(), 1.0, 4)
        assertEquals(5, plan.trainingActive)
        assertTrue(plan.train.none { it.trainer == "universal" })
        assertTrue(plan.steps.any { it.contains("Treinador universal ocupado com Gibbs") })
        assertTrue(plan.steps.any { it.contains("5 de 5") })
    }

    @Test fun freeUniversalTrainerGetsARecommendation() {
        val plan = MarketEngine.plan(squad(), emptyList(), 1.0, 4)
        assertEquals(0, plan.trainingActive)
        assertEquals(5, plan.train.size)
        assertEquals(1, plan.train.count { it.trainer == "universal" })
    }

    @Test fun foulsHistoryTurnsAggressiveTackleIntoNormal() {
        val hist = listOf(
            HistRow("4-3-3", "x", "V", fouls = 20), HistRow("4-3-3", "x", "V", fouls = 18), HistRow("4-3-3", "x", "V", fouls = 19)
        )
        val r = TacticEngine.recommend(input(91, 61, "Brando", hist))!!
        assertEquals("Normal", r.tactic.tackle)
        assertTrue(r.tactic.notes.any { it.contains("faltas") })
    }

    @Test fun tiredStarIsRotatedOutOfTheLineup() {
        val fit = mapOf(Txt.key("Mbeumo") to Pair(20, 80))
        val r = TacticEngine.recommend(input(91, 61).copy(fitness = fit))!!
        val names = r.rows.flatten().filterNotNull().map { it.name }
        assertFalse(names.contains("Mbeumo"))
        assertTrue(r.tactic.notes.any { it.contains("Rotação: Mbeumo") })
        val rested = TacticEngine.recommend(input(91, 61))!!
        assertTrue(rested.rows.flatten().filterNotNull().any { it.name == "Mbeumo" })
    }

    @Test fun stadiumPlanComparesUpgradeCostWithCash() {
        val st = "Capacidade: máximo • Relvado: concluir melhoria • Treino: 501K + 18h"
        val poor = mapOf(K.MY_STAD_STATUS to StoredField(st, 0.8, 1L), K.CASH to StoredField("120K", 0.8, 1L))
        val p1 = Director.stadiumPlan(poor)!!
        assertTrue(p1.contains("melhoria pronta"))
        assertTrue(p1.contains("faltam"))
        val rich = mapOf(K.MY_STAD_STATUS to StoredField(st, 0.8, 1L), K.CASH to StoredField("1,2M", 0.8, 1L))
        assertTrue(Director.stadiumPlan(rich)!!.contains("cabe no caixa agora"))
        assertNull(Director.stadiumPlan(emptyMap()))
    }

    @Test fun winForecastGrowsWithStrengthDifference() {
        assertTrue(Forecast.winPercent(30, true) > 85)
        assertTrue(Forecast.winPercent(-30, false) < 15)
        assertTrue(Forecast.winPercent(10, null) > Forecast.winPercent(0, null))
    }

    @Test fun noAffordableUpgradeExplainsTheShortfall() {
        val tight = squad().filter { it.name != "Zirkzee" }
        val listings = listOf(ListingEntity(1, "star", "Star", 24, "MC", "MEI", 93, "20,0M", "Clube", null, 0L))
        val plan = MarketEngine.plan(tight, listings, 1.0, 0)
        assertTrue(plan.buy.isEmpty())
        assertNotNull(plan.steps.firstOrNull { it.contains("faltam") || it.contains("Nenhuma") })
    }

    @Test fun clearlyWeakerRivalNeverGetsTwoStrikers() {
        // squad com meio-campo mais forte que o 3º atacante: antes saía 4-4-2
        val r = TacticEngine.recommend(input(84, 74))!!
        assertTrue(Formations.lines(r.tactic.formation).last() >= 3)
    }

    @Test fun tackleFollowsTheReferee() {
        assertEquals("Normal", Osm.clampTackle("Extremo", "Médio"))
        assertEquals("Normal", Osm.clampTackle("Agressivo", "Médio"))
        assertEquals("Normal", Osm.clampTackle("Extremo", null))
        assertEquals("Agressivo", Osm.clampTackle("Extremo", "Brando"))
        assertEquals("Cuidadoso", Osm.clampTackle("Cuidadoso", "Rigoroso"))
        assertEquals("Normal", TacticEngine.recommend(input(91, 61, "Médio"))!!.tactic.tackle)
        val (t, _) = TacticValidator.validate(
            org.json.JSONObject().put("formation", "4-3-3 A").put("pressure", 60).put("mentality", 70).put("tempo", 60).put("tackle", "Extremo"),
            "Médio"
        )
        assertEquals("Normal", t!!.tackle)
    }
}
