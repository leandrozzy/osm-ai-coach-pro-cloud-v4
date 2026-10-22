package com.osmaicoach.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushTest {
    private val H = 3600_000L
    private fun item(seq: String, at: Long) = Push.Item(seq, at, "t", "x")

    @Test fun sendsNewAndSkipsWhatTheServerAlreadyHas() {
        val now = 1_000_000L
        val a = item("t1_train_ATA", now + 2 * H)
        val p1 = Push.plan(listOf(a), emptyMap(), now)
        assertEquals(listOf(a), p1.publish)
        val p2 = Push.plan(listOf(a), mapOf(a.seq to a.at + Push.LATE_MS), now)
        assertTrue(p2.publish.isEmpty() && p2.delete.isEmpty())
    }

    @Test fun changedTimeIsSentAgainAndStaleOnesAreDeleted() {
        val now = 1_000_000L
        val moved = item("t1_stad_Relvado", now + 5 * H)
        val p = Push.plan(
            listOf(moved), mapOf("t1_stad_Relvado" to now + 3 * H + Push.LATE_MS, "g2_pre" to now + H), now
        )
        assertEquals(listOf(moved), p.publish)
        assertEquals(listOf("g2_pre"), p.delete)
    }

    @Test fun deliveredPastAndTooFarAheadAreIgnored() {
        val now = 10 * H
        val p = Push.plan(
            listOf(item("past", now - H), item("far", now + 4 * 24 * H)), mapOf("old" to now - 5 * H), now
        )
        assertTrue(p.publish.isEmpty() && p.delete.isEmpty() && p.keep.isEmpty())
    }
}
