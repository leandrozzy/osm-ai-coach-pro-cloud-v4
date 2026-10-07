package com.osmaicoach.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {
    @Test fun hubReadsFourCardsWithPositionRoundAndNames() {
        val cards = Fx.hubCards()
        assertEquals(4, cards.size)
        assertEquals("TOBOL", cards[0].team)
        assertEquals(24, cards[0].roundDone)
        assertEquals(34, cards[0].roundTotal)
        assertEquals("LEVSKI SOFIA", cards[1].team)
        assertEquals("NASAF", cards[2].team)
        assertEquals(9, cards[2].roundDone)
        assertEquals("UZM VS BAY", cards[3].team)
        assertEquals("BATALHA DE GRUPOS", cards[3].subtitle)
        assertEquals(listOf(1, 2, 3, 4), cards.map { it.slot })
    }

    @Test fun pregameWithMeOnTheRightMeansAwayAndHumanRival() {
        val ex = Parsers.pregame(Fx.ocr(*Fx.pregameLines().toTypedArray()), null, 0L)
        assertEquals("Universidad de Chile", ex.fields[K.TEAM]?.value)
        assertEquals("Deportes La Serena", ex.fields[K.RIVAL_TEAM]?.value)
        assertEquals("Fora", ex.fields[K.HOME]?.value)
        assertEquals("73", ex.fields[K.MY_STRENGTH]?.value)
        assertNull(ex.fields[K.RIVAL_STRENGTH])
        assertEquals("Sim", ex.fields[K.RIVAL_HUMAN]?.value)
        assertEquals("ChicoR78", ex.fields[K.RIVAL_NICK]?.value)
        assertEquals("4", ex.fields[K.ROUND]?.value)
        assertEquals("75900000", ex.fields[K.MATCH_AT]?.value)
        assertEquals("4,6M", ex.fields[K.CASH]?.value)
        assertEquals(4, ex.roundRead)
    }

    @Test fun pregameWithoutMyNickDoesNotGuessSides() {
        val lines = Fx.pregameLines().filter { it.text != "leandrozzy" }
        val ex = Parsers.pregame(Fx.ocr(*lines.toTypedArray()), null, 0L)
        assertNull(ex.fields[K.TEAM])
        assertNull(ex.fields[K.RIVAL_HUMAN])
        assertEquals(2, ex.teamCandidates.size)
    }

    @Test fun squadUsesColumnByCategoryAndGoalkeeperUsesDef() {
        val rows = ArrayList<OcrLine>()
        rows += Fx.line("Tobol", 0.05f, 0.228f, 0.05f)
        rows += Fx.line("leandrozzy", 0.06f, 0.263f, 0.08f)
        rows += Fx.line("Posição: 4", 0.08f, 0.149f, 0.1f)
        rows += Fx.line("4-3-3 B", 0.71f, 0.149f, 0.08f)
        rows += Fx.line("Avançados", 0.5f, 0.60f, 0.08f)
        fun row(y: Float, name: String, age: String, pos: String, a: String, d: String, m: String, v: String) {
            rows += Fx.line(name, 0.07f, y); rows += Fx.line(age, 0.555f, y, 0.03f); rows += Fx.line(pos, 0.62f, y, 0.03f)
            rows += Fx.line(a, 0.663f, y, 0.025f); rows += Fx.line(d, 0.70f, y, 0.025f); rows += Fx.line(m, 0.737f, y, 0.025f)
            rows += Fx.line(v, 0.95f, y, 0.04f)
        }
        row(0.67f, "Mbeumo", "27", "ED", "95", "28", "61", "25,5M")
        row(0.77f, "Foden", "26", "MC", "40", "30", "90", "18,2M")
        row(0.87f, "Quansah", "23", "DC", "20", "84", "30", "8,5M")
        row(0.97f, "Perri", "29", "GR", "10", "82", "20", "5,1M")
        val ex = Parsers.squad(Fx.ocr(*rows.toTypedArray()), null)
        assertEquals("Tobol", ex.ownerTeam)
        assertEquals("leandrozzy", ex.ownerNick)
        assertEquals("4-3-3 B", ex.fields["x.formation"]?.value)
        assertEquals(4, ex.players.size)
        assertEquals(listOf(95, 90, 84, 82), ex.players.map { it.strength })
        assertEquals(listOf("ATA", "MEI", "DEF", "GOL"), ex.players.map { it.cat })
        assertNull(ex.players[0].training)
        assertFalse(ex.needsAi)
    }

    @Test fun calendarSkipsEmptyCardsAndNeverInventsAResult() {
        val o = Fx.ocr(
            Fx.line("Altay Oskemen", 0.1f, 0.302f, 0.1f), Fx.line("Astana", 0.22f, 0.302f, 0.07f),
            Fx.line("FK Aktobe", 0.33f, 0.302f, 0.08f), Fx.line("el chiri_2", 0.33f, 0.34f, 0.08f),
            Fx.line("Tobol", 0.5f, 0.503f, 0.06f, 0.06f),
            Fx.line("Jornada 1", 0.087f, 0.604f, 0.08f), Fx.line("13-09-26", 0.087f, 0.69f, 0.08f),
            Fx.line("1-0", 0.087f, 0.78f, 0.05f, 0.08f), Fx.line("Kaysar Kyzylorda", 0.087f, 0.89f, 0.14f),
            Fx.line("Jornada 2", 0.25f, 0.604f, 0.08f), Fx.line("14-09-26", 0.25f, 0.69f, 0.08f),
            Fx.line("2-2", 0.25f, 0.78f, 0.05f, 0.08f), Fx.line("Kaspiy Aktau", 0.25f, 0.89f, 0.12f),
            Fx.line("Joel II Gonzalez", 0.25f, 0.935f, 0.14f),
            Fx.line("Jornada 8", 0.25f, 0.97f, 0.08f)
        )
        val ex = Parsers.calendar(o, null)
        assertEquals("Tobol", ex.ownerTeam)
        assertEquals(2, ex.matches.size)
        val m1 = ex.matches[0]
        assertEquals("L1", m1.key)
        assertEquals("13/09/26", m1.date)
        assertEquals("Kaysar Kyzylorda", m1.opponent)
        assertEquals("Joel II Gonzalez", ex.matches[1].opponentNick)
        // sem selo/ícone de casa não dá para orientar o placar: não inventa resultado
        assertNull(m1.result)
        assertNull(m1.scoreMine)
    }

    @Test fun scoreOrientationUsesBadgeAndHomeIcon() {
        val a = Parsers.orient(0, 1, 'D', true)!!
        assertEquals(0, a.mine); assertEquals(1, a.opp)
        val b = Parsers.orient(1, 0, 'D', false)!!
        assertEquals(0, b.mine); assertEquals(1, b.opp)
        val c = Parsers.orient(3, 0, 'V', false)!!
        assertEquals(3, c.mine)
        assertTrue(c.conf < 0.7)
        val d = Parsers.orient(2, 2, 'E', null)!!
        assertEquals(2, d.mine)
        assertNull(Parsers.orient(1, 0, null, null))
    }

    @Test fun marketReadsColumnsByCategoryAndSellingCount() {
        fun row(y: Float, name: String, age: String, pos: String, club: String, nick: String?, a: String, d: String, m: String, price: String): List<OcrLine> {
            val l = ArrayList<OcrLine>()
            l += Fx.line(name, 0.05f, y, 0.09f); l += Fx.line(age, 0.525f, y, 0.03f); l += Fx.line(pos, 0.60f, y, 0.04f)
            l += Fx.line(club, 0.69f, y - 0.015f, 0.12f)
            if (nick != null) l += Fx.line(nick, 0.69f, y + 0.025f, 0.12f)
            l += Fx.line(a, 0.816f, y, 0.025f); l += Fx.line(d, 0.853f, y, 0.025f); l += Fx.line(m, 0.888f, y, 0.025f)
            l += Fx.line(price, 0.945f, y, 0.05f)
            return l
        }
        val lines = ArrayList<OcrLine>()
        lines += Fx.line("Vender jogadores 1 / 4", 0.3f, 0.13f, 0.2f)
        lines += row(0.35f, "Diaby-Fadiga", "25", "MCO", "Audax Italiano", "Samuel Aires_1", "75", "72", "73", "10,3M")
        lines += row(0.45f, "Guedes", "30", "ED", "Al-Rayan", null, "80", "70", "60", "8,9M")
        val ex = Parsers.market(Fx.ocr(*lines.toTypedArray()))
        assertEquals("1/4", ex.fields[K.SELLING]?.value)
        assertEquals(2, ex.listings.size)
        assertEquals("MEI", ex.listings[0].cat)
        assertEquals(73, ex.listings[0].strength)
        assertEquals("Samuel Aires_1", ex.listings[0].sellerNick)
        assertEquals("ATA", ex.listings[1].cat)
        assertEquals(80, ex.listings[1].strength)
        assertNotNull(ex.listings[1].priceText)
    }

    @Test fun scrolledSquadHasNoOwnerSoPlayerNamesAreNeverTakenAsTeam() {
        val rows = ArrayList<OcrLine>()
        fun row(y: Float, name: String, age: String, pos: String, a: String, d: String, m: String, v: String) {
            rows += Fx.line(name, 0.07f, y); rows += Fx.line(age, 0.555f, y, 0.03f); rows += Fx.line(pos, 0.62f, y, 0.03f)
            rows += Fx.line(a, 0.663f, y, 0.025f); rows += Fx.line(d, 0.70f, y, 0.025f); rows += Fx.line(m, 0.737f, y, 0.025f)
            rows += Fx.line(v, 0.95f, y, 0.04f)
        }
        row(0.20f, "Foden", "26", "MC", "40", "30", "90", "18,2M")
        row(0.30f, "Gibbs-White", "26", "MC", "41", "31", "90", "18,0M")
        row(0.40f, "Lopez", "23", "MC", "40", "30", "88", "16,5M")
        val ex = Parsers.squad(Fx.ocr(*rows.toTypedArray()), null)
        assertNull(ex.ownerTeam)
        assertNull(ex.ownerNick)
        assertTrue(ex.fields.isEmpty())
        assertEquals(3, ex.players.size)
    }

    @Test fun scrolledCalendarHasNoOwnerAndNoHumans() {
        val o = Fx.ocr(
            Fx.line("Jornada 9", 0.5f, 0.503f, 0.08f, 0.04f),
            Fx.line("Irtysh Pavlodar", 0.25f, 0.30f, 0.12f), Fx.line("Mr_Java", 0.25f, 0.34f, 0.07f),
            Fx.line("Jornada 1", 0.087f, 0.604f, 0.08f), Fx.line("1-0", 0.087f, 0.78f, 0.05f, 0.08f),
            Fx.line("Kaysar Kyzylorda", 0.087f, 0.89f, 0.14f)
        )
        val ex = Parsers.calendar(o, null)
        assertNull(ex.ownerTeam)
        assertTrue(ex.humans.isEmpty())
    }

    @Test fun reportHintNeedsTwoKeywords() {
        assertTrue(Parsers.hasReportHint(Fx.ocr(Fx.line("Formação 4-4-2 B", 0.5f, 0.2f, 0.2f), Fx.line("Desarme Agressivo", 0.5f, 0.3f, 0.2f))))
        assertFalse(Parsers.hasReportHint(Fx.ocr(Fx.line("Mercado de transferências", 0.5f, 0.2f, 0.3f))))
    }

    @Test fun lostCommaInTeamValueIsRestoredOnlyForWeakTeams() {
        assertEquals("27,5M", Money.fixLostComma("275M", 61))
        assertNull(Money.fixLostComma("263M", 91))
        assertNull(Money.fixLostComma("27,5M", 61))
        assertNull(Money.fixLostComma("75M", 61))
    }

    @Test fun moneyIsFoundInsideNoisyTokens() {
        assertEquals("7,6M", Money.extract("©7,6M"))
        assertEquals("1,7M", Money.extract("S 1,7M"))
        assertEquals("942K", Money.extract("942K"))
        assertNull(Money.extract("Jogador"))
        assertNull(Money.extract("73"))
    }

    @Test fun moneyRequiresSuffix() {
        assertEquals(7.3, Money.parse("7,3M")!!, 0.001)
        assertEquals(0.942, Money.parse("942K")!!, 0.001)
        assertNull(Money.parse("73"))
    }
}
