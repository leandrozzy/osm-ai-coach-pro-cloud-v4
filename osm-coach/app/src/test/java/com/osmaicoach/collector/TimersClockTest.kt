package com.osmaicoach.collector

import org.junit.Assert.assertEquals
import org.junit.Test

class TimersClockTest {
    @Test fun clockLikeTheGame() {
        assertEquals("3h 09m 14s", Timers.clock((3 * 3600L + 9 * 60 + 14) * 1000, 0L))
        assertEquals("05m 48s", Timers.clock(348_000L, 0L))
        assertEquals("1d 04h 10m", Timers.clock((86400L + 4 * 3600 + 10 * 60) * 1000, 0L))
        assertEquals("00m 00s", Timers.clock(0L, 5L))
    }
}
