package com.osmaicoach.collector

import android.content.Context
import android.util.Base64
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object Settings {
    private fun p(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun get(ctx: Context, key: String, def: String): String = p(ctx).getString(key, def) ?: def
    fun put(ctx: Context, key: String, value: String) { p(ctx).edit().putString(key, value.trim()).apply() }

    const val GEMINI_KEY = "gemini_key"
    const val GEMINI_MODEL = "gemini_model"
    const val COMPAT_KEY = "compat_key"
    const val COMPAT_BASE = "compat_base"
    const val COMPAT_MODEL = "compat_model"
    const val DAILY_CAP = "daily_cap"

    const val DEFAULT_GEMINI_MODEL = "gemini-flash-latest"
    const val DEFAULT_COMPAT_BASE = "https://api.groq.com/openai/v1"
    const val DEFAULT_COMPAT_MODEL = "llama-3.3-70b-versatile"
}

object AiClient {
    data class Reply(val ok: Boolean, val text: String?, val error: String?)

    private val gate = Mutex()
    private var lastCallAt = 0L

    private fun post(urlStr: String, headers: Map<String, String>, body: String): Pair<Int, String> {
        val c = URL(urlStr).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 20000
            c.readTimeout = 70000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val txt = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Pair(code, txt)
        } finally {
            c.disconnect()
        }
    }

    /** Respeita limites do plano gratuito: intervalo mínimo entre chamadas e teto diário. */
    private suspend fun throttle(ctx: Context): String? = gate.withLock {
        val p = ctx.getSharedPreferences("ai_usage", Context.MODE_PRIVATE)
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        val used = if (p.getString("day", "") == today) p.getInt("count", 0) else 0
        val cap = Settings.get(ctx, Settings.DAILY_CAP, "80").toIntOrNull() ?: 80
        if (used >= cap) return@withLock "Teto diário de IA atingido ($cap chamadas)."
        val wait = 13000L - (System.currentTimeMillis() - lastCallAt)
        if (wait > 0) delay(wait)
        lastCallAt = System.currentTimeMillis()
        p.edit().putString("day", today).putInt("count", used + 1).apply()
        null
    }

    fun usedToday(ctx: Context): Int {
        val p = ctx.getSharedPreferences("ai_usage", Context.MODE_PRIVATE)
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        return if (p.getString("day", "") == today) p.getInt("count", 0) else 0
    }

    private suspend fun gemini(ctx: Context, prompt: String, jpeg: ByteArray?): Reply = withContext(Dispatchers.IO) {
        val key = Settings.get(ctx, Settings.GEMINI_KEY, "")
        if (key.isBlank()) return@withContext Reply(false, null, "Chave do Gemini não configurada.")
        val model = Settings.get(ctx, Settings.GEMINI_MODEL, Settings.DEFAULT_GEMINI_MODEL)
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (jpeg != null) {
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                )
            )
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", 0.1).put("responseMimeType", "application/json"))
            .toString()
        var lastErr = "falha desconhecida"
        for (attempt in 1..3) {
            val capErr = throttle(ctx)
            if (capErr != null) return@withContext Reply(false, null, capErr)
            Diag.aiCalls.incrementAndGet()
            try {
                val (code, txt) = post(
                    "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
                    mapOf("x-goog-api-key" to key), body
                )
                if (code in 200..299) {
                    val t = JSONObject(txt).optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
                    return@withContext if (t.isNullOrBlank()) Reply(false, null, "Resposta vazia do Gemini.") else Reply(true, t, null)
                }
                lastErr = "Gemini HTTP $code: " + txt.take(160).replace("\n", " ")
                if (code != 429 && code != 503) break
                delay(20000L)
            } catch (e: Exception) {
                lastErr = "Gemini: " + (e.message ?: e.javaClass.simpleName)
                delay(5000L)
            }
        }
        Reply(false, null, lastErr)
    }

    private suspend fun compat(ctx: Context, prompt: String): Reply = withContext(Dispatchers.IO) {
        val key = Settings.get(ctx, Settings.COMPAT_KEY, "")
        if (key.isBlank()) return@withContext Reply(false, null, "Chave do provedor alternativo não configurada.")
        val base = Settings.get(ctx, Settings.COMPAT_BASE, Settings.DEFAULT_COMPAT_BASE).trimEnd('/')
        val model = Settings.get(ctx, Settings.COMPAT_MODEL, Settings.DEFAULT_COMPAT_MODEL)
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .put("response_format", JSONObject().put("type", "json_object"))
            .toString()
        try {
            val (code, txt) = post("$base/chat/completions", mapOf("Authorization" to "Bearer $key"), body)
            if (code in 200..299) {
                val t = JSONObject(txt).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                if (t.isNullOrBlank()) Reply(false, null, "Resposta vazia do provedor.") else Reply(true, t, null)
            } else Reply(false, null, "Provedor HTTP $code: " + txt.take(160).replace("\n", " "))
        } catch (e: Exception) {
            Reply(false, null, "Provedor: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    /** Gemini primeiro; sem imagem, cai para o provedor alternativo (Groq/xAI etc.) se o Gemini falhar. */
    suspend fun ask(ctx: Context, prompt: String, jpeg: ByteArray?): Reply {
        val g = gemini(ctx, prompt, jpeg)
        if (g.ok || jpeg != null) return g
        val c = compat(ctx, prompt)
        return if (c.ok) c else Reply(false, null, (g.error ?: "") + " | " + (c.error ?: ""))
    }

    fun parseJson(raw: String?): JSONObject? {
        if (raw == null) return null
        val s = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val a = s.indexOf('{')
        val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return try { JSONObject(s.substring(a, b + 1)) } catch (e: Exception) { null }
    }
}

object AiPrompts {
    private const val HEAD =
        "Esta é uma captura de tela do jogo OSM 26 (Online Soccer Manager), em português. " +
            "Leia SOMENTE o que está visível. Se um campo não estiver visível ou legível, use \"NI\". " +
            "Nunca invente. Responda APENAS com um objeto JSON, sem texto extra.\n"

    fun report(): String = HEAD +
        "Primeiro classifique a tela em \"kind\": \"rival_report\" se for o relatório/análise do time ADVERSÁRIO " +
        "(informativo: formação, estilo, marcação, desarme etc. do rival); \"own_tactic_editor\" se for a tela onde o " +
        "jogador DEFINE a própria tática (setas, controles deslizantes, \"Define a tua tática\"); senão \"other\". " +
        "Se kind não for rival_report, devolva só {\"kind\": \"...\"}. " +
        "Para rival_report as chaves são: " +
        "formation (ex.: \"4-4-2\" ou \"4-4-2 B\"), playStyle (estilo de jogo), marking (\"À zona\" ou \"Homem a homem\"), " +
        "offside (\"Sim\" ou \"Não\"), tackle (desarme: ex. \"Normal\" ou \"Agressivo\"), " +
        "secretTraining (\"Sim\" SOMENTE se houver um cadeado visível no relatório; caso contrário \"NI\"), " +
        "trainingCamp (\"Sim\"/\"Não\"/\"NI\"), rivalStrength (número), rivalValue (ex.: \"21,1M\" — mantenha a vírgula), " +
        "stadiumLevel (texto), stadiumBonus (texto), loginBonus (texto), referee (\"Brando\", \"Médio\" ou \"Rigoroso\" pela cor/nível do termômetro)."

    fun squad(): String = HEAD +
        "É a lista de elenco. Chaves: team (nome do time no cabeçalho), players (lista). Cada jogador: " +
        "name, age (número), pos (código como ED, PL, MC, DC, GR), strength (a força em negrito: Ata para atacantes, " +
        "Med para meias, Def para defensores e goleiros), value (ex.: \"7,3M\"), " +
        "training (true somente se a camisa for LARANJA; false se for azul/cinza; null se não der para ver). " +
        "Camisa laranja = jogador treinando. Cartão amarelo/vermelho NÃO é treino nem venda."
}

object AiMapper {
    private val rxFormation = Regex("^[3-5]-\\d-\\d(-\\d)?( ?[A-D])?$")

    private fun clean(v: String?): String? {
        if (v == null) return null
        val t = v.trim()
        return if (FieldMerge.known(t) && t.length <= 40) t else null
    }

    private fun yesNo(v: String?): String? = when (Txt.norm(v ?: "")) {
        "sim" -> "Sim"
        "nao" -> "Não"
        else -> null
    }

    /** Só aplica campos de rival se a IA confirmar que a tela é o relatório do adversário. */
    fun reportKind(j: JSONObject): String = j.optString("kind").trim().lowercase()

    fun report(j: JSONObject): Map<String, Reading> {
        val out = LinkedHashMap<String, Reading>()
        val c = 0.7
        clean(j.optString("formation"))?.takeIf { rxFormation.matches(it.uppercase()) }
            ?.let { out[K.RIVAL_FORMATION] = Reading(it.uppercase(), c) }
        clean(j.optString("playStyle"))?.let { out[K.RIVAL_PLAN] = Reading(it, c) }
        clean(j.optString("marking"))?.let { out[K.RIVAL_MARKING] = Reading(it, c) }
        yesNo(j.optString("offside"))?.let { out[K.RIVAL_OFFSIDE] = Reading(it, c) }
        clean(j.optString("tackle"))?.let { out[K.RIVAL_TACKLE] = Reading(it, c) }
        // Treino secreto só é aceito como "Sim" (cadeado visível); ausência de cadeado não prova "Não".
        if (yesNo(j.optString("secretTraining")) == "Sim") out[K.RIVAL_SECRET] = Reading("Sim", c)
        yesNo(j.optString("trainingCamp"))?.let { out[K.RIVAL_CAMP] = Reading(it, c) }
        j.optString("rivalStrength").trim().toIntOrNull()?.takeIf { it in 30..130 }
            ?.let { out[K.RIVAL_STRENGTH] = Reading(it.toString(), c) }
        clean(j.optString("rivalValue"))?.takeIf { Money.valid(it) }?.let { out[K.RIVAL_VALUE] = Reading(it, c) }
        clean(j.optString("stadiumLevel"))?.let { out[K.STADIUM] = Reading(it, c) }
        clean(j.optString("stadiumBonus"))?.let { out[K.STADIUM_BONUS] = Reading(it, c) }
        clean(j.optString("loginBonus"))?.let { out[K.RIVAL_LOGIN_BONUS] = Reading(it, c) }
        clean(j.optString("referee"))?.takeIf { it in setOf("Brando", "Médio", "Rigoroso") }
            ?.let { out[K.REFEREE] = Reading(it, 0.6) }
        return out
    }

    fun squad(j: JSONObject): Pair<String?, List<PlayerRead>> {
        val team = clean(j.optString("team"))
        val arr = j.optJSONArray("players") ?: return Pair(team, emptyList())
        val out = ArrayList<PlayerRead>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            if (Txt.letters(name) < 2) continue
            val age = o.optInt("age", -1).takeIf { it in 15..45 }
            val pos = o.optString("pos").trim().uppercase().takeIf { it.length in 1..4 && it.all { ch -> ch.isLetter() } }
            val strength = o.optInt("strength", -1).takeIf { it in 30..120 }
            val value = o.optString("value").trim().takeIf { Money.valid(it) }
            val training = if (o.has("training") && !o.isNull("training")) o.optBoolean("training") else null
            if (strength == null && value == null) continue
            out.add(PlayerRead(name, age, pos, Pos.cat(pos), strength, value, training, null))
        }
        return Pair(team, out)
    }
}

/** Roda ao encerrar a captura: reprocessa quadros sem slot e consulta a IA só para o que o OCR local não resolveu. */
object Processor {
    suspend fun run(ctx: Context, sessionId: String) {
        val repo = Repo(ctx)
        val dao = repo.dao
        FramePipeline.get(ctx).reprocessUnassigned()

        val hasKey = Settings.get(ctx, Settings.GEMINI_KEY, "").isNotBlank()
        for (s in dao.pendingAi(8)) {
            if (!hasKey) {
                dao.updateScreenAi(s.id, "skipped", s.extracted, "sem chave de IA")
                continue
            }
            if (s.slotId == 0) {
                dao.updateScreenAi(s.id, "pending", s.extracted, "aguardando identificação de slot")
                continue
            }
            val path = s.imagePath
            val bytes = if (path != null) File(path).takeIf { it.exists() }?.readBytes() else null
            if (bytes == null) {
                dao.updateScreenAi(s.id, "failed", s.extracted, "imagem ausente")
                continue
            }
            val type = runCatching { ScreenType.valueOf(s.type) }.getOrDefault(ScreenType.OTHER_OSM)
            val prompt = if (type == ScreenType.SQUAD) AiPrompts.squad() else AiPrompts.report()
            val reply = AiClient.ask(ctx, prompt, bytes)
            val json = AiClient.parseJson(reply.text)
            if (!reply.ok || json == null) {
                Diag.lastError = reply.error ?: "IA devolveu JSON inválido"
                dao.updateScreenAi(s.id, "failed", s.extracted, (reply.error ?: "JSON inválido").take(120))
                continue
            }
            val now = System.currentTimeMillis()
            if (type != ScreenType.SQUAD && AiMapper.reportKind(json) != "rival_report") {
                dao.updateScreenAi(s.id, "done", s.extracted, "IA: não é relatório do rival (" + AiMapper.reportKind(json) + ")")
                continue
            }
            val changed = if (type == ScreenType.SQUAD) {
                val (team, players) = AiMapper.squad(json)
                repo.apply(s.slotId, Extraction(ScreenType.SQUAD, players = players, ownerTeam = team), "ai", now)
            } else {
                repo.apply(s.slotId, Extraction(ScreenType.REPORT, fields = AiMapper.report(json)), "ai", now)
            }
            if (changed > 0) {
                Diag.extracted.addAndGet(changed)
                Diag.lastUpdateAt = now
            }
            dao.updateScreenAi(s.id, "done", s.extracted + changed, "IA aplicou $changed campos")
        }
    }
}
