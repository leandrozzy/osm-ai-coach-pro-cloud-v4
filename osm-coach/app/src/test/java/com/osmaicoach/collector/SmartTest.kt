package com.osmaicoach.collector

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartTest {
    private val day = 86400000L

    @Test fun eventsFromScreenAndAi() {
        assertTrue(OsmEvents.Kind.TRANSFER in OsmEvents.kindsIn("TRANSFER MADNESS começou!"))
        assertTrue(OsmEvents.Kind.OLDIES in OsmEvents.kindsIn("Evento Golden Oldies"))
        val now = 1_800_000_000_000L
        val ai = JSONObject().put("events", JSONArray().put(JSONObject().put("event", "Super Staff").put("start", "2027-01-15").put("end", "2027-01-17")))
        val ev = OsmEvents.fromAi(ai, now)
        assertEquals(OsmEvents.Kind.STAFF, ev.single().kind)
        assertTrue(OsmEvents.active(ev, ev[0].start!! + 3600000L).contains(OsmEvents.Kind.STAFF))
        assertTrue(OsmEvents.active(ev, ev[0].end!! + 1).isEmpty())
    }

    @Test fun ceilingFromSellScreen() {
        assertEquals(2.5, SellPrices.ceilingFrom("Valor: 10M  Preço máximo: 25M")!!, 0.001)
        assertEquals(2.0, SellPrices.ceilingFrom("valor 4,0M • máx. 8,1M")!!, 0.001)
        assertNull(SellPrices.ceilingFrom("Lista de transferências"))
    }

    @Test fun learnsWhatTheCpuPays() {
        val hist = JSONObject()
        val sells = (1..4).map { i ->
            Growth.record(hist, "p$i", i * day, 70, 4.0, false)
            Ledger.Entry("sell", "p$i", "P$i", i * day + 1000, 4.0 * (1.8 + i * 0.1), false, 70, 25)
        }
        val r = SellPrices.learnedRatio(sells, hist)!!
        assertEquals(4, r.second)
        assertEquals(2.1, r.first, 0.01)
        // estimadas não contam
        assertNull(SellPrices.learnedRatio(sells.map { it.copy(estimated = true) }, hist))
    }

    @Test fun learnsWhoGainsMostFromTraining() {
        val h = JSONObject()
        // jovens: +2 em 1 dia de treino; veteranos: +0
        for (k in listOf("y1", "y2", "y3")) { Growth.record(h, k, 0L, 70, null, true); Growth.record(h, k, day, 72, null, false) }
        for (k in listOf("o1", "o2", "o3")) { Growth.record(h, k, 0L, 80, null, true); Growth.record(h, k, day, 80, 1.0, false) }
        val ages = mapOf("y1" to 20, "y2" to 21, "y3" to 19, "o1" to 32, "o2" to 33, "o3" to 31)
        val rates = TrainLearn.rates(listOf(h), ages)
        assertEquals(2.0, rates["até 21"]!!.first, 0.01)
        val p = { n: String, a: Int -> PlayerEntity(1, "MY", n, n, a, null, "MEI", 75, null, false, false, 0L) }
        val pick = TrainLearn.pick(listOf(p("velho", 32), p("novo", 20)), rates)!!
        assertEquals("novo", pick.first.name)
        assertNotNull(pick.second)
    }

    @Test fun predictsRivalTactic() {
        val e = { f: String, st: String, at: Long -> JSONObject().put("formation", f).put("plan", st).put("at", at) }
        val prof = JSONObject().put("entries", JSONArray().put(e("4-3-3 A", "Contra-ataque", 1)).put(e("4-4-2 A", "Jogo de passe", 2)).put(e("4-3-3 A", "Contra-ataque", 3)))
            .put("switches", 1).put("checked", 2)
        val v = RivalPredict.of(prof)!!
        assertEquals("4-3-3 A", v.guesses.first().formation)
        assertTrue(v.guesses.first().share > 50)
        assertEquals(50, v.switchRate)
    }

    @Test fun nationBonusCount() {
        assertEquals(Pair("Brasil", 5), Nations.best(setOf("a", "b", "c", "d", "e", "f"), mapOf("a" to "Brasil", "b" to "Brasil", "c" to "Brasil", "d" to "Brasil", "e" to "Brasil", "f" to "Portugal")))
    }
}
