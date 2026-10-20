package com.osmaicoach.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RivalWatchTest {
    private fun sf(v: String) = StoredField(v, 0.9, 0L)

    @Test fun reportsWhatChangedAfterTheTacticAndWhy() {
        val before = mapOf(
            K.RIVAL_FORMATION to sf("4-4-2 A"), K.RIVAL_PLAN to sf("Jogo de passe"), K.RIVAL_OFFSIDE to sf("Não"),
            K.RIVAL_STRENGTH to sf("80"), K.REFEREE to sf("Normal")
        )
        val basis = RivalWatch.basis(before)
        // nada mudou (força variando 1 ponto não conta)
        assertTrue(RivalWatch.diff(basis, before + (K.RIVAL_STRENGTH to sf("81"))).isEmpty())
        val now = before + mapOf(
            K.RIVAL_FORMATION to sf("4-3-3 A"), K.RIVAL_PLAN to sf("Contra-ataque"), K.RIVAL_OFFSIDE to sf("Sim"), K.RIVAL_STRENGTH to sf("84")
        )
        val ch = RivalWatch.diff(basis, now)
        assertEquals(listOf(K.RIVAL_FORMATION, K.RIVAL_PLAN, K.RIVAL_OFFSIDE, K.RIVAL_STRENGTH), ch.map { it.key })
        assertTrue(ch[0].why.contains("3 atacante"))
        assertTrue(ch[2].why.contains("impedimento"))
        assertTrue(ch[3].why.contains("4 ponto"))
        assertEquals(ch, RivalWatch.fromJson(RivalWatch.toJson(ch, 1L)))
        assertTrue(RivalWatch.headline(ch).startsWith("Formação: 4-4-2 A → 4-3-3 A"))
    }

    @Test fun dataReadOnlyAfterTheTacticIsNotAChange() {
        val basis = RivalWatch.basis(mapOf(K.RIVAL_STRENGTH to sf("80")))
        assertTrue(RivalWatch.diff(basis, mapOf(K.RIVAL_STRENGTH to sf("80"), K.RIVAL_FORMATION to sf("5-3-2"))).isEmpty())
    }
}
