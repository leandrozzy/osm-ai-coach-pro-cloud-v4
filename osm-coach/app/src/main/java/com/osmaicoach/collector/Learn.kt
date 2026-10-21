package com.osmaicoach.collector

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Aprendizado de verdade: o modelo do jogo tem pesos de partida (o que a comunidade do OSM diz), e este objeto
 * corrige esses pesos com os GOLS reais dos seus jogos nos 4 slots. Para cada jogo com placar, compara quantos
 * gols o modelo esperava (seus e do rival) com o que aconteceu e descobre quanto cada escolha de tática
 * (estilo de jogo, mentalidade, número de atacantes, ritmo, avançados) mexe nos gols. Refeito a cada jogo novo.
 *
 * Regressão de Poisson com o modelo como base (offset) e regularização: com poucos jogos os ajustes ficam
 * perto de zero (vale o modelo); com mais jogos, o que os seus resultados mostram passa a mandar.
 */
object Learned {
    val LABELS = listOf(
        "Contra-ataque", "Jogar pelas alas", "Jogo de passe", "Remate à vista", "Bola longa",
        "Mentalidade alta", "Atacante a mais", "Ritmo alto", "Avançados ajudando a defender"
    )
    private val STYLE_ORDER = listOf("Contra-ataque", "Jogar pelas alas", "Jogo de passe", "Remate à vista", "Bola longa")
    const val MIN_GAMES = 8
    /** Com esta quantidade de jogos o aprendido já substitui as regras fixas de segurança. */
    const val TRUSTED_GAMES = 12
    private const val RIDGE = 2.0
    private const val MAX_BETA = 0.8

    class Model(val my: DoubleArray, val opp: DoubleArray, val n: Int) {
        /** Ajuste no log dos gols (meus, do rival) para uma tática. */
        fun effect(x: DoubleArray): Pair<Double, Double> {
            var a = 0.0
            var b = 0.0
            for (k in x.indices) { a += my[k] * x[k]; b += opp[k] * x[k] }
            return Pair(a.coerceIn(-1.0, 1.0), b.coerceIn(-1.0, 1.0))
        }

        val trusted: Boolean get() = n >= TRUSTED_GAMES
    }

    fun features(formation: String, style: String?, mentality: Int, tempo: Int, advAttack: String?): DoubleArray {
        val x = DoubleArray(LABELS.size)
        val st = Osm.style(style)
        STYLE_ORDER.indexOf(st).takeIf { it >= 0 }?.let { x[it] = 1.0 }
        x[5] = (mentality - 50) / 50.0
        x[6] = (TacticEngine.strikers(formation) - 2).toDouble()
        x[7] = (tempo - 50) / 50.0
        x[8] = if (Osm.attack(advAttack) == "Ajudar a defender") 1.0 else 0.0
        return x
    }

    private class Row(val x: DoubleArray, val baseMy: Double, val baseOpp: Double, val gMy: Int, val gOpp: Int, val w: Double)

    private fun bool(j: JSONObject, k: String): Boolean? = if (j.has(k) && !j.isNull(k)) j.optBoolean(k) else null
    private fun str(j: JSONObject, k: String): String? = if (j.has(k) && !j.isNull(k)) j.optString(k).ifBlank { null } else null

    /** Jogo registrado (tática usada + placar) -> linha de treino. Sem placar ou sem força: fica de fora. */
    private fun row(j: JSONObject): Row? {
        if (!j.has("scoreMine") || !j.has("scoreOpp")) return null
        val my = j.optInt("myStrength", -1).takeIf { it > 0 } ?: return null
        val rv = j.optInt("rivalStrength", -1).takeIf { it > 0 } ?: return null
        val formation = str(j, "formation") ?: return null
        val style = str(j, "playStyle")
        val m = j.optInt("mentality", 50)
        val t = j.optInt("tempo", 50)
        val plan = WinModel.Plan(
            formation = formation, style = style, pressure = j.optInt("pressure", 50), mentality = m, tempo = t,
            marking = str(j, "marking"), offside = str(j, "offside"), tackle = str(j, "tackle"),
            advAttack = str(j, "advAttack"), advMid = str(j, "advMid")
        )
        val inp = WinModel.Input(
            myStrength = my, rivalStrength = rv, plan = plan,
            rivalFormation = str(j, "oppFormation") ?: str(j, "rivalFormationRead"),
            rivalStyle = str(j, "rivalStyleRead"), home = bool(j, "home"), rivalHuman = bool(j, "human")
        )
        val (lm, lo) = WinModel.baseGoals(inp) ?: return null
        // jogos arquivados (temporada passada) pesam um pouco menos que os da competição atual
        val w = if (j.optBoolean("archived")) 0.7 else 1.0
        return Row(features(formation, style, m, t, str(j, "advAttack")), lm, lo, j.optInt("scoreMine").coerceIn(0, 6), j.optInt("scoreOpp").coerceIn(0, 6), w)
    }

