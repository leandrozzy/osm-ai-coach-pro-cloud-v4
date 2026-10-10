package com.osmaicoach.collector

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Diagnóstico completo dos 4 slots (campos lidos, calendário, táticas, relatórios, elenco e eventos) num
 * arquivo JSON para compartilhar. Não inclui chaves de IA nem imagens.
 */
object DiagExport {
    suspend fun build(ctx: Context): File {
        val repo = Repo(ctx)
        val dao = repo.dao
        val root = JSONObject()
        root.put("at", System.currentTimeMillis())
        root.put("lastError", Diag.lastError)
        val slots = JSONArray()
        for (slot in 1..4) {
            val s = JSONObject().put("slot", slot)
            val fields = JSONObject()
            for (f in dao.fieldsOf(slot)) {
                fields.put(f.fkey, JSONObject().put("v", f.fvalue).put("conf", f.conf).put("src", f.source).put("at", f.updatedAt))
            }
            s.put("fields", fields)
            val ms = JSONArray()
            for (m in dao.matchesOf(slot)) {
                ms.put(
                    JSONObject().put("key", m.mkey).put("label", m.label).put("round", m.round ?: JSONObject.NULL)
                        .put("date", m.date ?: JSONObject.NULL).put("time", m.time ?: JSONObject.NULL)
                        .put("home", m.home ?: JSONObject.NULL).put("score", if (m.scoreMine != null) "${m.scoreMine}-${m.scoreOpp}" else JSONObject.NULL)
                        .put("result", m.result ?: JSONObject.NULL).put("opp", m.opponent ?: JSONObject.NULL)
                        .put("nick", m.opponentNick ?: JSONObject.NULL).put("at", m.updatedAt)
                )
            }
            s.put("matches", ms)
            val plans = JSONObject()
            for (p in dao.plansOf(slot)) {
                plans.put(p.kind, runCatching { JSONObject(p.json) }.getOrElse { p.json.take(4000) })
            }
            s.put("plans", plans)
            val players = JSONArray()
            for (p in dao.playersOf(slot)) {
                players.put(
                    JSONObject().put("owner", p.owner).put("name", p.name).put("age", p.age ?: JSONObject.NULL)
                        .put("pos", p.posCode ?: JSONObject.NULL).put("str", p.strength ?: JSONObject.NULL)
                        .put("value", p.valueText ?: JSONObject.NULL)
                )
            }
            s.put("players", players)
            val learn = JSONArray()
            for (l in dao.learningOf(slot)) learn.put("${l.at} ${l.kind}: ${l.text}")
            s.put("events", learn)
            slots.put(s)
        }
        root.put("slots", slots)
        val dir = File(ctx.cacheDir, "diag").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "osm-coach-diagnostico-${System.currentTimeMillis() / 1000}.json")
        f.writeText(root.toString(1))
        return f
    }

    fun share(ctx: Context, f: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Diagnóstico OSM Coach")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Enviar diagnóstico").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
