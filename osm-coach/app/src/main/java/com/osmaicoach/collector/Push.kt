package com.osmaicoach.collector

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Avisos pela nuvem (ntfy.sh): reserva dos alarmes do Android. Alguns celulares (ASUS/ROG, Xiaomi…) apagam os
 * alarmes quando o app é fechado; o servidor do ntfy guarda cada aviso agendado e o entrega no horário para o app
 * ntfy do celular, mesmo com este app morto. Opt-in: só funciona depois de ativar em Ajustes e assinar o tópico
 * (aleatório, só do usuário) no app ntfy. Cada aviso tem um id fixo: repetir substitui, e some se deixar de valer.
 */
object Push {
    private const val BASE = "https://ntfy.sh"
    /** O aviso da nuvem sai um pouco depois do alarme local; se o local tocar, este é cancelado (sem repetir). */
    const val LATE_MS = 90_000L
    /** O ntfy aceita agendar no máximo 3 dias à frente. */
    private const val MAX_AHEAD_MS = 3L * 24 * 3600_000L - 10 * 60_000L

    data class Item(val seq: String, val at: Long, val title: String, val text: String)
    data class Plan(val publish: List<Item>, val delete: List<String>, val keep: Map<String, Long>)

    /** O que enviar/apagar: [sent] = id -> horário (já com o atraso) que o servidor tem agendado. */
    fun plan(want: List<Item>, sent: Map<String, Long>, now: Long): Plan {
        val wanted = want.filter { it.at > now && it.at - now <= MAX_AHEAD_MS }.associateBy { it.seq }
        val keep = LinkedHashMap<String, Long>()
        val del = ArrayList<String>()
        for ((k, at) in sent) {
            if (at <= now) continue // já foi entregue
            if (k in wanted) keep[k] = at else del.add(k)
        }
        val pub = wanted.values.filter { keep[it.seq] != it.at + LATE_MS }
        return Plan(pub, del, keep)
    }

    fun enabled(ctx: Context): Boolean = Settings.get(ctx, "push_on", "0") == "1"

    /** Tópico secreto e fixo deste aparelho (quem não tem o nome não consegue ler nem enviar). */
    fun topic(ctx: Context): String {
        val cur = Settings.get(ctx, "push_topic", "")
        if (cur.length >= 20) return cur
        val rnd = SecureRandom()
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val t = "osmcoach-" + (1..22).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        Settings.put(ctx, "push_topic", t)
        return t
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("push", Context.MODE_PRIVATE)

    private fun readSent(ctx: Context): MutableMap<String, Long> {
        val j = runCatching { JSONObject(prefs(ctx).getString("sent", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val m = LinkedHashMap<String, Long>()
        for (k in j.keys()) m[k] = j.optLong(k)
        return m
    }

    private fun writeSent(ctx: Context, m: Map<String, Long>) {
        val j = JSONObject()
        for ((k, v) in m) j.put(k, v)
        prefs(ctx).edit().putString("sent", j.toString()).apply()
    }

    private fun call(method: String, url: String, body: String?): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 8000
        c.readTimeout = 8000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        c.disconnect()
        code in 200..299 || (method == "DELETE" && code == 404)
    } catch (e: Exception) {
        false
    }

    private fun publish(ctx: Context, it: Item): Boolean {
        val body = JSONObject()
            .put("topic", topic(ctx))
            .put("title", it.title)
            .put("message", it.text)
            .put("priority", 4)
            .put("tags", JSONArray().put("soccer"))
            .put("delay", ((it.at + LATE_MS) / 1000L).toString())
            .put("sequence_id", it.seq)
        return call("POST", BASE, body.toString())
    }

    private fun delete(ctx: Context, seq: String): Boolean = call("DELETE", "$BASE/${topic(ctx)}/$seq", null)

    /** Deixa o servidor igual ao que vale agora (envia o novo, troca o que mudou, apaga o que não vale mais). */
    suspend fun sync(ctx: Context, want: List<Item>) {
        if (!enabled(ctx)) return
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val p = plan(want, readSent(ctx), now)
            val sent = LinkedHashMap(p.keep)
            for (k in p.delete) if (!delete(ctx, k)) sent[k] = readSent(ctx)[k] ?: (now + 1) // tenta de novo depois
            for (it in p.publish) if (publish(ctx, it)) sent[it.seq] = it.at + LATE_MS
            writeSent(ctx, sent)
        }
    }

    /** O alarme local tocou: o aviso da nuvem do mesmo evento não deve repetir. */
    suspend fun cancel(ctx: Context, seq: String) {
        if (!enabled(ctx)) return
        withContext(Dispatchers.IO) {
            val sent = readSent(ctx)
            if (sent.containsKey(seq) && delete(ctx, seq)) {
                sent.remove(seq)
                writeSent(ctx, sent)
            }
        }
    }

    /** Desligou: apaga tudo o que estava agendado no servidor. */
    suspend fun disable(ctx: Context) {
        withContext(Dispatchers.IO) {
            val sent = readSent(ctx)
            val left = LinkedHashMap<String, Long>()
            for ((k, v) in sent) if (!delete(ctx, k)) left[k] = v
            writeSent(ctx, left)
        }
    }

    /** Teste: o aviso chega em ~1 minuto pelo ntfy (feche este app e espere). */
    suspend fun test(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        publish(ctx, Item("test", System.currentTimeMillis() + 60_000L - LATE_MS, "✅ Aviso pela nuvem funcionando", "O OSM Coach avisará treinos, obras e jogos mesmo com o app fechado."))
    }
}