    fun fit(logs: List<JSONObject>): Model? {
        val rows = logs.mapNotNull { runCatching { row(it) }.getOrNull() }
        if (rows.size < MIN_GAMES) return null
        val my = solve(rows.map { Triple(it.x, it.baseMy, it.gMy) }, rows.map { it.w })
        val opp = solve(rows.map { Triple(it.x, it.baseOpp, it.gOpp) }, rows.map { it.w })
        return Model(my, opp, rows.size)
    }

    /** Newton para Poisson com offset log(base) e penalidade L2. */
    private fun solve(data: List<Triple<DoubleArray, Double, Int>>, w: List<Double>): DoubleArray {
        val p = LABELS.size
        val b = DoubleArray(p)
        repeat(30) {
            val g = DoubleArray(p) { 2 * RIDGE * b[it] }
            val h = Array(p) { i -> DoubleArray(p) { k -> if (i == k) 2 * RIDGE else 0.0 } }
            for ((idx, d) in data.withIndex()) {
                val (x, base, y) = d
                var eta = 0.0
                for (k in 0 until p) eta += b[k] * x[k]
                val mu = base * exp(eta.coerceIn(-3.0, 3.0))
                val wi = w[idx]
                for (i in 0 until p) {
                    if (x[i] == 0.0) continue
                    g[i] += wi * (mu - y) * x[i]
                    for (k in 0 until p) if (x[k] != 0.0) h[i][k] += wi * mu * x[i] * x[k]
                }
            }
            val step = gauss(h, g) ?: return b
            var moved = 0.0
            for (i in 0 until p) {
                val nb = (b[i] - step[i]).coerceIn(-MAX_BETA, MAX_BETA)
                moved = maxOf(moved, abs(nb - b[i]))
                b[i] = nb
            }
            if (moved < 1e-5) return b
        }
        return b
    }

    private fun gauss(a0: Array<DoubleArray>, b0: DoubleArray): DoubleArray? {
        val n = b0.size
        val a = Array(n) { a0[it].copyOf() }
        val b = b0.copyOf()
        for (c in 0 until n) {
            val piv = (c until n).maxByOrNull { abs(a[it][c]) } ?: return null
            if (abs(a[piv][c]) < 1e-12) return null
            val tr = a[c]; a[c] = a[piv]; a[piv] = tr
            val tb = b[c]; b[c] = b[piv]; b[piv] = tb
            for (r in 0 until n) if (r != c) {
                val f = a[r][c] / a[c][c]
                if (f == 0.0) continue
                for (k in c until n) a[r][k] -= f * a[c][k]
                b[r] -= f * b[c]
            }
        }
        return DoubleArray(n) { b[it] / a[it][it] }
    }

    private fun pct(beta: Double): Int = ((exp(beta) - 1) * 100).roundToInt()

    /**
     * O que os seus jogos ensinaram, em português (os efeitos mais fortes primeiro).
     * Ex.: "Contra-ataque: seus gols −55%" ou "Mentalidade alta: gols do rival +20%".
     */
    fun explain(m: Model, max: Int = 4): List<String> {
        val out = ArrayList<Pair<Double, String>>()
        for (i in LABELS.indices) {
            val a = m.my[i]
            val b = m.opp[i]
            if (abs(a) < 0.08 && abs(b) < 0.08) continue
            val parts = ArrayList<String>()
            if (abs(a) >= 0.08) parts.add("seus gols ${sign(pct(a))}%")
            if (abs(b) >= 0.08) parts.add("gols do rival ${sign(pct(b))}%")
            out.add(Pair(abs(a) + abs(b), "${LABELS[i]}: " + parts.joinToString(", ")))
        }
        return out.sortedByDescending { it.first }.take(max).map { it.second }
    }

    private fun sign(v: Int): String = if (v > 0) "+$v" else "−${-v}"
}
