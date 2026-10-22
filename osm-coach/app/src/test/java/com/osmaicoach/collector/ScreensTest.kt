package com.osmaicoach.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Telas novas vistas no vídeo de 22/10 (posições medidas nos quadros do vídeo). */
class ScreensTest {
    private fun cls(vararg lines: OcrLine): ScreenType? = Screens.classify(Txt.norm(Fx.ocr(*lines).fullText))

    @Test fun classifiesTheNewScreens() {
        assertEquals(ScreenType.MISSIONS, cls(Fx.line("Novas Missões dentro de: 3h", 0.2f, 0.3f, 0.3f), Fx.line("A semana reinicia em: 4d", 0.8f, 0.3f, 0.3f)))
        assertEquals(ScreenType.SPECIALISTS, cls(Fx.line("Seleciona os Especialistas para melhorares o desempenho da equipa", 0.4f, 0.1f, 0.5f), Fx.line("Capitão", 0.2f, 0.2f), Fx.line("Grandes penalidades", 0.4f, 0.2f), Fx.line("Livres", 0.6f, 0.2f), Fx.line("Substituir jogador", 0.2f, 0.9f)))
        assertEquals(ScreenType.DOCTOR, cls(Fx.line("Não tens qualquer jogador lesionado neste momento.", 0.4f, 0.8f, 0.4f), Fx.line("Médico", 0.9f, 0.95f)))
        assertEquals(ScreenType.LAWYER, cls(Fx.line("Não tens qualquer jogador suspenso neste momento.", 0.4f, 0.8f, 0.4f), Fx.line("Advogado", 0.9f, 0.95f)))
        assertEquals(ScreenType.SCOUT, cls(Fx.line("Seleciona uma posição", 0.2f, 0.3f), Fx.line("Procurar jogadores", 0.9f, 0.9f), Fx.line("Olheiro", 0.1f, 0.1f), Fx.line("Negociações", 0.3f, 0.1f)))
        assertEquals(ScreenType.STATS, cls(Fx.line("Jogadores transferidos", 0.1f, 0.3f), Fx.line("Golos marcados", 0.6f, 0.3f)))
        assertEquals(ScreenType.SPONSORS, cls(Fx.line("Acordo de patrocínio", 0.5f, 0.1f), Fx.line("Acordo de patrocínio", 0.1f, 0.4f)))
        assertEquals(ScreenType.DEALS, cls(Fx.line("Jogador (idade)", 0.1f, 0.2f), Fx.line("Hora", 0.7f, 0.2f), Fx.line("Preço", 0.9f, 0.2f)))
        assertEquals(ScreenType.PREP, cls(Fx.line("Preparação para o jogo", 0.2f, 0.05f), Fx.line("Lista de Afazeres", 0.6f, 0.05f), Fx.line("Posiciona os jogadores", 0.3f, 0.2f)))
        // o menu lateral ou uma tela comum não viram tela nova
        assertNull(cls(Fx.line("Plantel", 0.9f, 0.1f), Fx.line("Médico", 0.9f, 0.5f), Fx.line("Advogado", 0.9f, 0.6f)))
    }

    private fun greenThumbImg(rowYs: List<Float>): PixelProbe.Img {
        val w = 400
        val h = 200
        val px = IntArray(w * h) { 0xFF123456.toInt() }
        for (y in rowYs) for (yy in (y * h).toInt() until ((y + 0.04f) * h).toInt()) for (x in (0.79f * w).toInt() until (0.83f * w).toInt()) px[yy * w + x] = 0xFF22DD22.toInt()
        return PixelProbe.Img(w, h, px)
    }

    @Test fun readsDoneAndPendingFromThumbs() {
        val o = Fx.ocr(
            Fx.line("Posiciona os jogadores", 0.30f, 0.20f, 0.2f), Fx.line("Planeia um Treino Secreto", 0.30f, 0.45f, 0.2f), Fx.line("Escolhe o 11 inicial", 0.30f, 0.70f, 0.2f)
        )
        // só o 11 inicial tem o polegar verde (o desenho da imagem começa em yc, como no jogo)
        val j = Screens.prep(o, greenThumbImg(listOf(0.71f)))
        val items = j.getJSONArray("items")
        val m = (0 until items.length()).associate { items.getJSONObject(it).getString("k") to items.getJSONObject(it).getBoolean("done") }
        assertEquals(mapOf("posicao" to false, "secreto" to false, "onze" to true), m)
    }

