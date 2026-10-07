package com.osmaicoach.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeTest {
    private val old = StoredField("Tobol", 0.9, 1000L)

    @Test fun niNeverOverwritesKnownValue() {
        for (v in listOf("NI", "ni", "", "  ", "-", "--")) {
            assertNull(FieldMerge.merge(K.TEAM, old, Reading(v, 1.0), 2000L))
        }
    }

    @Test fun sameValueSameConfidenceIsIdempotent() {
        assertNull(FieldMerge.merge(K.TEAM, old, Reading("Tobol", 0.9), 2000L))
    }

    @Test fun lowerConfidenceDoesNotReplaceStableField() {
        assertNull(FieldMerge.merge(K.TEAM, old, Reading("Tobel", 0.6), 2000L))
        val up = FieldMerge.merge(K.TEAM, old, Reading("Tobol FC", 0.95), 2000L)
        assertEquals("Tobol FC", up!!.value)
    }

    @Test fun volatileFieldsFollowNewReadings() {
        val s = StoredField("88", 0.85, 1000L)
        assertEquals("90", FieldMerge.merge(K.MY_STRENGTH, s, Reading("90", 0.8), 2000L)!!.value)
        assertNull(FieldMerge.merge(K.MY_STRENGTH, s, Reading("12", 0.4), 2000L))
    }

    @Test fun firstReadingIsStored() {
        assertNotNull(FieldMerge.merge(K.COMPETITION, null, Reading("Liga", 0.5), 5L))
        assertNotNull(FieldMerge.merge(K.COMPETITION, StoredField("NI", 0.1, 1L), Reading("Liga", 0.5), 5L))
    }

    @Test fun completenessCountsOnlyFilledFieldsAndNeverShrinksWithPartialSession() {
        val f = HashMap<String, StoredField>()
        f[K.TEAM] = StoredField("Tobol", 0.9, 1L)
        f[K.ROUND] = StoredField("25", 0.9, 1L)
        f[K.RIVAL_FORMATION] = StoredField("NI", 0.9, 1L)
        val before = Completeness.compute(f, 18, 30, true)
        assertTrue(before.known.contains("Meu time"))
        assertTrue(before.missing.contains("Formação rival"))
        // uma sessão parcial que não leu nada novo não pode reduzir a completude
        val merged = FieldMerge.merge(K.TEAM, f[K.TEAM], Reading("NI", 1.0), 9L)
        assertNull(merged)
        val after = Completeness.compute(f, 18, 30, true)
        assertEquals(before.percent, after.percent)
        assertEquals(0, Completeness.compute(emptyMap(), 0, 0, false).percent)
    }

    @Test fun marketPlannerLimits() {
        assertEquals(3, MarketPlanner.sellSlotsLeft("1/4"))
        assertEquals(0, MarketPlanner.sellSlotsLeft("4/4"))
        assertEquals(4, MarketPlanner.sellSlotsLeft(null))
        val needs = MarketPlanner.needs(mapOf("ATA" to 3, "MEI" to 6, "DEF" to 7, "GOL" to 2))
        assertEquals(1, needs.first { it.cat == "ATA" }.missing)
        assertEquals(1, needs.first { it.cat == "DEF" }.surplus)
    }
}
