package com.osmaicoach.collector

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * Placar da tática: o que o app previu x o que aconteceu, por slot. Usa os registros de tática com resultado
 * (inclusive das competições arquivadas).
 */
object Scoreboard {
    data class Row(val label: String, val games: Int, val v: Int, val e: Int, val d: Int) {
        val points: Double get() = if (games == 0) 0.0 else (3.0 * v + e) / games
    }

    data class Stats(
        val games: Int, val v: Int, val e: Int, val d: Int,
        /** Jogos em que o resultado mais provável da previsão foi o que aconteceu. */
        val hits: Int, val predicted: Int,
        /** Média da chance de vitória prevista x vitórias reais (%). */
        val avgWinPred: Int?, val winRate: Int,
        val best: Row?, val worst: Row?, val lastFive: List<String>
    )

    fun of(logs: List<JSONObject>): Stats {
        val done = logs.filter { !it.isNull("result") && it.optString("result") in setOf("V", "E", "D") }
            .sortedBy { it.optLong("at", 0L).takeIf { a -> a > 0 } ?: it.optInt("round", 0).toLong() }
        val v = done.count { it.optString("result") == "V" }
        val e = done.count { it.optString("result") == "E" }
        val d = done.size - v - e
        var hits = 0
        var predicted = 0
        var winSum = 0
        for (j in done) {
            if (!j.has("predW")) continue
            predicted++
            val w = j.optInt("predW")
            val dr = j.optInt("predE")
            val l = j.optInt("predD")
            winSum += w
            val pick = if (w >= dr && w >= l) "V" else if (l >= dr) "D" else "E"
            if (pick == j.optString("result")) hits++
        }
        val groups = done.groupBy { Formations.base(it.optString("formation")) + " • " + (Osm.style(it.optString("playStyle")) ?: "?") }
            .map { (k, g) ->
                Row(k, g.size, g.count { it.optString("result") == "V" }, g.count { it.optString("result") == "E" }, g.count { it.optString("result") == "D" })
            }.filter { it.games >= 2 }
        return Stats(
            done.size, v, e, d, hits, predicted,
            if (predicted > 0) (winSum.toDouble() / predicted).roundToInt() else null,
            if (done.isEmpty()) 0 else (v * 100.0 / done.size).roundToInt(),
            groups.maxByOrNull { it.points }, groups.filter { it.games >= 2 }.minByOrNull { it.points }?.takeIf { w -> groups.size > 1 },
            done.takeLast(5).map { it.optString("result") }
        )
    }
}

/**
 * Livro de caixa do elenco: compras (jogador novo no plantel) e vendas (saiu numa leitura completa do plantel).
 * Preço: o da lista de transferências quando o jogador foi visto lá; senão o valor do plantel (estimado).
 */
object Ledger {
    data class Entry(val type: String, val key: String, val name: String, val at: Long, val price: Double?, val estimated: Boolean, val strength: Int?, val age: Int?)

    data class Deal(val name: String, val bought: Double?, val sold: Double?, val gain: Int?, val estimated: Boolean) {
        val profit: Double? get() = if (bought != null && sold != null) sold - bought else null
    }

    data class Summary(val spent: Double, val received: Double, val buys: Int, val sells: Int, val deals: List<Deal>) {
        val balance: Double get() = received - spent
    }

