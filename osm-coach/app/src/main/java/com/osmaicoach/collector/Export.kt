package com.osmaicoach.collector

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Diagnóstico COMPLETO do app: todas as tabelas (campos, elenco, calendário, mercado, relatórios, táticas, sessões,
 * telas lidas, aprendizado), os ajustes (chaves de IA só pelos 4 últimos caracteres), a situação da IA, o registro em
 * arquivo com o texto lido em cada tela e as imagens guardadas. Vai num .zip (com o .json também solto) salvo na
 * pasta Download/OSM Coach do celular, e pode ser compartilhado depois.
 */
object DiagExport {
    data class Result(
        val zipName: String, val zipUri: Uri?, val jsonName: String, val jsonUri: Uri?, val fallback: File?,
        val images: Int, val bytes: Long
    )

    private const val FOLDER = "OSM Coach"
    private const val IMAGE_BUDGET = 150L * 1024 * 1024

    private val SECRETS = setOf(Settings.GEMINI_KEY, Settings.CLAUDE_KEY, Settings.COMPAT_KEY)
    private val PREFS = listOf("ai_block", "ai_learn", "ai_usage", "collector_runtime", "formations", "health", "machine", "notifier", "ui")

    private fun n(v: Any?): Any = v ?: JSONObject.NULL

    private fun prefsDump(ctx: Context, name: String): JSONObject {
        val out = JSONObject()
        for ((k, v) in ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
            out.put(k, if (v is Set<*>) JSONArray(v.map { it.toString() }) else v?.toString() ?: JSONObject.NULL)
        }
        return out
    }