    @Test fun readsTheFourSpecialistsLeftToRight() {
        val o = Fx.ocr(
            Fx.line("10 GIORGIAN DE ARRASCAETA", 0.72f, 0.52f, 0.12f), Fx.line("17 ADEMOLA LOOKMAN", 0.34f, 0.52f, 0.12f),
            Fx.line("10 GIORGIAN DE ARRASCAETA", 0.52f, 0.52f, 0.12f), Fx.line("10 GIORGIAN DE ARRASCAETA", 0.16f, 0.52f, 0.12f),
            Fx.line("IDADE: 32", 0.16f, 0.56f)
        )
        val j = Screens.specialists(o)
        assertEquals("GIORGIAN DE ARRASCAETA", j.getString("capitao"))
        assertEquals("ADEMOLA LOOKMAN", j.getString("penalti"))
        assertEquals("GIORGIAN DE ARRASCAETA", j.getString("escanteio"))
    }

    @Test fun rosterEmptyMessage() {
        val j = Screens.roster(Fx.ocr(Fx.line("Não tens qualquer jogador lesionado neste momento.", 0.4f, 0.8f, 0.4f)))
        assertTrue(j.getBoolean("empty"))
        val j2 = Screens.roster(Fx.ocr(Fx.line("Rodrigo Zalazar", 0.2f, 0.4f), Fx.line("Lesão grave", 0.5f, 0.4f)))
        assertFalse(j2.getBoolean("empty"))
        assertTrue(j2.getJSONArray("lines").toString().contains("Zalazar"))
    }

    @Test fun readsTransferHistoryRows() {
        val o = Fx.ocr(
            Fx.line("Maguire (33)", 0.07f, 0.31f, 0.1f), Fx.line("Manchester United", 0.33f, 0.31f, 0.14f), Fx.line("Fortuna Düsseldorf", 0.55f, 0.31f, 0.14f), Fx.line("17:05", 0.74f, 0.31f, 0.05f), Fx.line("10,2M", 0.93f, 0.31f, 0.05f),
            Fx.line("Mac Allister (27)", 0.07f, 0.40f, 0.12f), Fx.line("Liverpool FC", 0.33f, 0.40f, 0.1f), Fx.line("SG Sonnenhof", 0.55f, 0.40f, 0.1f), Fx.line("16:55", 0.74f, 0.40f, 0.05f), Fx.line("21,9M", 0.93f, 0.40f, 0.05f)
        )
        val rows = Screens.deals(o).getJSONArray("rows")
        assertEquals(2, rows.length())
        assertEquals("Maguire", rows.getJSONObject(0).getString("name"))
        assertEquals("Fortuna Düsseldorf", rows.getJSONObject(0).getString("to"))
        assertEquals(10.2, rows.getJSONObject(0).getDouble("price"), 0.001)
        assertEquals(17, rows.getJSONObject(0).getInt("h"))
    }

    @Test fun readsLeagueStats() {
        val o = Fx.ocr(
            Fx.line("Jogos", 0.55f, 0.55f), Fx.line("40", 0.55f, 0.60f, 0.03f),
            Fx.line("Golos marcados", 0.70f, 0.55f), Fx.line("117", 0.70f, 0.60f, 0.03f),
            Fx.line("Média de golos por jogo", 0.84f, 0.55f), Fx.line("2,9", 0.84f, 0.60f, 0.03f),
            Fx.line("Cartões amarelos", 0.70f, 0.72f), Fx.line("81", 0.70f, 0.76f, 0.03f)
        )
        val j = Screens.stats(o)
        assertEquals(40, j.getInt("games"))
        assertEquals(117, j.getInt("goals"))
        assertEquals(2.9, j.getDouble("avg"), 0.001)
        assertEquals(81, j.getInt("yellow"))
    }

    @Test fun readsMissionTimers() {
        val o = Fx.ocr(Fx.line("Novas Missões dentro de:", 0.20f, 0.30f, 0.2f), Fx.line("3h", 0.32f, 0.30f, 0.02f), Fx.line("A semana reinicia em:", 0.75f, 0.30f, 0.2f), Fx.line("4d", 0.87f, 0.30f, 0.02f))
        val j = Screens.missions(o)
        assertEquals(3 * 3600L, j.getLong("newIn"))
        assertEquals(4 * 86400L, j.getLong("weekIn"))
    }

