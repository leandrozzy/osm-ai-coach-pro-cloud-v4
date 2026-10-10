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

    @Test fun anniversaryBannerBehindTheRivalIsNotANick() {
        val lines = Fx.pregameLines().map { if (it.text == "ChicoR78") Fx.line("25 Anniversary", 0.23f, 0.41f, 0.12f) else it }
        val ex = Parsers.pregame(Fx.ocr(*lines.toTypedArray()), null, 0L)
        assertNull(ex.fields[K.RIVAL_NICK])
        // sem apelido legível não vira CPU (o OCR pode ter perdido o nome do usuário)
        assertNull(ex.fields[K.RIVAL_HUMAN])
        assertTrue(ex.humans.values.none { it == "25 Anniversary" })
    }

    @Test fun rivalNickIsKeptEvenWhenMyNickIsNotRead() {
        val lines = Fx.pregameLines().filter { it.text != "leandrozzy" }
        val ex = Parsers.pregame(Fx.ocr(*lines.toTypedArray()), null, 0L)
        assertTrue(ex.humans.values.contains("ChicoR78"))
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

    @Test fun midfieldBubbleGluedToItsLabelIsStillRead() {
        val o = Fx.ocr(
            Fx.line("Posição: 4", 0.08f, 0.149f, 0.1f),
            Fx.line("88", 0.631f, 0.275f, 0.03f), Fx.line("90", 0.676f, 0.275f, 0.03f),
            Fx.line("Méd89", 0.724f, 0.27f, 0.04f), Fx.line("91", 0.766f, 0.275f, 0.03f)
        )
        val ex = Parsers.squad(o, null)
        assertEquals("89", ex.fields["x.mid"]?.value)
        assertEquals("91", ex.fields["x.atk"]?.value)
    }

    @Test fun sectorBubblesAreReadByPositionEvenIfLabelsAreWrong() {
        val o = Fx.ocr(
            Fx.line("Posição: 4", 0.08f, 0.149f, 0.1f),
            Fx.line("Gr", 0.631f, 0.23f, 0.02f), Fx.line("88", 0.631f, 0.275f, 0.03f),
            Fx.line("Dei", 0.676f, 0.23f, 0.02f), Fx.line("90", 0.676f, 0.275f, 0.03f),
            Fx.line("Méd", 0.721f, 0.23f, 0.02f), Fx.line("89", 0.721f, 0.275f, 0.03f),
            Fx.line("Ata", 0.766f, 0.23f, 0.02f), Fx.line("91", 0.766f, 0.275f, 0.03f)
        )
        val ex = Parsers.squad(o, null)
        assertEquals("88", ex.fields["x.gol"]?.value)
        assertEquals("90", ex.fields["x.def"]?.value)
        assertEquals("89", ex.fields["x.mid"]?.value)
        assertEquals("91", ex.fields["x.atk"]?.value)
    }

    private fun starImg(levels: Map<Float, Int>): PixelProbe.Img {
        val w = 1000
        val h = 500
        val px = IntArray(w * h) { 0xFF102040.toInt() }
        for ((cx, lvl) in levels) {
            for (i in -1..1) {
                val filled = (i + 2) <= lvl
                val color = if (filled) 0xFFFFC83D.toInt() else 0xFFD0D0D0.toInt()
                val sx = ((cx + i * 0.0286f) * w).toInt()
                val sy = ((0.31f + 0.074f) * h).toInt()
                for (y in sy - 10..sy + 10) for (x in sx - 10..sx + 10) px[y * w + x] = color
            }
        }
        return PixelProbe.Img(w, h, px)
    }

    @Test fun stadiumLevelsAreTheNumberOfGoldStars() {
        val o = Fx.ocr(
            Fx.line("Capacidade", 0.196f, 0.31f, 0.12f), Fx.line("Relvado", 0.5f, 0.31f, 0.1f), Fx.line("Treino", 0.805f, 0.31f, 0.08f),
            Fx.line("Nível 3", 0.5f, 0.62f, 0.08f), Fx.line("Nível 2", 0.805f, 0.62f, 0.08f)
        )
        val img = starImg(mapOf(0.196f to 3, 0.5f to 2, 0.805f to 1))
        val ex = Parsers.stadium(o, img)
        assertEquals("3", ex.fields[K.MY_STAD_CAP]?.value)
        assertEquals("2", ex.fields[K.MY_STAD_PITCH]?.value)
        assertEquals("1", ex.fields[K.MY_STAD_TRAIN]?.value)
        assertEquals("Relvado 2 • Capacidade 3 • Treino 1", ex.fields[K.MY_STADIUM]?.value)
        assertTrue(Parsers.stadium(o, null).fields.isEmpty())
    }

    @Test fun stadiumUpgradeStatusIsReadForEachCard() {
        val o = Fx.ocr(
            Fx.line("Capacidade", 0.196f, 0.31f, 0.12f), Fx.line("Relvado", 0.5f, 0.31f, 0.1f), Fx.line("Treino", 0.805f, 0.31f, 0.08f),
            Fx.line("A capacidade do teu estádio está no máximo!", 0.196f, 0.80f, 0.3f),
            Fx.line("Concluir", 0.5f, 0.82f, 0.1f),
            Fx.line("Começar melhoramento", 0.805f, 0.80f, 0.2f), Fx.line("501K + 18h", 0.805f, 0.84f, 0.1f)
        )
        val ex = Parsers.stadium(o, starImg(mapOf(0.196f to 3, 0.5f to 2, 0.805f to 1)))
        assertEquals("Relvado: concluir melhoria • Capacidade: máximo • Treino: 501K + 18h", ex.fields[K.MY_STAD_STATUS]?.value)
    }

    @Test fun conditionBarFillIsMeasuredByColoredWidth() {
        val w = 1000
        val h = 100
        val px = IntArray(w * h) { 0xFFF5F5F5.toInt() }
        for (x in 757 until 779) px[50 * w + x] = 0xFF2BC24A.toInt()
        val half = PixelProbe.barFill(PixelProbe.Img(w, h, px), 0.757f, 0.800f, 0.5f)
        assertTrue(half in 40..60)
        for (x in 757 until 800) px[50 * w + x] = 0xFF2BC24A.toInt()
        assertTrue(PixelProbe.barFill(PixelProbe.Img(w, h, px), 0.757f, 0.800f, 0.5f) >= 95)
    }

    @Test fun humanRivalShowsBonusInTheCircleAndCpuShowsStrength() {
        val human = Fx.ocr(
            Fx.line("Jornada 4", 0.5f, 0.13f, 0.08f),
            Fx.line("Deportes La Serena", 0.23f, 0.37f, 0.2f, 0.05f), Fx.line("Universidad de Chile", 0.77f, 0.37f, 0.2f, 0.05f),
            Fx.line("ChinoM10", 0.25f, 0.42f, 0.1f), Fx.line("leandrozzy", 0.77f, 0.42f, 0.1f),
            Fx.line("+3%", 0.339f, 0.26f, 0.05f), Fx.line("+3%", 0.66f, 0.26f, 0.05f)
        )
        val h = Parsers.pregame(human, null, 0L)
        assertEquals("Universidad de Chile", h.fields[K.TEAM]?.value)
        assertEquals("Fora", h.fields[K.HOME]?.value)
        assertEquals("+3%", h.fields[K.RIVAL_LOGIN_BONUS]?.value)
        assertEquals("+3%", h.fields[K.MY_BONUS]?.value)
        val cpu = Fx.ocr(
            Fx.line("Jornada 25", 0.5f, 0.13f, 0.08f),
            Fx.line("Tobol", 0.23f, 0.37f, 0.2f, 0.05f), Fx.line("FC Zhenis Astana", 0.77f, 0.37f, 0.2f, 0.05f),
            Fx.line("leandrozzy", 0.23f, 0.42f, 0.1f),
            Fx.line("+3%", 0.339f, 0.26f, 0.05f), Fx.line("61", 0.66f, 0.26f, 0.04f)
        )
        val c = Parsers.pregame(cpu, null, 0L)
        assertEquals("Casa", c.fields[K.HOME]?.value)
        assertEquals("61", c.fields[K.RIVAL_STRENGTH]?.value)
        assertEquals("+3%", c.fields[K.MY_BONUS]?.value)
        assertNull(c.fields[K.RIVAL_LOGIN_BONUS])
    }

    @Test fun matchResultHeaderIsRead() {
        val o = Fx.ocr(
            Fx.line("Casa", 0.02f, 0.125f, 0.03f), Fx.line("Jornada 3", 0.5f, 0.125f, 0.08f), Fx.line("Fora", 0.98f, 0.125f, 0.03f),
            Fx.line("Coquimbo Unido", 0.115f, 0.235f, 0.15f, 0.05f), Fx.line("Universidad de Chile", 0.875f, 0.235f, 0.2f, 0.05f),
            Fx.line("cnco 55", 0.085f, 0.285f, 0.07f), Fx.line("leandrozzy", 0.905f, 0.285f, 0.09f),
            Fx.line("0-1", 0.5f, 0.25f, 0.1f, 0.12f), Fx.line("Cristián Galaz", 0.9f, 0.395f, 0.12f),
            Fx.line("K. Phillips é o jogador certo para marcar os cantos. A assistência para golo é a prova disso mesmo!", 0.35f, 0.53f, 0.4f),
            Fx.line("Adorei a tática. Gostei da vitória. O meu trabalho aqui está terminado.", 0.25f, 0.68f, 0.3f),
            Fx.line("Primeira parte", 0.5f, 0.79f, 0.1f), Fx.line("Segunda parte", 0.5f, 0.95f, 0.1f)
        )
        assertTrue(Parsers.isMatchResult(o))
        val r = Parsers.matchResult(o).matchReport!!
        assertEquals(3, r.round)
        assertEquals("Coquimbo Unido", r.homeTeam)
        assertEquals("Universidad de Chile", r.awayTeam)
        assertEquals("leandrozzy", r.awayNick)
        assertEquals(0, r.scoreHome)
        assertEquals(1, r.scoreAway)
        assertEquals("Cristián Galaz", r.referee)
        assertTrue(r.tip!!.contains("K. Phillips"))
        assertTrue(r.advice!!.contains("Adorei"))
    }

    @Test fun matchResultStatsAndZonesAreRead() {
        fun row(label: String, l: String, rr: String, y: Float) = listOf(
            Fx.line(label, 0.5f, y, 0.1f), Fx.line(l, 0.045f, y, 0.05f), Fx.line(rr, 0.955f, y, 0.05f)
        )
        val lines = ArrayList<OcrLine>()
        lines.add(Fx.line("Estatísticas do jogo", 0.5f, 0.05f, 0.15f))
        lines.addAll(row("Golos", "0", "1", 0.10f))
        lines.addAll(row("Remates", "12", "11", 0.20f))
        lines.addAll(row("Cantos", "8", "5", 0.30f))
        lines.addAll(row("Faltas", "7", "21", 0.40f))
        lines.addAll(row("Formação", "4-3-3 A", "4-3-3 B", 0.50f))
        lines.addAll(row("Posse de bola", "52%", "48%", 0.60f))
        lines.add(Fx.line("Cartões", 0.5f, 0.70f, 0.08f))
        lines.add(Fx.line("0", 0.026f, 0.70f, 0.02f)); lines.add(Fx.line("0", 0.046f, 0.70f, 0.02f))
        lines.add(Fx.line("0", 0.954f, 0.70f, 0.02f)); lines.add(Fx.line("2", 0.973f, 0.70f, 0.02f))
        lines.add(Fx.line("25 %", 0.33f, 0.85f, 0.05f)); lines.add(Fx.line("50 %", 0.5f, 0.85f, 0.05f)); lines.add(Fx.line("25 %", 0.67f, 0.85f, 0.05f))
        val r = Parsers.matchResult(Fx.ocr(*lines.toTypedArray())).matchReport!!
        assertEquals(Pair("0", "1"), r.stats["golos"])
        assertEquals(Pair("12", "11"), r.stats["remates"])
        assertEquals(Pair("7", "21"), r.stats["faltas"])
        assertEquals(Pair("4-3-3 A", "4-3-3 B"), r.stats["formacao"])
        assertEquals(Pair("52%", "48%"), r.stats["posse de bola"])
        assertEquals(Pair("0,0", "2,0"), r.stats["cartoes"])
        assertEquals(listOf(25, 50, 25), r.zones)
    }

    @Test fun matchResultPlayerRatingsAreReadForBothTeams() {
        val lines = ArrayList<OcrLine>()
        val home = listOf("1. L. Popescu", "17. Giannoulis", "18. Otávio", "2. Gazzolo", "Sugawara")
        val away = listOf("1. Trott", "13. O. Rodríguez", "14. Keane", "4. Calero", "17. Hormazábal")
        for (i in 0 until 5) {
            val y = 0.2f + i * 0.1f
            lines.add(Fx.line(home[i], 0.07f, y, 0.1f)); lines.add(Fx.line("6", 0.467f, y, 0.02f))
            lines.add(Fx.line("7", 0.533f, y, 0.02f)); lines.add(Fx.line(away[i], 0.93f, y, 0.1f))
        }
        val o = Fx.ocr(*lines.toTypedArray())
        assertTrue(Parsers.isMatchResult(o))
        val r = Parsers.matchResult(o).matchReport!!
        assertEquals(5, r.ratingsHome.size)
        assertEquals(Pair("L. Popescu", 6), r.ratingsHome.first())
        assertEquals(Pair("O. Rodríguez", 7), r.ratingsAway[1])
    }

    private fun analysisNote() = listOf(
        Fx.line("Mashal Mubarek", 0.20f, 0.27f, 0.2f, 0.04f),
        Fx.line("UzM Raridade [S3]", 0.20f, 0.32f, 0.2f, 0.04f),
        Fx.line("Pelo que pude ver, Mashal Mubarek deu ordens aos jogadores para usarem entradas Normal.", 0.20f, 0.38f, 0.4f),
        Fx.line("Também consegui descobrir a formação. Parece que vão jogar num 4-3-3 A", 0.20f, 0.42f, 0.4f),
        Fx.line("Tenho a certeza que eles não foram em Estágio.", 0.20f, 0.46f, 0.3f),
        Fx.line("Nível do estádio: 1", 0.10f, 0.51f, 0.12f)
    )

    @Test fun analysisFieldsAreReadFromTheFormationPage() {
        val o = Fx.ocr(*(analysisNote() + listOf(
            Fx.line("Formação: 4-3-3 A", 0.70f, 0.03f, 0.2f), Fx.line("Kostoulas", 0.70f, 0.12f), Fx.line("Suplentes", 0.70f, 0.74f)
        )).toTypedArray())
        assertTrue(Parsers.isAnalysis(o))
        val ex = Parsers.report(o)
        assertEquals(ScreenType.REPORT, ex.type)
        assertEquals("Normal", ex.fields[K.RIVAL_TACKLE]?.value)
        assertEquals("4-3-3 A", ex.fields[K.RIVAL_FORMATION]?.value)
        assertEquals("Não", ex.fields[K.RIVAL_CAMP]?.value)
        assertEquals("Nível 1", ex.fields[K.STADIUM]?.value)
        assertEquals("UzM Raridade", ex.fields[K.RIVAL_NICK]?.value)
        assertEquals("Sim", ex.fields[K.RIVAL_HUMAN]?.value)
        assertFalse(ex.needsAi)
    }

    @Test fun analysisFieldsAreReadFromTheTacticPage() {
        val o = Fx.ocr(*(analysisNote() + listOf(
            Fx.line("Jogar pelas alas", 0.70f, 0.03f, 0.15f),
            Fx.line("Marcação", 0.67f, 0.68f, 0.08f), Fx.line("À zona", 0.67f, 0.72f, 0.06f),
            Fx.line("Fazer fora-de-jogo", 0.95f, 0.68f, 0.15f), Fx.line("Não", 0.95f, 0.72f, 0.04f)
        )).toTypedArray())
        val ex = Parsers.report(o)
        assertEquals("Jogar pelas alas", ex.fields[K.RIVAL_PLAN]?.value)
        assertEquals("À zona", ex.fields[K.RIVAL_MARKING]?.value)
        assertEquals("Não", ex.fields[K.RIVAL_OFFSIDE]?.value)
        assertEquals("Normal", ex.fields[K.RIVAL_TACKLE]?.value)
    }

    @Test fun calendarIgnoresStadiumBannerTextGluedToTheDate() {
        val o = Fx.ocr(
            Fx.line("Jornada 14", 0.25f, 0.60f, 0.12f), Fx.line("Jornada 15", 0.5f, 0.60f, 0.12f), Fx.line("Jornada 16", 0.75f, 0.60f, 0.12f),
            Fx.line("ANIVE11-10-26", 0.25f, 0.68f, 0.12f), Fx.line("Neftchi Fergana", 0.25f, 0.90f, 0.15f), Fx.line("UzM wLn7", 0.25f, 0.94f, 0.1f),
            Fx.line("12-10-26", 0.5f, 0.68f, 0.1f), Fx.line("Dinamo Samarkand", 0.5f, 0.90f, 0.15f),
            Fx.line("13-10-26", 0.75f, 0.68f, 0.1f), Fx.line("Quartos de final", 0.75f, 0.90f, 0.15f)
        )
        val ex = Parsers.calendar(o, null)
        val m14 = ex.matches.first { it.round == 14 }
        assertEquals("Neftchi Fergana", m14.opponent)
        assertEquals("11/10/26", m14.date)
    }

    @Test fun lastRowOfTheCalendarIsReadEvenWhenACupCardSaysFinal() {
        val o = Fx.ocr(
            Fx.line("Jornada 31", 0.25f, 0.64f, 0.12f), Fx.line("Jornada 32", 0.5f, 0.64f, 0.12f), Fx.line("Jornada 33", 0.75f, 0.64f, 0.12f),
            Fx.line("28-10-26", 0.25f, 0.72f, 0.1f), Fx.line("Neftchi Fergana", 0.25f, 0.93f, 0.15f),
            Fx.line("29-10-26", 0.5f, 0.72f, 0.1f), Fx.line("Dinamo Samarkand", 0.5f, 0.93f, 0.15f),
            Fx.line("30-10-26", 0.75f, 0.72f, 0.1f), Fx.line("Final", 0.75f, 0.93f, 0.08f), Fx.line("ASD", 0.75f, 0.97f, 0.05f)
        )
        val ex = Parsers.calendar(o, null)
        assertEquals(setOf(31, 32, 33), ex.matches.mapNotNull { it.round }.toSet())
        assertEquals("Neftchi Fergana", ex.matches.first { it.round == 31 }.opponent)
        // "Final" é a fase da copa, não o nome do rival
        assertEquals("Copa • Final", ex.matches.first { it.round == 33 }.label)
    }

    @Test fun reportWithoutLabelsReadsNothingAndAsksForAi() {
        val ex = Parsers.report(Fx.ocr(Fx.line("Mercado", 0.5f, 0.5f)))
        assertTrue(ex.fields.isEmpty())
        assertTrue(ex.needsAi)
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

    @Test fun topOfTheMatchReportIsAResultAndTellsMySlot() {
        // topo da análise do jogo (vídeo do usuário): Jornada 11, Nasaf 1-2 Pakhtakor, botão Rever
        val o = Fx.ocr(
            Fx.line("Casa", 0.01f, 0.127f, 0.03f), Fx.line("Jornada 11", 0.5f, 0.127f, 0.08f), Fx.line("Fora", 0.98f, 0.127f, 0.03f),
            Fx.line("Nasaf", 0.08f, 0.236f, 0.06f), Fx.line("leandrozzy", 0.09f, 0.283f, 0.08f, 0.02f),
            Fx.line("1-2", 0.5f, 0.26f, 0.06f, 0.08f),
            Fx.line("Pakhtakor", 0.9f, 0.236f, 0.08f), Fx.line("UzMRobozao", 0.9f, 0.283f, 0.08f, 0.02f),
            Fx.line("Rever", 0.5f, 0.39f, 0.05f),
            Fx.line("Bom golo de Cepeda. Esteve como peixe na água no Contra-ataque!", 0.3f, 0.53f, 0.45f)
        )
        assertEquals(ScreenType.RESULT, ScreenClassifier.classify(o, true))
        val ex = Parsers.matchResult(o)
        assertEquals("Nasaf", ex.ownerTeam)
        assertEquals(11, ex.matchReport?.round)
        assertEquals(1, ex.matchReport?.scoreHome)
        assertEquals(2, ex.matchReport?.scoreAway)
    }

    @Test fun zeroBonusAndBattleRoundAreRead() {
        val lines = Fx.pregameLines().map { if (it.text == "Jornada 4") Fx.line("Ronda 7", 0.5f, 0.126f, 0.08f) else it } +
            listOf(Fx.line("+0%", 0.33f, 0.30f, 0.04f), Fx.line("+3%", 0.67f, 0.30f, 0.04f), Fx.line("45m", 0.5f, 0.18f, 0.05f))
        val ex = Parsers.pregame(Fx.ocr(*lines.filter { it.text != "21h 5m 10s" }.toTypedArray()), null, 0L)
        assertEquals(7, ex.roundRead)
        assertEquals("+0%", ex.fields[K.RIVAL_LOGIN_BONUS]?.value)
        assertEquals("+3%", ex.fields[K.MY_BONUS]?.value)
        assertEquals((45L * 60000L).toString(), ex.fields[K.MATCH_AT]?.value)
    }
}
