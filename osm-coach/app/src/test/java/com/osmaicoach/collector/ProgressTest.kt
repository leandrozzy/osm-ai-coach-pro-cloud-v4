package com.osmaicoach.collector

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressTest {
    private fun log(f: String, st: String, res: String?, w: Int, e: Int, d: Int, at: Long) = JSONObject()
        .put("formation", f).put("playStyle", st).put("result", res ?: JSONObject.NULL)
        .put("predW", w).put("predE", e).put("predD", d).put("at", at)

    @Test fun scoreboardCountsHitsAndBestTactic() {
        val logs = listOf(
            log("4-3-3 A", "Jogar pelas alas", "V", 60, 25, 15, 1),
            log("4-3-3 A", "Jogar pelas alas", "V", 55, 25, 20, 2),
            log("4-4-2 A", "Jogo de passe", "D", 50, 30, 20, 3),
            log("4-4-2 A", "Jogo de passe", "E", 30, 25, 45, 4),
            log("4-3-3 A", "Jogar pelas alas", null, 60, 25, 15, 5)
        )
        val s = Scoreboard.of(logs)
        assertEquals(4, s.games)
        assertEquals(2, s.v)
        assertEquals(2, s.hits) // as duas vitórias previstas
        assertEquals(4, s.predicted)
        assertEquals("4-3-3 • Jogar pelas alas", s.best?.label)
        assertEquals("4-4-2 • Jogo de passe", s.worst?.label)
        assertEquals(listOf("V", "V", "D", "E"), s.lastFive)
    }

    @Test fun ledgerPairsBuyAndSellIntoProfit() {
        val l = Ledger.parse(null)
        assertTrue(Ledger.add(l, Ledger.Entry("buy", "silva", "Silva", 1000L, 4.2, false, 70, 21)))
        // a mesma compra lida de novo não duplica
        assertFalse(Ledger.add(l, Ledger.Entry("buy", "silva", "Silva", 2000L, 4.2, false, 70, 21)))
        Ledger.add(l, Ledger.Entry("sell", "silva", "Silva", 10L * 86400000L, 6.0, true, 74, 22))
        Ledger.add(l, Ledger.Entry("buy", "kane", "Kane", 5000L, 9.0, false, 88, 30))
        val back = Ledger.parse(Ledger.toJson(l))
        val s = Ledger.summary(back)
        assertEquals(2, s.buys)
        assertEquals(1, s.sells)
        val silva = s.deals.first { it.name == "Silva" }
        assertEquals(1.8, silva.profit!!, 0.001)
        assertEquals(4, silva.gain)
        assertNull(s.deals.first { it.name == "Kane" }.sold)
        assertEquals(6.0 - 13.2, s.balance, 0.001)
    }

    @Test fun trainingReportComparesTrainedWithOthers() {
        val h = JSONObject()
        val day = 86400000L
        val now = 30 * day
        // treinou: 80 -> 84
        Growth.record(h, "a", now - 20 * day, 80, 5.0, false)
        Growth.record(h, "a", now - 10 * day, 81, 5.2, true)
        Growth.record(h, "a", now - 1 * day, 84, 6.0, true)
        // não treinou: 80 -> 80 (só o valor mudou)
        Growth.record(h, "b", now - 20 * day, 80, 5.0, false)
        Growth.record(h, "b", now - 2 * day, 80, 4.8, false)
        // leitura repetida igual não grava
        assertFalse(Growth.record(h, "b", now - day, 80, 4.8, false))
        val r = Growth.report(h, mapOf("a" to "Ana", "b" to "Bia"), now)
        assertNotNull(r)
        assertEquals(1, r!!.trained)
        assertEquals(4.0, r.trainedGain, 0.001)
        assertEquals(0.0, r.othersGain, 0.001)
        assertEquals(4.0, r.edge, 0.001)
        assertEquals(listOf(Pair("Ana", 4)), r.top)
    }

    @Test fun weeklyDeltaComparesWithLastWeek() {
        val l = Weekly.parse(null)
        Weekly.upsert(l, Weekly.Week(202641, 1L, 50.0, 3.0, 80.0, 3, 1, 1))
        Weekly.upsert(l, Weekly.Week(202642, 2L, 55.5, 2.0, null, 6, 1, 2))
        // a mesma semana relida mantém o que a leitura nova não trouxe
        Weekly.upsert(l, Weekly.Week(202642, 3L, null, 2.5, 81.0, 6, 1, 2))
        val d = Weekly.delta(Weekly.parse(Weekly.toJson(l)))!!
        assertEquals(5.5, d.value!!, 0.001)
        assertEquals(-0.5, d.cash!!, 0.001)
        assertEquals(1.0, d.xi!!, 0.001)
        assertEquals(3, d.wins)
        assertEquals(4, d.games)
        assertEquals(84.0, Weekly.xi(List(11) { 84 } + listOf(60, 50))!!, 0.001)
        assertNull(Weekly.xi(listOf(90, 80)))
    }

    @Test fun moneyIsShortAndSigned() {
        assertEquals("+1,8M", moneyText(1.8, true))
        assertEquals("-350K", moneyText(-0.35, true))
    }
}
