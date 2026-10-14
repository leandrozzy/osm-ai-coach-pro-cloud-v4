package com.osmaicoach.collector

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bônus por jogo, setor dos jogadores, repetidos no elenco, modelo da comunidade e IA. */
class FixesTest {
    private val h = 3600000L

    // ------------------------------------------------------------------ bônus

    @Test fun bonusFromAnotherGameNeverHoldsTheNewReading() {
        val now = 100L * 24 * h
        // digitado à mão há 6 dias: a leitura de hoje vale
        val manualOld = StoredField("+3%", 2.0, now - 6 * 24 * h)
        assertEquals("+7%", FieldMerge.merge(K.MY_BONUS, manualOld, Reading("+7%", 0.85), now)?.value)
        // digitado à mão agora há pouco: continua valendo
        val manualNew = StoredField("+3%", 2.0, now - h / 2)
        assertNull(FieldMerge.merge(K.MY_BONUS, manualNew, Reading("+7%", 0.85), now))
        // leitura do OCR de hoje troca a leitura anterior do OCR (o bônus muda)
        val ocr = StoredField("+3%", 0.85, now - 1 * h)
        assertEquals("+5%", FieldMerge.merge(K.MY_BONUS, ocr, Reading("+5%", 0.85), now)?.value)
        // mesmo valor lido num dia novo renova a data (continua valendo para o jogo de hoje)
        val same = FieldMerge.merge(K.RIVAL_LOGIN_BONUS, StoredField("+3%", 0.85, now - 20 * h), Reading("+3%", 0.85), now)
        assertEquals(now, same?.updatedAt)
    }

    @Test fun staleBonusIsNotUsedByTheTactic() {
        val now = 100L * 24 * h
        assertTrue(FieldMerge.fresh(K.MY_BONUS, StoredField("+3%", 2.0, now - 10 * h), now))
        assertFalse(FieldMerge.fresh(K.MY_BONUS, StoredField("+3%", 2.0, now - 6 * 24 * h), now))
        // outros campos não expiram por tempo
        assertTrue(FieldMerge.fresh(K.MY_STRENGTH, StoredField("75", 0.9, now - 30 * 24 * h), now))
    }

    // ------------------------------------------------------------------ setor dos jogadores

    @Test fun categoryComesFromSectionAndRowNumbers() {
        // meia com o código lido errado como "ED" (seria atacante): seção e números dizem meia
        assertEquals("MEI", Pos.resolve(Pos.cat("ED"), "MEI", Pos.catFromStats(40, 30, 90)))
        // sem seção visível: os números corrigem o código mal lido
        assertEquals("MEI", Pos.resolve(Pos.cat("ED"), null, Pos.catFromStats(40, 30, 90)))
        // defensor nunca vira atacante
        assertEquals("DEF", Pos.resolve(Pos.cat("PL"), null, Pos.catFromStats(20, 84, 30)))
        // goleiro x defensor: o código decide
        assertEquals("GOL", Pos.resolve(Pos.cat("GR"), "DEF", Pos.catFromStats(10, 82, 20)))
        // meia equilibrado (75/72/73): números não decidem, vale o código
        assertNull(Pos.catFromStats(75, 72, 73))
        assertEquals("MEI", Pos.resolve(Pos.cat("MCO"), null, Pos.catFromStats(75, 72, 73)))
        // código desconhecido de uma letra não vira atacante
        assertNull(Pos.cat("S"))
        assertNull(Pos.cat("X1"))
    }

    @Test fun squadRowWithMisreadCodeUnderMidfieldSectionStaysMidfielder() {
        val rows = ArrayList<OcrLine>()
        rows += Fx.line("Tobol", 0.05f, 0.228f, 0.05f)
        rows += Fx.line("leandrozzy", 0.06f, 0.263f, 0.08f)
        rows += Fx.line("Posição: 4", 0.08f, 0.149f, 0.1f)
        rows += Fx.line("Médios", 0.5f, 0.60f, 0.08f)
        fun row(y: Float, name: String, age: String, pos: String, a: String, d: String, m: String, v: String) {
            rows += Fx.line(name, 0.07f, y); rows += Fx.line(age, 0.555f, y, 0.03f); rows += Fx.line(pos, 0.62f, y, 0.03f)
            rows += Fx.line(a, 0.663f, y, 0.025f); rows += Fx.line(d, 0.70f, y, 0.025f); rows += Fx.line(m, 0.737f, y, 0.025f)
            rows += Fx.line(v, 0.95f, y, 0.04f)
        }
        row(0.67f, "Foden", "26", "ED", "40", "30", "90", "18,2M")
        row(0.77f, "Szoboszlai", "25", "MC", "50", "45", "89", "17,0M")
        val ex = Parsers.squad(Fx.ocr(*rows.toTypedArray()), null)
        assertEquals(listOf("MEI", "MEI"), ex.players.map { it.cat })
        assertEquals(listOf(90, 89), ex.players.map { it.strength })
    }

