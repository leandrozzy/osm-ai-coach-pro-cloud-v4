package com.osmaicoach.collector

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TacticValidTest {
    private val now = 1_800_000_000_000L
    private val hour = 3600000L

    private fun plan(round: Int, matchAt: Long?) = JSONObject().put("forRound", round).put("matchAt", matchAt ?: JSONObject.NULL)
    private fun fields(round: Int?, matchAt: Long?): Map<String, StoredField> {
        val m = HashMap<String, StoredField>()
        round?.let { m[K.ROUND] = StoredField(it.toString(), 0.9, now) }
        matchAt?.let { m[K.MATCH_AT] = StoredField(it.toString(), 0.9, now) }
        return m
    }

    @Test fun sameMatchTimeKeepsTheTacticEvenIfTheRoundWasReReadOrLost() {
        val at = now + hour
        assertTrue(Director.tacticValid(plan(31, at), now - hour, fields(31, at), now))
        assertTrue(Director.tacticValid(plan(31, at), now - hour, fields(32, at), now))
        assertTrue(Director.tacticValid(plan(31, at), now - hour, fields(null, at), now))
    }

    @Test fun nextMatchOrStartedMatchInvalidatesTheTactic() {
        val at = now + hour
        assertFalse(Director.tacticValid(plan(31, at), now - hour, fields(32, at + 24 * hour), now))
        assertFalse(Director.tacticValid(plan(31, at), now - hour, fields(31, now - 60000L), now))
        assertFalse(Director.tacticValid(plan(30, null), now - hour, fields(31, at), now))
    }
}
