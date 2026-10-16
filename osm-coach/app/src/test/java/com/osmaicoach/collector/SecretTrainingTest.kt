package com.osmaicoach.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Treino secreto do rival: cadeado no círculo dele no pré-jogo. */
class SecretTrainingTest {
    /** Recorte real do pré-jogo (cadeado à esquerda, minha força 96 à direita), no formato dos testes do árbitro. */
    private fun shot(): PixelProbe.Img {
        val inp = java.io.DataInputStream(javaClass.getResourceAsStream("/referee/locked.rgb")!!.buffered())
        val w = inp.readInt()
        val h = inp.readInt()
        val x0 = inp.readInt()
        val y0 = inp.readInt()
        val cw = inp.readInt()
        val ch = inp.readInt()
        val px = IntArray(w * h) { 0xFF000000.toInt() }
        for (y in 0 until ch) for (x in 0 until cw) {
            val r = inp.readUnsignedByte()
            val g = inp.readUnsignedByte()
            val b = inp.readUnsignedByte()
            px[(y0 + y) * w + x0 + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return PixelProbe.Img(w, h, px)
    }

    @Test fun lockIsSeenOnlyOnTheRivalCircle() {
        val img = shot()
        assertTrue(PixelProbe.lockedCircle(img, left = true))
        assertFalse(PixelProbe.lockedCircle(img, left = false))
    }

    @Test fun plainNavyCircleIsNotALock() {
        val img = PixelProbe.Img(400, 180, IntArray(400 * 180) { (0xFF shl 24) or (8 shl 16) or (26 shl 8) or 62 })
        assertFalse(PixelProbe.lockedCircle(img, left = true))
    }

    private fun sf(v: String) = StoredField(v, 0.9, 0L)

    private val base = mapOf(
        K.MATCH_AT to sf("1"), K.HOME to sf("Fora"), K.RIVAL_TEAM to sf("Ludogorets"), K.RIVAL_HUMAN to sf("Sim"),
        K.RIVAL_NICK to sf("amar111_13"), K.MY_STRENGTH to sf("96"), K.RIVAL_FORMATION to sf("4-3-3 A"), K.REFEREE to sf("Normal"),
        K.MY_GOL to sf("90"), K.MY_DEF to sf("95"), K.MY_MID to sf("96"), K.MY_ATK to sf("98"),
        K.RIVAL_GOL to sf("88"), K.RIVAL_DEF to sf("90"), K.RIVAL_MID to sf("92"), K.RIVAL_ATK to sf("94")
    )

    @Test fun analystFieldsDoNotCountAsMissingWithSecretTraining() {
        val without = Completeness.gameMissing(base).map { it.key }
        assertTrue(K.RIVAL_STRENGTH in without && K.RIVAL_PLAN in without)
        val secret = Completeness.gameMissing(base + (K.RIVAL_SECRET to sf("Sim"))).map { it.key }
        for (k in SecretTraining.HIDDEN) assertFalse(k, k in secret)
        val all = Completeness.compute(base + (K.RIVAL_SECRET to sf("Sim")), 20, 30, true, 30)
        assertFalse(all.missing.any { it.contains("Força do rival") || it.contains("Plano de jogo") || it.contains("Bônus do rival") })
    }

    @Test fun strengthIsEstimatedFromTheRivalSquadWithMargin() {
        assertNull(SecretTraining.rivalStrength(base))
        val f = base + (K.RIVAL_SECRET to sf("Sim"))
        assertEquals(91 + SecretTraining.MARGIN, SecretTraining.rivalStrength(f))
        // força lida vale mais que a estimativa
        assertEquals(80, SecretTraining.rivalStrength(f + (K.RIVAL_STRENGTH to sf("80"))))
    }

    @Test fun secretTrainingGivesASafeTactic() {
        val squad = EngineTest().squad()
        val inp = TacticEngine.Input(squad, 96, 94, true, "4-3-3 A", 95, 80, "Normal", false, emptyList(), rivalSecret = true)
        val r = TacticEngine.recommend(inp)!!
        assertEquals("Não", r.tactic.offside)
        assertTrue(r.tactic.advMid != "Pressionar na frente")
        assertTrue(r.tactic.notes.first().contains("TREINO SECRETO"))
        assertFalse(r.tactic.notes.any { it.contains("confira o relatório") })
    }

    @Test fun battleAgainstEqualRivalPlaysToWin() {
        val squad = EngineTest().squad()
        val inp = TacticEngine.Input(squad, 73, 75, true, "4-3-3 A", 70, 76, "Muito brando", false, emptyList(),
            rivalStyle = "Contra-ataque", mustWin = true)
        val r = TacticEngine.recommend(inp)!!
        assertEquals("Atacar apenas", r.tactic.advAttack)
        assertTrue(r.tactic.mentality >= 60)
        assertTrue(r.tactic.playStyle != "Contra-ataque")
        assertEquals("Extremo", r.tactic.tackle)
        assertTrue(r.tactic.notes.first().startsWith("⚔ Batalha"))
    }

    @Test fun drawIsWorthLessInBattle() {
        val i = WinModel.Input(73, 75)
        val league = WinModel.expectedPoints(i)!!
        val battle = WinModel.expectedPoints(i.copy(drawValue = Director.BATTLE_DRAW))!!
        assertTrue(battle < league)
    }
}
