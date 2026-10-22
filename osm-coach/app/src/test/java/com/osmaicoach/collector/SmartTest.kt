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

    private val CAL = "<script>(function(){var D={\"y\":2026,\"m\":9,\"e\":[{\"d\":[10,11],\"n\":\"Transfer Madness\"},{\"d\":[14],\"n\":\"Super Staff\"}," +
        "{\"d\":[21],\"n\":\"Bargain Scout\"},{\"d\":[24,25],\"n\":\"Intense Friendlies\"},{\"d\":[28],\"n\":\"Top Facilities\"}],\"t\":{\"today\":\"Today\"}};var p={};</script>"

    private fun ist(d: Int, h: Int): Long = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Europe/Istanbul")).apply {
        clear(); set(2026, java.util.Calendar.OCTOBER, d, h, 0, 0)
    }.timeInMillis

    @Test fun calendarSaysNothingIsActiveOn22nd() {
        // o caso do print: Olheiro barato, Loucura de transferências e Super equipe NÃO estavam ativos em 22/10
        val now = ist(22, 12)
        val ev = OsmEvents.fromCalendarHtml(CAL, now)!!
        assertTrue(OsmEvents.active(ev, now).isEmpty())
        assertEquals("Intense Friendlies", OsmEvents.upcoming(ev, now, 3).first().name)
    }

    @Test fun calendarActiveOnlyInsideTheDays() {
        val ev = OsmEvents.fromCalendarHtml(CAL, ist(10, 12))!!
        assertEquals(listOf("Transfer Madness"), OsmEvents.active(ev, ist(10, 12)).map { it.name })
        assertEquals(listOf("Transfer Madness"), OsmEvents.active(ev, ist(11, 23)).map { it.name })
        assertTrue(OsmEvents.active(ev, ist(12, 1)).isEmpty())
        assertEquals(OsmEvents.Kind.TRANSFER, OsmEvents.active(ev, ist(11, 5)).single().kind)
    }

    @Test fun calendarOfAnotherMonthOrBrokenPageIsNotTrusted() {
        assertNull(OsmEvents.fromCalendarHtml(CAL, ist(22, 12) + 12 * 86400000L)) // novembro
        assertNull(OsmEvents.fromCalendarHtml("<html>sem calendário</html>", ist(22, 12)))
    }

    @Test fun eventsNotFromTheCalendarAreIgnored() {
        val now = ist(22, 12)
        val bad = listOf(OsmEvents.Ev(OsmEvents.Kind.TRANSFER, now - 1000, now + 86400000L, "tela"), OsmEvents.Ev(OsmEvents.Kind.SCOUT, now - 1000, now + 1000, "internet"))
        assertTrue(OsmEvents.active(bad, now).isEmpty())
    }

    @Test fun sponsorsFromTheScreenOfTheUser() {
        val o = Fx.ocr(
            Fx.line("Acordo de patrocínio", 0.50f, 0.12f, 0.14f), Fx.line("falta 1 jogo em casa", 0.50f, 0.15f, 0.14f), Fx.line("+ 92K por cada jogo em casa", 0.50f, 0.33f, 0.26f),
            Fx.line("Acordo de patrocínio", 0.08f, 0.43f, 0.14f), Fx.line("falta 1 jogo em casa", 0.08f, 0.46f, 0.14f), Fx.line("+ 49K por cada jogo em casa", 0.08f, 0.64f, 0.26f),
            Fx.line("Acordo de patrocínio", 0.92f, 0.43f, 0.14f), Fx.line("Faltam 3 jogos em casa", 0.92f, 0.46f, 0.14f), Fx.line("+ 69K por cada jogo em casa", 0.92f, 0.64f, 0.26f),
            Fx.line("Acordo de patrocínio", 0.50f, 0.73f, 0.14f), Fx.line("Faltam 3 jogos em casa", 0.50f, 0.76f, 0.14f), Fx.line("+ 76K por cada jogo em casa", 0.50f, 0.94f, 0.26f)
        )
        val d = Sponsors.parse(o)
        assertEquals(4, d.size)
        assertEquals(listOf(1, 1, 3, 3), d.map { it.games }.sorted().let { listOf(it[0], it[1], it[2], it[3]) })
        assertEquals(0.286, d.sumOf { it.perGameM }, 0.0005)
    }

    @Test fun ceilingFromThePriceScreenOfTheUser() {
        val o = Fx.ocr(
            Fx.line("Definir um preço para B. Abdikholikov", 0.66f, 0.50f, 0.30f), Fx.line("11,0M", 0.85f, 0.56f, 0.06f),
            Fx.line("Ganhar imediatamente 4,4M", 0.47f, 0.78f, 0.24f), Fx.line("7,4M", 0.18f, 0.04f, 0.05f)
        )
        assertEquals(2.5, SellPrices.fromScreen(o)!!, 0.001)
        // controle no meio do caminho: razão que não é um teto do OSM → ignora
        val mid = Fx.ocr(Fx.line("Definir um preço para X", 0.66f, 0.50f, 0.30f), Fx.line("7,0M", 0.85f, 0.56f, 0.06f), Fx.line("Ganhar imediatamente 4,4M", 0.47f, 0.78f, 0.24f))
        assertNull(SellPrices.fromScreen(mid))
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
