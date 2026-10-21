package com.osmaicoach.collector

import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LearnedTest {
    private fun game(f: String, st: String, m: Int, gm: Int, go: Int, my: Int = 76, rv: Int = 82) = JSONObject()
        .put("formation", f).put("playStyle", st).put("mentality", m).put("tempo", 60).put("pressure", 40)
        .put("myStrength", my).put("rivalStrength", rv).put("human", true).put("scoreMine", gm).put("scoreOpp", go)

    @Test fun learnsThatCounterAttackScoresNothing() {
        val logs = List(8) { game("5-4-1 A", "Contra-ataque", 30, 0, 1) } + List(6) { game("5-3-2", "Jogar pelas alas", 60, 2, 1) }
        val m = Learned.fit(logs)
        assertNotNull(m)
        assertTrue("contra-ataque deve tirar gols", m!!.my[0] < -0.2)
        assertTrue("alas deve dar gols", m.my[1] > 0.1)
        assertTrue(m.trusted)
        assertTrue(Learned.explain(m).first().startsWith("Contra-ataque") || Learned.explain(m).any { it.startsWith("Contra-ataque") })
        // a previsão passa a preferir o que deu gol
        fun ep(f: String, st: String, mm: Int) = WinModel.expectedPoints(
            WinModel.Input(76, 82, plan = WinModel.Plan(f, st, 40, mm, 60), rivalHuman = true, learned = m, drawValue = 0.4)
        )!!
        assertTrue(ep("5-3-2", "Jogar pelas alas", 60) > ep("5-4-1 A", "Contra-ataque", 30))
    }

    @Test fun fewGamesLearnNothing() {
        assertNull(Learned.fit(List(3) { game("4-3-3 A", "Jogar pelas alas", 70, 3, 0) }))
    }

    /** Roda com os dados reais quando o diagnóstico estiver na máquina (-Ddiag=...). */
    @Test fun realDiagnostic() {
        val path = System.getenv("OSM_DIAG") ?: return
        val d = JSONObject(File(path).readText())
        val logs = ArrayList<JSONObject>()
        val slots = d.getJSONArray("slots")
        for (i in 0 until slots.length()) {
            val p = slots.getJSONObject(i).getJSONObject("plans")
            for (k in p.keys()) if (k.startsWith("tlog_")) {
                val v = p.get(k)
                logs.add((if (v is String) JSONObject(v) else v as JSONObject).also { if (k.startsWith("tlog_X")) it.put("archived", true) })
            }
        }
        val m = Learned.fit(logs)!!
        println("JOGOS ${m.n}")
        for (i in Learned.LABELS.indices) println("%-32s meus %+.2f  rival %+.2f".format(Learned.LABELS[i], m.my[i], m.opp[i]))
        println(Learned.explain(m, 9))
        fun ep(f: String, st: String, mm: Int, t: Int, adv: String, learned: Learned.Model?) = WinModel.predict(
            WinModel.Input(77, 83, myAtk = 79, myMid = 74, myDef = 75, rivalAtk = 84, rivalMid = 89, rivalDef = 84,
                plan = WinModel.Plan(f, st, 35, mm, t, advAttack = adv), rivalFormation = "5-2-3 A", rivalStyle = "Contra-ataque",
                home = false, rivalHuman = true, learned = learned, drawValue = 0.4)
        )!!
        for (l in listOf(null, m)) {
            println(if (l == null) "--- sem aprendizado" else "--- com aprendizado")
            for ((f, st, mm) in listOf(Triple("5-4-1 A", "Contra-ataque", 48), Triple("5-3-2", "Jogar pelas alas", 60), Triple("4-3-3 A", "Jogar pelas alas", 60), Triple("5-3-2", "Contra-ataque", 40))) {
                val p = ep(f, st, mm, 66, "Atacar apenas", l)
                println("$f $st m$mm -> V${p.win} E${p.draw} D${p.loss} gols %.2f x %.2f pts %.2f".format(p.xgMine, p.xgOpp, 3 * p.win / 100.0 + 0.4 * p.draw / 100.0))
            }
        }
    }
}