    /** Ajustes sem as chaves: de cada chave só a quantidade e os 4 últimos caracteres (para eu ver qual estourou). */
    private fun settingsDump(ctx: Context): JSONObject {
        val out = JSONObject()
        for ((k, v) in ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).all) {
            if (k in SECRETS) {
                val keys = Settings.splitKeys(v?.toString() ?: "")
                out.put(k, JSONObject().put("count", keys.size).put("labels", JSONArray(keys.map { AiClient.keyLabel(it) })))
            } else out.put(k, v?.toString() ?: JSONObject.NULL)
        }
        return out
    }

    private fun json(raw: String): Any = runCatching { JSONObject(raw) }.getOrElse { raw.take(20000) }

    suspend fun buildJson(ctx: Context): JSONObject {
        val repo = Repo(ctx)
        val dao = repo.dao
        val now = System.currentTimeMillis()
        val root = JSONObject()
        root.put("at", now)
        root.put("lastError", n(Diag.lastError))
        val pkg = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        root.put(
            "app", JSONObject().put("version", n(pkg?.versionName)).put("build", n(pkg?.longVersionCode))
                .put("device", Build.MANUFACTURER + " " + Build.MODEL).put("android", Build.VERSION.SDK_INT)
        )
        root.put(
            "counters", JSONObject().put("valid", Diag.valid.get()).put("discarded", Diag.discarded.get()).put("dedup", Diag.dedup.get())
                .put("ocr", Diag.ocr.get()).put("parsed", Diag.parsed.get()).put("extracted", Diag.extracted.get())
                .put("unassigned", Diag.unassigned.get()).put("aiCalls", Diag.aiCalls.get())
                .put("serviceConnected", Diag.serviceConnected).put("currentType", Diag.currentType).put("currentSlot", Diag.currentSlot)
        )
        root.put(
            "ai", JSONObject().put("gemini", AiClient.geminiStatus(ctx)).put("geminiAvailable", AiClient.geminiAvailable(ctx))
                .put("order", JSONArray(AiClient.providerOrder(ctx, false))).put("orderImage", JSONArray(AiClient.providerOrder(ctx, true)))
                .put("usedToday", AiClient.usedToday(ctx)).put("blocks", AiClient.blockDump(ctx)).put("stage", AiStatus.stage)
        )
        root.put("settings", settingsDump(ctx))
        val prefs = JSONObject()
        for (p in PREFS) prefs.put(p, prefsDump(ctx, p))
        root.put("prefs", prefs)

        val slots = JSONArray()
        for (slot in 1..4) {
            val s = JSONObject().put("slot", slot)
            val fields = JSONObject()
            for (f in dao.fieldsOf(slot)) {
                fields.put(f.fkey, JSONObject().put("v", f.fvalue).put("conf", f.conf).put("src", f.source).put("at", f.updatedAt))
            }
            s.put("fields", fields)
            val ms = JSONArray()
            for (m in dao.matchesOf(slot).sortedBy { it.round ?: 999 }) {
                ms.put(
                    JSONObject().put("key", m.mkey).put("label", m.label).put("round", n(m.round))
                        .put("date", n(m.date)).put("time", n(m.time))
                        .put("home", n(m.home)).put("score", if (m.scoreMine != null) "${m.scoreMine}-${m.scoreOpp}" else JSONObject.NULL)
                        .put("result", n(m.result)).put("opp", n(m.opponent))
                        .put("nick", n(m.opponentNick)).put("at", m.updatedAt)
                )
            }
            s.put("matches", ms)
            val plans = JSONObject()
            for (p in dao.plansOf(slot)) plans.put(p.kind, json(p.json))
            s.put("plans", plans)
            val players = JSONArray()
            val seen = runCatching { JSONObject(dao.plan(slot, "squadseen")?.json ?: "{}") }.getOrDefault(JSONObject())
            val all = dao.playersOf(slot)
            for (p in all.sortedWith(compareBy({ it.owner }, { it.cat ?: "?" }, { -(it.strength ?: 0) }))) {
                players.put(
                    JSONObject().put("owner", p.owner).put("key", p.nameKey).put("name", p.name).put("age", n(p.age))
                        .put("pos", n(p.posCode)).put("cat", n(p.cat)).put("str", n(p.strength))
                        .put("value", n(p.valueText)).put("training", n(p.training)).put("forSale", n(p.forSale))
                        .put("updatedAt", p.updatedAt).put("seenAt", seen.optLong(p.nameKey, 0L))
                )
            }
            s.put("players", players)
            val cats = JSONObject()
            for (c in listOf("ATA", "MEI", "DEF", "GOL")) cats.put(c, all.count { it.owner == "MY" && it.cat == c })
            cats.put("semSetor", all.count { it.owner == "MY" && it.cat == null })
            cats.put("total", all.count { it.owner == "MY" })
            s.put("squadCount", cats)
            val listings = JSONArray()
            for (l in dao.listingsOf(slot)) {
                listings.put(
                    JSONObject().put("name", l.name).put("age", n(l.age)).put("pos", n(l.posCode)).put("cat", n(l.cat))
                        .put("str", n(l.strength)).put("price", n(l.priceText)).put("club", n(l.club)).put("seller", n(l.sellerNick))
                        .put("seenAt", l.seenAt)
                )
            }
            s.put("listings", listings)
            val learn = JSONArray()
            for (l in dao.learningOf(slot)) learn.put("${l.at} ${l.kind}: ${l.text}")
            s.put("events", learn)
            slots.put(s)
        }
        root.put("slots", slots)

        val global = JSONObject()
        for (p in dao.plansOf(0)) global.put(p.kind, json(p.json))
        root.put("global", global)
        val snaps = JSONArray()
        for (x in dao.allSnapshots().take(400)) {
            snaps.put(JSONObject().put("kind", x.kind).put("slot", x.slotId).put("owner", n(x.owner)).put("json", json(x.json)).put("at", x.at))
        }
        root.put("snapshots", snaps)
        val sessions = JSONArray()
        for (x in dao.allSessions()) {
            sessions.put(JSONObject().put("id", x.id).put("startedAt", x.startedAt).put("endedAt", n(x.endedAt)).put("state", x.state))
        }
        root.put("sessions", sessions)
        val screens = JSONArray()
        for (x in dao.allScreens(4000)) {
            screens.put(
                JSONObject().put("id", x.id).put("session", x.sessionId).put("at", x.at).put("type", x.type).put("slot", x.slotId)
                    .put("slotConf", x.slotConf).put("ocrChars", x.ocrChars).put("extracted", x.extracted).put("ai", x.aiState)
                    .put("note", x.note).put("image", if (x.imagePath != null) imageName(x) else JSONObject.NULL)
            )
        }
        root.put("screens", screens)
        val learning = JSONArray()
        for (l in dao.allLearning(3000)) learning.put("${l.at} S${l.slotId} ${l.kind}: ${l.text}")
        root.put("learning", learning)
        root.put("pipeline", JSONArray(Diag.historyLines()))
        return root
    }

    private fun imageName(s: ScreenEntity): String = "imagens/${s.at}_${s.type}_S${s.slotId}.jpg"

    /** Monta o .zip no cache do app. */
    private suspend fun buildZip(ctx: Context, root: JSONObject, stamp: String): Pair<File, Int> {
        val dir = File(ctx.cacheDir, "diag").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val zip = File(dir, "osm-coach-diagnostico-$stamp.zip")
        var images = 0
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            z.putNextEntry(ZipEntry("diagnostico.json"))
            z.write(root.toString(1).toByteArray(Charsets.UTF_8))
            z.closeEntry()
            for (f in Diag.logFiles()) {
                z.putNextEntry(ZipEntry("registro/" + f.name))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
            var used = 0L
            for (s in Repo(ctx).dao.allScreens(4000)) {
                val f = s.imagePath?.let { File(it) }?.takeIf { it.exists() } ?: continue
                if (used + f.length() > IMAGE_BUDGET) break
                z.putNextEntry(ZipEntry(imageName(s)))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
                used += f.length()
                images++
            }
            z.putNextEntry(ZipEntry("LEIA-ME.txt"))
            z.write(
                ("Diagnóstico do OSM Coach gerado em $stamp.\n" +
                    "diagnostico.json: tudo o que o app guardou (campos, elenco, calendário, relatórios, táticas, telas, ajustes sem as chaves).\n" +
                    "registro/: o texto lido em cada tela (com a posição na tela) e os erros, inclusive de dias anteriores.\n" +
                    "imagens/: as telas guardadas (nome = instante_tipo_slot).\n").toByteArray(Charsets.UTF_8)
            )
            z.closeEntry()
        }
        return Pair(zip, images)
    }

    /** Grava na pasta Download/OSM Coach (sem pedir permissão: Android 10+). null se o sistema recusar. */
    private fun saveToDownloads(ctx: Context, name: String, mime: String, write: (java.io.OutputStream) -> Unit): Uri? {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { write(it) } ?: throw IllegalStateException("sem acesso ao arquivo")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            Diag.lastError = "Salvar diagnóstico: " + (e.message ?: e.javaClass.simpleName)
            null
        }
    }

    suspend fun export(ctx: Context): Result {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", java.util.Locale.US).format(java.util.Date())
        val root = buildJson(ctx)
        val (zip, images) = buildZip(ctx, root, stamp)
        val jsonName = "osm-coach-diagnostico-$stamp.json"
        val jsonUri = saveToDownloads(ctx, jsonName, "application/json") { it.write(root.toString(1).toByteArray(Charsets.UTF_8)) }
        val zipUri = saveToDownloads(ctx, zip.name, "application/zip") { out -> zip.inputStream().use { it.copyTo(out) } }
        val bytes = zip.length()
        // salvo na pasta Download: a cópia do cache não é mais necessária
        val fallback = if (zipUri == null) zip else {
            zip.delete()
            null
        }
        Diag.log("Diagnóstico exportado: ${zip.name} (${bytes / 1024} KB, $images imagens)" + if (zipUri == null) " — só no cache" else "")
        return Result(zip.name, zipUri, jsonName, jsonUri, fallback, images, bytes)
    }

    /** Abre o "Compartilhar" do Android com o arquivo já salvo. */
    fun share(ctx: Context, r: Result) {
        val uri = r.zipUri ?: r.fallback?.let { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", it) } ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Diagnóstico OSM Coach")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Enviar diagnóstico").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun summary(r: Result): String {
        val size = if (r.bytes >= 1024 * 1024) "%.1f MB".format(r.bytes / 1048576.0) else "${r.bytes / 1024} KB"
        return if (r.zipUri != null) {
            "Salvo no celular em Download/$FOLDER:\n• ${r.zipName} ($size, ${r.images} imagens)" +
                (if (r.jsonUri != null) "\n• ${r.jsonName} (só o texto)" else "")
        } else "Não consegui salvar na pasta Download; o arquivo ficou no app ($size). Use Compartilhar para enviar."
    }
}