    fun parse(json: String?): MutableList<Entry> {
        val out = ArrayList<Entry>()
        val arr = try { JSONArray(json ?: "[]") } catch (e: Exception) { JSONArray() }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Entry(
                    o.optString("t"), o.optString("k"), o.optString("n"), o.optLong("at"),
                    if (o.isNull("p")) null else o.optDouble("p"), o.optBoolean("est"),
                    if (o.isNull("s")) null else o.optInt("s"), if (o.isNull("a")) null else o.optInt("a")
                )
            )
        }
        return out
    }

    fun toJson(list: List<Entry>): String {
        val arr = JSONArray()
        for (e in list.takeLast(200)) {
            arr.put(
                JSONObject().put("t", e.type).put("k", e.key).put("n", e.name).put("at", e.at)
                    .put("p", e.price ?: JSONObject.NULL).put("est", e.estimated)
                    .put("s", e.strength ?: JSONObject.NULL).put("a", e.age ?: JSONObject.NULL)
            )
        }
        return arr.toString()
    }

    /** Não duplica: a mesma compra/venda do mesmo jogador em 3 dias é a mesma. */
    fun add(list: MutableList<Entry>, e: Entry): Boolean {
        val same = list.firstOrNull { it.type == e.type && it.key == e.key && kotlin.math.abs(it.at - e.at) < 3L * 86400000L }
        if (same != null) {
            // a leitura exata (tela de Negociações) vale mais que a estimada pelo elenco: troca, mantendo força e idade
            if (same.estimated && !e.estimated) {
                list[list.indexOf(same)] = e.copy(strength = e.strength ?: same.strength, age = e.age ?: same.age)
                return true
            }
            return false
        }
        list.add(e)
        return true
    }

    fun summary(list: List<Entry>): Summary {
        val buys = list.filter { it.type == "buy" }
        val sells = list.filter { it.type == "sell" }
        val deals = ArrayList<Deal>()
        for (s in sells.sortedByDescending { it.at }) {
            val b = buys.filter { it.key == s.key && it.at < s.at }.maxByOrNull { it.at }
            val gain = if (b?.strength != null && s.strength != null) s.strength - b.strength else null
            deals.add(Deal(s.name, b?.price, s.price, gain, s.estimated || b?.estimated == true))
        }
        for (b in buys.sortedByDescending { it.at }) {
            if (sells.any { it.key == b.key && it.at > b.at }) continue
            deals.add(Deal(b.name, b.price, null, null, b.estimated))
        }
        return Summary(buys.sumOf { it.price ?: 0.0 }, sells.sumOf { it.price ?: 0.0 }, buys.size, sells.size, deals)
    }
}

/**
 * Histórico de força e valor de cada jogador (a cada leitura do plantel que mudou algo) e se estava treinando.
 * Mostra quanto o treino rendeu: quem treinou x quem não treinou, no mesmo período.
 */
object Growth {
    data class Point(val at: Long, val strength: Int?, val value: Double?, val training: Boolean)

    data class TrainReport(
        val days: Int, val trained: Int, val trainedGain: Double, val others: Int, val othersGain: Double,
        val valueGain: Double, val top: List<Pair<String, Int>>
    ) {
        /** Pontos de força a mais por jogador treinado, comparado com quem não treinou. */
        val edge: Double get() = trainedGain - othersGain
    }

    fun record(hist: JSONObject, key: String, at: Long, strength: Int?, value: Double?, training: Boolean): Boolean {
        val arr = hist.optJSONArray(key) ?: JSONArray().also { hist.put(key, it) }
        val last = if (arr.length() > 0) arr.optJSONArray(arr.length() - 1) else null
        if (last != null) {
            val ls = if (last.isNull(1)) null else last.optInt(1)
            val lv = if (last.isNull(2)) null else last.optDouble(2)
            val lt = last.optInt(3) == 1
            // treinando agora entra mesmo sem mudar a força (marca o começo do treino)
            if (ls == strength && (lv == value || value == null) && (lt == training || !training)) return false
        }
        arr.put(JSONArray().put(at).put(strength ?: JSONObject.NULL).put(value ?: JSONObject.NULL).put(if (training) 1 else 0))
        while (arr.length() > 60) arr.remove(0)
        return true
    }

    fun points(hist: JSONObject, key: String): List<Point> {
        val arr = hist.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<Point>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONArray(i) ?: continue
            out.add(Point(a.optLong(0), if (a.isNull(1)) null else a.optInt(1), if (a.isNull(2)) null else a.optDouble(2), a.optInt(3) == 1))
        }
        return out
    }

    /** Jogadores perdendo valor (10%+ em 3 semanas): vender antes que caia mais. */
    fun falling(hist: JSONObject, players: List<PlayerEntity>, now: Long): List<Triple<String, Double, Double>> {
        val since = now - 21 * 86400000L
        val out = ArrayList<Triple<String, Double, Double>>()
        for (p in players) {
            val pts = points(hist, p.nameKey).filter { it.value != null }
            val first = pts.firstOrNull { it.at >= since } ?: continue
            val before = pts.lastOrNull { it.at < since } ?: first
            val last = pts.last()
            val v0 = before.value ?: continue
            val v1 = last.value ?: continue
            if (v0 > 0 && (v0 - v1) / v0 >= 0.10) out.add(Triple(p.name, v0, v1))
        }
        return out.sortedByDescending { it.second - it.third }
    }

    fun report(hist: JSONObject, names: Map<String, String>, now: Long, days: Int = 14): TrainReport? {
        val since = now - days * 86400000L
        var tN = 0
        var tSum = 0
        var oN = 0
        var oSum = 0
        var vSum = 0.0
        val top = ArrayList<Pair<String, Int>>()
        for (k in names.keys) {
            val pts = points(hist, k).filter { it.strength != null }
            val inWin = pts.filter { it.at >= since }
            if (inWin.isEmpty()) continue
            val before = pts.lastOrNull { it.at < since } ?: inWin.first()
            val after = inWin.last()
            if (after === before) continue
            val gain = (after.strength ?: 0) - (before.strength ?: 0)
            val trained = pts.any { it.training && it.at >= since - 86400000L }
            if (trained) {
                tN++
                tSum += gain
                if (gain > 0) top.add(Pair(names[k] ?: k, gain))
            } else {
                oN++
                oSum += gain
            }
            if (after.value != null && before.value != null) vSum += after.value - before.value
        }
        if (tN + oN == 0) return null
        return TrainReport(
            days, tN, if (tN > 0) tSum.toDouble() / tN else 0.0, oN, if (oN > 0) oSum.toDouble() / oN else 0.0,
            vSum, top.sortedByDescending { it.second }.take(5)
        )
    }
}

