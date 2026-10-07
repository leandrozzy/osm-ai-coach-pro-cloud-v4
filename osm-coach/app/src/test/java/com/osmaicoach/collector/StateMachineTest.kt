package com.osmaicoach.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StateMachineTest {
    private fun sm(): SlotStateMachine {
        val m = SlotStateMachine()
        m.onHub(Fx.hubCards())
        return m
    }

    private fun enter(m: SlotStateMachine, team: String, round: Int): Assignment =
        m.onScreen(ScreenType.PREGAME, listOf(team), round, null, emptyList())

    @Test fun centralToS1ThenInheritsUntilReturningToCentral() {
        val m = sm()
        assertEquals(1, enter(m, "Tobol", 25).slot)
        assertEquals(1, m.onScreen(ScreenType.SQUAD, emptyList(), null, "Tobol", emptyList()).slot)
        assertEquals(1, m.onScreen(ScreenType.MARKET, emptyList(), null, null, emptyList()).slot)
        m.onHub(Fx.hubCards())
        assertNull(m.current)
        assertNull(m.onScreen(ScreenType.MARKET, emptyList(), null, null, emptyList()).slot)
    }

    @Test fun centralToS2() {
        val m = sm()
        assertEquals(2, enter(m, "Levski Sofia", 21).slot)
    }

    @Test fun centralToS3() {
        val m = sm()
        assertEquals(3, enter(m, "Nasaf", 10).slot)
    }

    @Test fun centralToS4ByRoundWhenHubShowsGroupNameInsteadOfTeam() {
        val m = sm()
        val a = enter(m, "Universidad de Chile", 4)
        assertEquals(4, a.slot)
        assertEquals(0.65, a.confidence, 0.001)
    }

    @Test fun adsSystemUiAndLauncherNeverChangeSlot() {
        val m = sm()
        enter(m, "Tobol", 25)
        assertEquals(1, m.onScreen(ScreenType.NON_OSM, emptyList(), null, null, emptyList()).slot)
        assertEquals(1, m.onScreen(ScreenType.NOISE, emptyList(), null, null, emptyList()).slot)
        assertEquals(1, m.current)
    }

    @Test fun unknownPregameDoesNotInventASlot() {
        val m = sm()
        val a = enter(m, "Time Misterioso", 99)
        assertNull(a.slot)
    }

    @Test fun recognizesSlotFromStoredIdentityWithoutHub() {
        val m = SlotStateMachine()
        val known = listOf(SlotIdentity(3, setOf("nasaf", "uzmvsbay"), 10), SlotIdentity(1, setOf("tobol"), 25))
        val a = m.onScreen(ScreenType.PREGAME, listOf("Nasaf"), 10, null, known)
        assertEquals(3, a.slot)
    }

    @Test fun ambiguousRoundOnlyReturnsNull() {
        val m = SlotStateMachine()
        val known = listOf(SlotIdentity(1, setOf("aaaa"), 5), SlotIdentity(2, setOf("bbbb"), 5))
        assertNull(m.onScreen(ScreenType.PREGAME, listOf("Outro Nome"), 5, null, known).slot)
    }

    @Test fun restoredStateKeepsSlotAfterProcessDeath() {
        val m = SlotStateMachine()
        m.restore(2, false, emptyList())
        assertEquals(2, m.onScreen(ScreenType.CALENDAR, emptyList(), null, null, emptyList()).slot)
    }
}
