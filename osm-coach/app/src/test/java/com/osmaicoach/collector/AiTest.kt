package com.osmaicoach.collector

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTest {
    @Test fun reportKeepsOnlyValidatedFieldsAndNeverTurnsMissingLockIntoNo() {
        val j = JSONObject(
            """{"formation":"4-4-2 b","playStyle":"Jogar pelas alas","marking":"À zona","offside":"Não",
               "tackle":"Agressivo","secretTraining":"Não","trainingCamp":"Não","rivalStrength":"85","rivalValue":"211M",
               "referee":"Rigoroso"}"""
        )
        val r = AiMapper.report(j)
        assertEquals("4-4-2 B", r[K.RIVAL_FORMATION]?.value)
        assertEquals("Não", r[K.RIVAL_OFFSIDE]?.value)
        assertEquals("85", r[K.RIVAL_STRENGTH]?.value)
        assertNull(r[K.RIVAL_SECRET])
        assertEquals("Rigoroso", r[K.REFEREE]?.value)
        assertEquals("Sim", AiMapper.report(JSONObject("""{"secretTraining":"Sim"}"""))[K.RIVAL_SECRET]?.value)
    }

    @Test fun invalidAiValuesAreRejected() {
        val r = AiMapper.report(JSONObject("""{"formation":"9-9-9","rivalStrength":"999","rivalValue":"211","referee":"Bravo"}"""))
        assertTrue(r.isEmpty())
    }

    @Test fun squadMappingKeepsTrainingOnlyWhenExplicit() {
        val j = JSONObject(
            """{"team":"Tobol","players":[
               {"name":"Mbeumo","age":27,"pos":"ED","strength":95,"value":"25,5M","training":true},
               {"name":"Barnes","age":28,"pos":"EE","strength":88,"value":"16,1M","training":null},
               {"name":"X","age":28,"pos":"EE","strength":5,"value":"abc"}]}"""
        )
        val (team, players) = AiMapper.squad(j)
        assertEquals("Tobol", team)
        assertEquals(2, players.size)
        assertEquals(true, players[0].training)
        assertNull(players[1].training)
    }

    @Test fun jsonFencesAreStripped() {
        val j = AiClient.parseJson("```json\n{\"a\":1}\n```")
        assertNotNull(j)
        assertEquals(1, j!!.getInt("a"))
        assertNull(AiClient.parseJson("sem json"))
    }

    @Test fun tacticValidationRejectsBadSlidersAndFormation() {
        val ok = JSONObject("""{"formation":"4-5-1","playStyle":"Jogo de passe","pressure":60,"mentality":40,"tempo":55,"marking":"À zona","offside":"Sim","tackle":"Agressivo"}""")
        val (t, err) = TacticValidator.validate(ok, "Rigoroso")
        assertNull(err)
        assertEquals("Normal", t!!.tackle)
        assertTrue(t.notes.isNotEmpty())
        assertEquals("Agressivo", TacticValidator.validate(ok, "Brando").first!!.tackle)
        assertNotNull(TacticValidator.validate(JSONObject("""{"formation":"7-7-7","pressure":1,"mentality":1,"tempo":1}"""), null).second)
        assertNotNull(TacticValidator.validate(JSONObject("""{"formation":"4-4-2","pressure":150,"mentality":1,"tempo":1}"""), null).second)
        assertNotNull(TacticValidator.validate(JSONObject("""{"formation":"4-4-2","pressure":"alto","mentality":1,"tempo":1}"""), null).second)
    }

    @Test fun marketPlanDropsInventedPlayersAndUnaffordableBuys() {
        val j = JSONObject(
            """{"sell":[{"name":"Zirkzee","reason":"x"},{"name":"Inventado","reason":"y"},{"name":"Foden","reason":"z"}],
               "buy":[{"name":"Diaby-Fadiga","reason":"a"},{"name":"Caro Demais","reason":"b"},{"name":"Fantasma","reason":"c"}],
               "train":[{"name":"Foden","trainer":"médios","reason":"t"},{"name":"Nada","trainer":"médios","reason":"t"}],"summary":"ok"}"""
        )
        val squad = setOf(Txt.key("Zirkzee"), Txt.key("Foden"))
        val prices = mapOf(Txt.key("Diaby-Fadiga") to 10.3, Txt.key("Caro Demais") to 90.0)
        val out = MarketValidator.sanitize(j, squad, prices, 20.0, 1)
        assertEquals(1, out.getJSONArray("sell").length())
        assertEquals("Zirkzee", out.getJSONArray("sell").getJSONObject(0).getString("name"))
        assertEquals(1, out.getJSONArray("buy").length())
        assertEquals(1, out.getJSONArray("train").length())
    }
}
