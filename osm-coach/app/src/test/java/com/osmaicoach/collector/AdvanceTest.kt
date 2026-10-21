package com.osmaicoach.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** S4 em 21/10: J6 começou às 18:42 sem placar lido; o app tem que passar para o J7 (CD Nacional, 22/10). */
class AdvanceTest {
    private fun m(r: Int, date: String, opp: String, nick: String?, res: String? = null) =
        MatchEntity(4, "L$r", "Jornada $r", r, date, null, r % 2 == 1, null, null, res, opp, nick, 0L)

    private val ms = listOf(m(5, "20/10/26", "FC Alverca", "Mbappé Glazer"), m(6, "21/10/26", "Casa Pia", "Daniel Mustaqim"), m(7, "22/10/26", "CD Nacional", "creedinho88"))

    private fun at(d: Int, h: Int, min: Int): Long = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, d, h, min, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test fun movesToNextRoundAfterKickoff() {
        val now = at(21, 19, 21)
        val adv = Fixtures.advanceAfterKickoff(ms, Fixtures.Clock(6, at(21, 18, 42), 4), now)
        assertNotNull(adv)
        assertEquals(7, adv!!.clock.round)
        assertEquals(at(22, 18, 42), adv.clock.at)
        assertEquals("CD Nacional", adv.match.opponent)
    }

    @Test fun staysBeforeKickoffAndInFirstMinutes() {
        assertNull(Fixtures.advanceAfterKickoff(ms, Fixtures.Clock(6, at(21, 18, 42), 4), at(21, 18, 0)))
        assertNull(Fixtures.advanceAfterKickoff(ms, Fixtures.Clock(6, at(21, 18, 42), 4), at(21, 18, 47)))
        assertTrue(Fixtures.advanceAfterKickoff(ms, Fixtures.Clock(7, at(22, 18, 42), 4), at(23, 9, 0)) == null)
    }
}
