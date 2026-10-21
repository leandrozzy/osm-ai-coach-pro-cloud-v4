package com.osmaicoach.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloorsTest {
    private fun inp(mustWin: Boolean, atkNeed: Int) = TacticEngine.Input(
        emptyList(), 77, 83, true, null, null, null, null, null, emptyList(),
        lessons = Coach.Lessons(atkNeed = atkNeed), mustWin = mustWin
    )

    @Test fun battleUnderdogInDroughtKeepsTwoStrikersAndStyle60() {
        // o caso do S4: batalha, -6 e sem gols — a simulação não pode voltar ao 5-4-1 com estilo 48
        val f = TacticEngine.floors(inp(true, 1), -6)
        assertEquals(60, f.mentality)
        assertTrue(f.attackOnly)
        assertEquals(2, f.minStrikers)
        assertEquals(66, f.minTempo)
        assertEquals(1, TacticEngine.strikers("5-4-1 A"))
        assertEquals(1, TacticEngine.strikers("4-2-3-1"))
        assertEquals(2, TacticEngine.strikers("5-3-2"))
    }

    @Test fun evenLeagueGameHasNoFloors() {
        val f = TacticEngine.floors(inp(false, 0), -3)
        assertEquals(0, f.mentality)
        assertEquals(0, f.minStrikers)
    }

    private fun t(f: String, st: String) = Tactic(f, st, 50, 50, 50, "À zona", "Não", "Normal", "Atacar apenas", "Manter posições", "Defender atrás", emptyList())

    @Test fun provenTacticIsRecognized() {
        // o caso do S2: 4-3-3 + Jogar pelas alas vinha ganhando e foi trocado por 4-2-3-1 contra-ataque
        val hist = listOf(
            HistRow("4-3-3 A", "Jogar pelas alas", "V"), HistRow("4-3-3 A", "Jogar pelas alas", "V"),
            HistRow("4-4-2 A", "Jogar pelas alas", "E"), HistRow("4-3-3 B", "Jogar pelas alas", "V"),
            HistRow("4-2-3-1", "Contra-ataque", "D")
        )
        assertNotNull(Director.proven(hist, t("4-3-3 A", "Jogar pelas alas")))
        assertNull(Director.proven(hist, t("4-2-3-1", "Contra-ataque")))
        assertNull(Director.proven(listOf(HistRow("4-3-3 A", "Jogar pelas alas", "V")), t("4-3-3 A", "Jogar pelas alas")))
    }

    @Test fun refereeMisreadIsNotANick() {
        assertTrue(!Evidence.plausibleNick("Arbifro"))
        assertTrue(!Evidence.plausibleNick("Árbitro"))
        assertTrue(Evidence.plausibleNick("Ctolga_1905_316"))
    }
}