    @Test fun scoutTimerOnlyWhenSearching() {
        assertTrue(Screens.scout(Fx.ocr(Fx.line("Procurar jogadores", 0.9f, 0.9f), Fx.line("16h", 0.9f, 0.93f, 0.02f))).isNull("endsIn"))
        assertEquals(15 * 3600L + 59 * 60 + 2, Screens.scout(Fx.ocr(Fx.line("15h 59m 02s", 0.9f, 0.93f, 0.06f))).getLong("endsIn"))
    }

    private fun p(n: String, cat: String, age: Int, str: Int) = PlayerEntity(1, "MY", Txt.key(n), n, age, null, cat, str, "5M", false, false, 0L)

    @Test fun recommendsSpecialistsByCommunityRules() {
        val xi = listOf(p("Old Gk", "GOL", 37, 80), p("Atk Star", "ATA", 24, 88), p("Atk B", "ATA", 30, 80), p("Mid A", "MEI", 27, 85), p("Mid B", "MEI", 25, 83), p("Mid C", "MEI", 26, 80), p("Def A", "DEF", 28, 82))
        val r = Screens.recommendSpecialists(xi).associateBy { it.role }
        assertEquals("Old Gk", r["capitao"]!!.player.name)
        assertEquals("Atk Star", r["penalti"]!!.player.name)
        assertEquals("Mid A", r["falta"]!!.player.name)
        assertEquals("Mid B", r["escanteio"]!!.player.name)
    }

    @Test fun freshnessAsksForTheRightScreens() {
        val h = 3600000L
        val now = 100 * h
        val seen = mapOf("TRAINING" to now - 20 * h, "SQUAD" to now - 2 * h, "MARKET" to now - h, "PREGAME" to now - 5 * h)
        val ended = Timers.Entry(2, "train_ATA", now - 1000L, "treino: X (avançados)")
        val needs = Fresh.needs(Fresh.In(2, seen, listOf(ended), matchAt = now + 30 * 60000L, sponsorEnding = false, now = now))
        val byType = needs.associateBy { it.type }
        assertEquals(2, byType[ScreenType.TRAINING]!!.urgency)       // terminou
        assertEquals(2, byType[ScreenType.PREGAME]!!.urgency)        // jogo em 30 min, lido há 5 h
        assertEquals(2, byType[ScreenType.DOCTOR]!!.urgency)         // jogo hoje, nunca li
        assertNull(byType[ScreenType.SQUAD])                          // lido há 2 h
        assertTrue(needs.first().urgency >= needs.last().urgency)
        assertTrue(Fresh.needs(Fresh.In(2, seen, emptyList(), null, false, now, active = false)).isEmpty())
    }

    @Test fun newTipsAppear() {
        val squad = listOf(
            p("Gk A", "GOL", 28, 80), p("Gk B", "GOL", 33, 70), p("Def A", "DEF", 24, 80), p("Def B", "DEF", 25, 79), p("Def C", "DEF", 26, 78), p("Def D", "DEF", 31, 70),
            p("Mid A", "MEI", 22, 82), p("Mid B", "MEI", 27, 80), p("Mid C", "MEI", 30, 76), p("Mid D", "MEI", 19, 70), p("Atk A", "ATA", 23, 83), p("Atk B", "ATA", 26, 80), p("Atk C", "ATA", 30, 71)
        )
        val xi = setOf("gka", "defa", "defb", "defc", "defd", "mida", "midb", "midc", "midd", "atka", "atkb")
        val tips = Playbook.tips(
            Playbook.Input(
                squad, xi, 1.0, null, 2, 2, 2, false, 8, 22, emptyList(), matchAt = 5 * 3600000L, now = 0L,
                prep = mapOf("onze" to true, "banco" to false, "estagio" to false),
                injured = setOf("defb"), suspended = setOf("atkc"), yellow = setOf("mida"),
                spec = mapOf("capitao" to "MID D", "penalti" to "ATK A", "falta" to "MID A", "escanteio" to "MID B"),
                needs = listOf(Fresh.Need(1, ScreenType.DOCTOR, 2, "o jogo é hoje"))
            )
        )
        val titles = tips.map { it.title }
        assertTrue(titles.any { it.startsWith("Fora do próximo jogo") && it.contains("Def B (lesão)") && it.contains("Atk C (suspenso)") })
        assertTrue(titles.any { it.startsWith("Titulares com cartão amarelo") && it.contains("Mid A") })
        assertTrue(titles.any { it.startsWith("Especialistas: ajuste") })
        assertTrue(titles.any { it.startsWith("Preparação do jogo: faltam 2") })
        assertEquals("Atualize: Médico", tips.first { it.icon == "🔄" }.title)
    }
}