    // ------------------------------------------------------------------ repetidos no elenco

    @Test fun samePlayerReadWithDifferentNamesIsMergedButTwinsStay() {
        fun p(key: String, name: String, age: Int?, str: Int?, cat: String?, seen: Long) = SquadDedupe.P(key, name, age, str, cat, null, seen)
        val list = listOf(
            p("frimpong", "Frimpong", 25, 88, "DEF", 10),
            p("erimpong", "Erimpong", 25, 88, "DEF", 5),
            p("jtimber", "J. Timber", 25, 86, "DEF", 10),
            p("timber", "Timber", 25, 86, "DEF", 3),
            // mesmo sobrenome, setor e força diferentes: são dois jogadores
            p("qtimber", "Q. Timber", 25, 80, "MEI", 10),
            p("lopez", "Lopez", 23, 88, "MEI", 10),
            p("flopez", "F. Lopez", 23, 88, "MEI", 2)
        )
        val d = SquadDedupe.duplicates(list).toMap()
        assertEquals("frimpong", d["erimpong"])
        assertEquals("jtimber", d["timber"])
        assertEquals("lopez", d["flopez"])
        assertFalse(d.containsKey("qtimber"))
        assertEquals(3, d.size)
    }

    @Test fun differentAgeOrStrengthIsNeverTheSamePlayer() {
        assertFalse(SquadDedupe.samePlayer("silva", 25, 80, "rsilva", 30, 80))
        assertFalse(SquadDedupe.samePlayer("silva", 25, 70, "rsilva", 25, 88))
        assertTrue(SquadDedupe.samePlayer("silva", 25, 80, "rsilva", 25, 81))
    }

    // ------------------------------------------------------------------ modelo da comunidade

    @Test fun communityRulesAcceptEnglishAndOtherShapes() {
        val j = JSONObject(
            """{"rules": {
                "F|4-4-2": {"formation": "4-3-3 A", "style": "Wing play", "pressure": "70", "mentality": 74, "tempo": "65%",
                            "attack": "Attack only", "mid": "Stay in position", "def": "Defend deep", "marking": "Zonal", "offside": "No", "source": "osmnextlevel.com"},
                "weaker | 4-3-3 A": {"formation": "5-4-1", "style": "Counter attack", "marking": "Man-to-man"},
                "E": {"4-2-3-1": {"formation": "4-2-3-1", "style": "Passing game", "pressure": 55}},
                "X|9-9-9": {"formation": "4-3-3", "style": "Jogo de passe"},
                "U|?": {"formation": "nada", "style": "Contra-ataque"}
            }}"""
        )
        val r = MetaRefresh.cleanRules(j, "")
        assertEquals(3, r.length())
        val f = r.getJSONObject("F|4-4-2")
        assertEquals("4-3-3 A", f.getString("formation"))
        assertEquals("Jogar pelas alas", f.getString("style"))
        assertEquals(70, f.getInt("pressure"))
        assertEquals(65, f.getInt("tempo"))
        assertEquals("Atacar apenas", f.getString("attack"))
        assertEquals("Manter posições", f.getString("mid"))
        assertEquals("Defender atrás", f.getString("def"))
        assertEquals("À zona", f.getString("marking"))
        val u = r.getJSONObject("U|4-3-3")
        assertEquals("Contra-ataque", u.getString("style"))
        assertEquals("Homem-a-homem", u.getString("marking"))
        // controle que não veio fica de fora (o modelo embutido completa)
        assertFalse(u.has("pressure"))
        assertEquals("Jogo de passe", r.getJSONObject("E|4-2-3-1").getString("style"))
    }

    @Test fun communityRulesAsListAreRead() {
        val j = JSONObject(
            """{"rules": [{"situation": "F|4-3-3", "formation": "4-3-3 B", "style": "Passing game", "pressure": 65},
                          {"strength": "stronger", "rivalFormation": "5-4-1 A", "formation": "4-3-3 A", "style": "Wing play"}]}"""
        )
        val r = MetaRefresh.cleanRules(j, " • sem busca")
        assertNotNull(r.optJSONObject("F|4-3-3"))
        assertNotNull(r.optJSONObject("F|5-4-1"))
        assertTrue(r.getJSONObject("F|4-3-3").getString("source").endsWith("sem busca"))
    }

