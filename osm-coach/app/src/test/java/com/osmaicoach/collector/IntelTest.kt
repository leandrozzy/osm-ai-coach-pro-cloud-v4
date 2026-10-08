package com.osmaicoach.collector

import java.util.Calendar
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntelTest {
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
        val c = Calendar.getInstance()
        c.set(y, mo - 1, d, h, mi, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun m(
        round: Int, opp: String?, result: String? = null, date: String? = null, time: String? = null,
        label: String = "Jornada $round", readAt: Long = 0L
    ) = MatchEntity(
        1, "L$round", label, round, date, time, true,
        if (result == null) null else if (result == "V") 2 else if (result == "E") 1 else 0,
        if (result == null) null else 1, result, opp, null, readAt
    )

    @Test fun cupStageIsNotATeam() {
        assertTrue(Fixtures.isStage("Meias finais"))
        assertTrue(Fixtures.isStage("Quartos de final"))
        assertTrue(Fixtures.isStage("Final"))
        assertFalse(Fixtures.isStage("FC Zhenis Astana"))
        assertFalse(Fixtures.isStage("Finlandia FC"))
        val cup = m(24, "Meias finais", date = "21/10/26")
        assertTrue(Fixtures.isCup(cup))
        assertNull(Fixtures.opponent(cup))
        assertEquals("Meias finais", Fixtures.stage(cup))
    }

    @Test fun cupLossVoidsTheNextCupGames() {
        val ms = listOf(
            m(20, "Quartos de final", result = "D", label = "Copa • Quartos de final"),
            m(22, "Dinamo", result = "V"),
            m(24, "Meias finais", date = "21/10/26"),
            m(25, "FC Zhenis Astana", time = "22:18", readAt = at(2026, 10, 21, 20, 0))
        )
        assertEquals(20, Fixtures.cupOutRound(ms))
        assertTrue(Fixtures.void(ms[2], ms))
        assertFalse(Fixtures.void(ms[3], ms))
        // a semifinal (data passada, copa perdida) não é o próximo jogo nem pede resultado
        val now = at(2026, 10, 21, 21, 0)
        assertEquals(25, Fixtures.next(ms, now)?.round)
        assertNull(Fixtures.awaitingResult(ms, emptySet(), now))
    }

    @Test fun resultIsAskedOnlyAfterTheCalendarTimePasses() {
        val readAt = at(2026, 10, 21, 20, 0)
        val ms = listOf(m(25, "FC Zhenis Astana", time = "22:18", readAt = readAt))
        assertNull(Fixtures.awaitingResult(ms, emptySet(), at(2026, 10, 21, 22, 0)))
        assertEquals(25, Fixtures.awaitingResult(ms, emptySet(), at(2026, 10, 21, 22, 30))?.round)
        // análise do jogo já leu o placar: não pede de novo
        assertNull(Fixtures.awaitingResult(ms, setOf(25), at(2026, 10, 21, 22, 30)))
        // card sem data e sem hora: nunca pede
        assertNull(Fixtures.awaitingResult(listOf(m(26, "X")), emptySet(), at(2026, 12, 1, 0, 0)))
    }

    @Test fun backgroundTextIsNeverANick() {
        assertFalse(Evidence.plausibleNick("25 Anniversary"))
        assertFalse(Evidence.plausibleNick("Aniversário"))
        assertFalse(Evidence.plausibleNick("Jornada 12"))
        assertTrue(Evidence.plausibleNick("amar111_13"))
        assertTrue(Evidence.plausibleNick("Joel II Gonzalez"))
    }

    @Test fun nickNeedsTwoIndependentScreens() {
        val j = JSONObject()
        Evidence.add(j, K.RIVAL_NICK, "amar111_13", "PREGAME", 1L)
        assertNull(Evidence.confirmed(j, K.RIVAL_NICK))
        Evidence.add(j, K.RIVAL_NICK, "amar11l_13", "CALENDAR", 2L)
        val ok = Evidence.confirmed(j, K.RIVAL_NICK)
        assertNotNull(ok)
        assertEquals("amar111_13", ok!!.value)
        assertEquals(2, ok.sources.size)
        // a etiqueta "Apelido [S3]" da análise sozinha já confirma
        val r = JSONObject()
        Evidence.add(r, K.RIVAL_NICK, "leo_fc", "REPORT", 1L)
        assertNotNull(Evidence.confirmed(r, K.RIVAL_NICK))
    }

    @Test fun divergentReadingsAreKeptApart() {
        val j = JSONObject()
        Evidence.add(j, K.RIVAL_TEAM, "Levski", "PREGAME", 1L)
        Evidence.add(j, K.RIVAL_TEAM, "Ludogorets", "CALENDAR", 2L)
        assertEquals(2, Evidence.values(j, K.RIVAL_TEAM).size)
    }

    @Test fun winProbabilityFollowsStrengthAndContext() {
        val even = WinModel.predict(WinModel.Input(80, 80))!!
        assertEquals(100, even.win + even.draw + even.loss)
        assertTrue(kotlin.math.abs(even.win - even.loss) <= 2)
        val home = WinModel.predict(WinModel.Input(80, 80, home = true))!!
        assertTrue(home.win > even.win)
        val strong = WinModel.predict(WinModel.Input(90, 72, home = true, recent = listOf("V", "V", "V")))!!
        assertTrue(strong.win >= 70)
        // sempre sobra chance de zebra
        val huge = WinModel.predict(WinModel.Input(95, 50, home = true))!!
        assertTrue(huge.win <= 92)
        val weak = WinModel.predict(WinModel.Input(70, 85, home = false, rivalHuman = true))!!
        assertTrue(weak.loss > weak.win)
        assertNull(WinModel.predict(WinModel.Input(null, null)))
    }

    private val plan = WinModel.Plan("4-4-2", "Jogo de passe", 50, 50, 50, "À zona", "Não", "Normal", "Atacar apenas", "Manter posições", 80.0, 80.0, 80.0, 80.0)

    @Test fun theGeneratedTacticChangesThePrediction() {
        val base = WinModel.Input(80, 80, rivalAtk = 80, rivalMid = 80, rivalDef = 80, rivalGol = 80, referee = "Rigoroso", rivalFormation = "4-3-3", rivalStyle = "Contra-ataque")
        val normal = WinModel.predict(base.copy(plan = plan))!!
        val aggressive = WinModel.predict(base.copy(plan = plan.copy(tackle = "Agressivo")))!!
        assertTrue(aggressive.win < normal.win)
        // linha de impedimento contra quem joga em contra-ataque é arriscada
        val trap = WinModel.predict(base.copy(plan = plan.copy(offside = "Sim")))!!
        assertTrue(trap.loss > normal.loss)
        assertTrue(normal.factors.isNotEmpty())
    }

    @Test fun counterAttackPaysOffAgainstAnAttackingStrongerRival() {
        val base = WinModel.Input(75, 82, myMid = 72, rivalAtk = 84, rivalMid = 82, rivalDef = 80, rivalFormation = "4-3-3", rivalStyle = "Jogo de passe")
        val passe = WinModel.expectedPoints(base.copy(plan = plan.copy(formation = "4-5-1", style = "Jogo de passe")))!!
        val contra = WinModel.expectedPoints(base.copy(plan = plan.copy(formation = "4-5-1", style = "Contra-ataque")))!!
        assertTrue(contra > passe)
    }

    @Test fun tacticHistoryMovesThePrediction() {
        val base = WinModel.predict(WinModel.Input(80, 80))!!
        val good = WinModel.predict(WinModel.Input(80, 80, tacticRecord = listOf("V", "V", "V", "E")))!!
        val bad = WinModel.predict(WinModel.Input(80, 80, tacticRecord = listOf("D", "D", "E")))!!
        assertTrue(good.win > base.win)
        assertTrue(bad.win < base.win)
    }
}
