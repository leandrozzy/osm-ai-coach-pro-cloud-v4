package com.osmaicoach.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualTest {
    @Test fun numbersAndMoneyAreValidated() {
        assertEquals("91", ManualFields.normalize(K.MY_STRENGTH, " 91 "))
        assertNull(ManualFields.normalize(K.MY_STRENGTH, "9a"))
        assertEquals("19,4M", ManualFields.normalize(K.CASH, "19.4m"))
        assertNull(ManualFields.normalize(K.CASH, "muito"))
    }

    @Test fun optionFieldsAcceptOnlyKnownValues() {
        assertEquals("À zona", ManualFields.normalize(K.RIVAL_MARKING, "a zona"))
        assertNull(ManualFields.normalize(K.RIVAL_MARKING, "xyz"))
        assertEquals("Sim", ManualFields.normalize(K.RIVAL_OFFSIDE, "sim"))
        assertEquals("4-3-3 A", ManualFields.normalize(K.RIVAL_FORMATION, "4-3-3 A"))
    }

    @Test fun matchTimeBecomesEpochMillis() {
        val v = ManualFields.normalize(K.MATCH_AT, "07/10 22:18")
        assertNotNull(v)
        assertTrue(v!!.toLong() > 0L)
        assertNull(ManualFields.normalize(K.MATCH_AT, "amanhã"))
    }

    @Test fun manualValueIsNeverOverwrittenByAutomaticReading() {
        assertNull(FieldMerge.merge(K.RIVAL_STRENGTH, StoredField("61", 2.0, 1L), Reading("70", 0.95), 2L))
        assertNull(FieldMerge.merge(K.RIVAL_PLAN, StoredField("Jogo de passe", 2.0, 1L), Reading("Jogar pelas alas", 0.95), 2L))
    }

    @Test fun missingFieldsComeWithKeysSoTheUserCanFillThem() {
        val r = Completeness.compute(emptyMap(), 0, 0, false)
        assertTrue(r.missingItems.size >= 20)
        assertTrue(r.missingItems.any { it.key == K.RIVAL_PLAN })
        assertTrue(r.missingItems.any { it.key == K.MY_STADIUM })
    }

    @Test fun everyMissingFieldHasAManualSpec() {
        for (item in Completeness.ITEMS) {
            assertNotNull("sem especificação manual para ${item.key}", ManualFields.SPECS[item.key])
        }
    }
}