    @Test fun remoteRuleWithoutSlidersUsesBuiltInModel() {
        val remote = JSONObject().put("at", System.currentTimeMillis())
            .put("rules", JSONObject().put("U|4-3-3", JSONObject().put("formation", "5-4-1 A").put("style", "Contra-ataque")))
        val t = Meta.resolve(-8, true, "4-3-3 A", remote, emptyList())!!
        val builtIn = Meta.template(-8, null, "4-3-3 A")!!
        assertEquals("5-4-1 A", t.formation)
        assertEquals(builtIn.pressure, t.pressure)
        assertEquals(builtIn.mentality, t.mentality)
    }

    @Test fun jsonIsFoundInsideTextWithSeveralBlocks() {
        val raw = "Segundo as fontes [1] {nota}, eis o resultado:\n```json\n{\"rules\": {\"F|4-4-2\": {\"formation\": \"4-3-3 A\"}}}\n```\nFonte: {osm}"
        val j = AiClient.parseJson(raw)
        assertNotNull(j)
        assertTrue(j!!.has("rules"))
    }

    // ------------------------------------------------------------------ IA

    @Test fun severalGeminiKeysAreSplit() {
        val a = "AIzaSyA1234567890123456789012345678901"
        val b = "AIzaSyB1234567890123456789012345678902"
        assertEquals(listOf(a, b), Settings.splitKeys(" $a,\n$b ; $a "))
        assertTrue(Settings.splitKeys("curta").isEmpty())
    }

    @Test fun geminiCandidatesAlwaysHaveFreeTierFallbacks() {
        val c = ModelPicker.geminiCandidates(listOf("models/gemini-3.7-flash", "models/gemini-3.7-flash-lite", "models/gemini-2.5-pro"))
        assertEquals("gemini-3.7-flash", c.first())
        assertTrue(c.containsAll(ModelPicker.GEMINI_FALLBACK))
        assertEquals(c.size, c.distinct().size)
    }

    @Test fun visionModelsAreRankedAndKnownOnesExist() {
        val r = ModelPicker.rankCompatVision(listOf("llama-guard-4", "meta-llama/llama-4-scout-17b-16e-instruct", "gpt-4o-mini", "whisper-large-v3"))
        assertEquals(listOf("meta-llama/llama-4-scout-17b-16e-instruct", "gpt-4o-mini"), r)
        assertTrue(ModelPicker.knownVision("https://api.groq.com/openai/v1").isNotEmpty())
    }

    @Test fun quotaRenewsAtPacificMidnight() {
        // 14/10 06:30 UTC = 13/10 23:30 no Pacífico: ainda é o "dia" 13
        val t = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear(); set(2026, 9, 14, 6, 30)
        }.timeInMillis
        assertEquals("20261013", AiClient.quotaDay(t))
        assertEquals(30L * 60000L, AiClient.quotaResetAt(t) - t)
    }

    @Test fun quotaAndOverloadAreWaitedNotFailed() {
        assertTrue(Processor.transient("Gemini (0s): gemini-3.7-flash: cota diária esgotada"))
        assertTrue(Processor.transient("gemini-3.7-flash: HTTP 503 This model is currently overloaded"))
        assertFalse(Processor.transient("IA devolveu JSON inválido"))
    }

    @Test fun englishOptionNamesBecomeGameNames() {
        assertEquals("Contra-ataque", Osm.style("Counter-attack"))
        assertEquals("Remate à vista", Osm.style("Shoot on sight"))
        assertEquals("Jogo de passe", Osm.style("Passing game"))
        assertEquals("Agressivo", Osm.tackle("Aggressive"))
        assertEquals("Defesas atacantes", Osm.defense("Attacking full-backs"))
        assertEquals("Ajudar a defesa", Osm.midfield("Protect the defence"))
        // nomes do jogo continuam iguais
        assertEquals("Manter posições", Osm.midfield("Manter posições"))
        assertEquals("Pressionar na frente", Osm.midfield("Pressionar na frente"))
        assertEquals("Apoiar meio-campo", Osm.attack("Apoiar meio-campo"))
        assertEquals("Ajudar a defender", Osm.attack("Ajudar a defender"))
        assertEquals("Defender atrás", Osm.defense("Defender atrás"))
    }
}
