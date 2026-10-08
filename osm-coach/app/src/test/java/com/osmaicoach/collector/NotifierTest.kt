package com.osmaicoach.collector

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifierTest {
    private val MIN = 60000L

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
        val c = Calendar.getInstance()
        c.set(y, mo - 1, d, h, mi, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    @Test fun calendarTimeWithoutDateIsToday() {
        val now = at(2026, 10, 7, 20, 0)
        assertEquals(at(2026, 10, 7, 22, 18), MatchClock.toMillis(null, "22:18", now))
    }

    @Test fun timeThatPassedLongAgoMeansTomorrow() {
        val now = at(2026, 10, 7, 23, 50)
        assertEquals(at(2026, 10, 8, 0, 30), MatchClock.toMillis(null, "00:30", now))
        assertEquals(at(2026, 10, 7, 22, 18), MatchClock.toMillis(null, "22:18", now))
    }

    @Test fun cardWithDateUsesThatDate() {
        val now = at(2026, 10, 7, 20, 0)
        assertEquals(at(2026, 10, 8, 21, 0), MatchClock.toMillis("08/10/26", "21:00", now))
    }

    @Test fun invalidTimesAreRejected() {
        assertNull(MatchClock.toMillis(null, "25:99", 0L))
        assertNull(MatchClock.toMillis(null, "abc", 0L))
    }

    @Test fun alarmsAreSetAroundTheMatchTime() {
        val now = at(2026, 10, 7, 19, 0)
        val next = at(2026, 10, 7, 22, 18)
        val a = Notifier.alarms(next, now).associate { it.kind to it.at }
        assertEquals(next - 20 * MIN, a["pre"])
        assertEquals(next - 60 * MIN, a["tactic"])
        assertEquals(next + 30 * MIN, a["result"])
    }

    @Test fun alarmsInThePastAreDropped() {
        val next = at(2026, 10, 7, 22, 18)
        val a = Notifier.alarms(next, next - 10 * MIN).map { it.kind }
        assertFalse("pre" in a)
        assertFalse("tactic" in a)
        assertTrue("result" in a)
    }

    @Test fun reminderIsDueOnlyInsideTheTwentyMinuteWindow() {
        val next = at(2026, 10, 7, 22, 18)
        assertFalse(Notifier.dueNow(next, next - 21 * MIN))
        assertTrue(Notifier.dueNow(next, next - 15 * MIN))
        assertFalse(Notifier.dueNow(next, next - 30000L))
        assertFalse(Notifier.dueNow(next, next + MIN))
    }

    @Test fun calendarTimeBeatsCountdownUnlessTheStoredOneAlreadyPassed() {
        val now = 10_000_000_000L
        val future = StoredField((now + 3600000L).toString(), 0.95, 1L)
        assertNull(FieldMerge.merge(K.MATCH_AT, future, Reading((now + 7200000L).toString(), 0.8), now))
        val past = StoredField((now - 3600000L).toString(), 0.95, 1L)
        assertNotNull(FieldMerge.merge(K.MATCH_AT, past, Reading((now + 7200000L).toString(), 0.8), now))
        assertNotNull(FieldMerge.merge(K.MATCH_AT, future, Reading((now + 7000000L).toString(), 0.95), now))
    }

    @Test fun nextGameCardKeepsItsClockTime() {
        val o = Fx.ocr(
            Fx.line("Jornada 24", 0.25f, 0.30f, 0.12f), Fx.line("Jornada 25", 0.5f, 0.30f, 0.12f),
            Fx.line("21-10-26", 0.25f, 0.38f, 0.1f), Fx.line("Meias finais", 0.25f, 0.58f, 0.15f),
            Fx.line("22:18", 0.5f, 0.38f, 0.06f), Fx.line("FC Zhenis Astana", 0.5f, 0.58f, 0.2f)
        )
        val m = Parsers.calendar(o, null).matches.first { it.round == 25 }
        assertEquals("22:18", m.time)
        assertNull(m.date)
        assertEquals("FC Zhenis Astana", m.opponent)
    }
}