/** Resumo da semana por slot: valor do elenco, caixa, força do XI e jogos — comparado com a semana anterior. */
object Weekly {
    data class Week(val id: Int, val at: Long, val value: Double?, val cash: Double?, val xi: Double?, val v: Int, val e: Int, val d: Int)

    fun weekId(at: Long): Int {
        val c = Calendar.getInstance()
        c.timeInMillis = at
        c.minimalDaysInFirstWeek = 4
        c.firstDayOfWeek = Calendar.MONDAY
        return c.get(Calendar.YEAR) * 100 + c.get(Calendar.WEEK_OF_YEAR)
    }

    fun parse(json: String?): MutableList<Week> {
        val arr = try { JSONArray(json ?: "[]") } catch (e: Exception) { JSONArray() }
        val out = ArrayList<Week>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            fun d(n: String) = if (o.isNull(n) || !o.has(n)) null else o.optDouble(n)
            out.add(Week(o.optInt("w"), o.optLong("at"), d("val"), d("cash"), d("xi"), o.optInt("v"), o.optInt("e"), o.optInt("d")))
        }
        return out
    }

    fun toJson(list: List<Week>): String {
        val arr = JSONArray()
        for (w in list.takeLast(26)) {
            arr.put(
                JSONObject().put("w", w.id).put("at", w.at).put("val", w.value ?: JSONObject.NULL).put("cash", w.cash ?: JSONObject.NULL)
                    .put("xi", w.xi ?: JSONObject.NULL).put("v", w.v).put("e", w.e).put("d", w.d)
            )
        }
        return arr.toString()
    }

    /** Atualiza a semana atual (vale a leitura mais recente da semana). */
    fun upsert(list: MutableList<Week>, w: Week) {
        val i = list.indexOfFirst { it.id == w.id }
        if (i >= 0) list[i] = w.copy(
            value = w.value ?: list[i].value, cash = w.cash ?: list[i].cash, xi = w.xi ?: list[i].xi
        ) else list.add(w)
        list.sortBy { it.id }
    }

    data class Delta(val cur: Week, val prev: Week?) {
        val value: Double? get() = cur.value?.let { c -> prev?.value?.let { c - it } }
        val cash: Double? get() = cur.cash?.let { c -> prev?.cash?.let { c - it } }
        val xi: Double? get() = cur.xi?.let { c -> prev?.xi?.let { c - it } }
        val wins: Int get() = cur.v - (prev?.v ?: 0)
        val games: Int get() = (cur.v + cur.e + cur.d) - ((prev?.v ?: 0) + (prev?.e ?: 0) + (prev?.d ?: 0))
    }

    fun delta(list: List<Week>): Delta? {
        val cur = list.lastOrNull() ?: return null
        return Delta(cur, list.dropLast(1).lastOrNull())
    }

    /** Força média dos 11 melhores. */
    fun xi(strengths: List<Int>): Double? {
        val top = strengths.sortedDescending().take(11)
        return if (top.size < 11) null else top.average()
    }
}

/** Texto curto de dinheiro em milhões ("+1,8M", "-350K"). */
fun moneyText(m: Double, sign: Boolean = false): String {
    val s = if (sign && m > 0) "+" else if (m < 0) "-" else ""
    val a = kotlin.math.abs(m)
    val body = if (a >= 1.0) String.format(java.util.Locale("pt", "BR"), "%.1fM", a) else "${(a * 1000).roundToInt()}K"
    return s + body
}
