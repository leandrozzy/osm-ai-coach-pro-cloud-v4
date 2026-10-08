package com.osmaicoach.collector

import android.content.Context
import android.util.Base64
import java.io.File
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
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

    /** Versões antigas gravaram nomes de modelo que travam ou não existem mais: volta para detecção automática. */
    fun migrate(ctx: Context) {
        if (get(ctx, "settings_v3", "") == "1") return
        if (get(ctx, "gemini_model", "") == "gemini-flash-latest") put(ctx, "gemini_model", "auto")
        if (get(ctx, "compat_model", "") == "llama-3.3-70b-versatile") put(ctx, "compat_model", "auto")
        put(ctx, "gemini_model_resolved", "")
        put(ctx, "compat_model_resolved", "")
        put(ctx, "compat_vision_resolved", "")
        put(ctx, "settings_v3", "1")
    }

    const val GEMINI_KEY = "gemini_key"
    const val GEMINI_MODEL = "gemini_model"
    const val GEMINI_RESOLVED = "gemini_model_resolved"
    const val COMPAT_KEY = "compat_key"
    const val COMPAT_BASE = "compat_base"
    const val COMPAT_MODEL = "compat_model"
    const val COMPAT_RESOLVED = "compat_model_resolved"
    const val COMPAT_VISION = "compat_vision_resolved"
    const val CLAUDE_KEY = "claude_key"
    const val CLAUDE_MODEL = "claude_model"
    const val PREFERRED = "preferred_provider"
    const val DAILY_CAP = "daily_cap"

    /** "auto" = o app pergunta à API quais modelos existem e escolhe um válido. */
    const val DEFAULT_GEMINI_MODEL = "auto"
    const val DEFAULT_COMPAT_BASE = "https://api.groq.com/openai/v1"
    const val DEFAULT_COMPAT_MODEL = "auto"
    const val DEFAULT_CLAUDE_MODEL = "claude-haiku-4-5-20251001"
}

/** Etapa atual da chamada de IA (para a interface mostrar o que está acontecendo). */
object AiStatus {
    @Volatile var stage: String = ""
    @Volatile var since: Long = 0L

    fun set(s: String) {
        stage = s
        since = System.currentTimeMillis()
    }
}

object ModelPicker {
    private data class Cand(val id: String, val stable: Boolean, val version: Double, val lite: Boolean)

    /**
     * Ordem aprendida: quem respondeu por último vai para a frente (lista "winners", mais recente primeiro);
     * o resto mantém a ordem base. Só reordena itens que existem na base.
     */
    fun learnedOrder(base: List<String>, winners: List<String>): List<String> {
        val first = winners.filter { it in base }.distinct()
        return first + base.filter { it !in first }
    }

    /** Escolhe o Flash estável mais novo (sem lite/preview/imagem/áudio) entre os modelos que a chave enxerga. */
    fun pickGemini(names: List<String>): String? = rankGemini(names).firstOrNull()

    /** Lista ordenada dos melhores Flash estáveis (para tentar o próximo quando um estiver sobrecarregado). */
    fun rankGemini(names: List<String>): List<String> {
        val banned = listOf("image", "tts", "live", "audio", "embedding", "robotics", "computer", "exp", "thinking", "8b")
        val cands = names.map { it.removePrefix("models/") }
            .filter { n -> n.startsWith("gemini") && n.contains("flash") && banned.none { n.contains(it) } }
            .map { n ->
                Cand(
                    n,
                    !n.contains("preview") && !n.contains("latest"),
                    Regex("gemini-(\\d+(?:\\.\\d+)?)").find(n)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0,
                    n.contains("lite")
                )
            }
        return cands.sortedWith(
            compareByDescending<Cand> { it.stable }.thenByDescending { !it.lite }.thenByDescending { it.version }
        ).map { it.id }
    }

    /** Modelo com visão (aceita imagem) entre os que o provedor lista; null se não houver. */
    fun pickCompatVision(ids: List<String>): String? {
        val prefs = listOf("llama-4-maverick", "llama-4-scout", "vision", "pixtral", "-vl")
        for (p in prefs) {
            val hit = ids.firstOrNull { it.contains(p, ignoreCase = true) && !it.contains("guard", ignoreCase = true) }
            if (hit != null) return hit
        }
        return null
    }

    /** Escolhe um modelo de texto grande entre os que o provedor (Groq/xAI/OpenAI-compatível) lista. */
    fun pickCompat(ids: List<String>): String? {
        val bad = listOf("whisper", "tts", "guard", "embed", "playai", "orpheus", "distil", "moderation", "image")
        val ok = ids.filter { id -> bad.none { id.contains(it, ignoreCase = true) } }
        val prefs = listOf("llama-3.3-70b", "gpt-oss-120b", "llama-3.1-70b", "gpt-oss-20b", "qwen", "grok", "llama-3.1-8b", "llama")
        for (p in prefs) {
            val hit = ok.firstOrNull { it.contains(p, ignoreCase = true) }
            if (hit != null) return hit
        }
        return ok.firstOrNull()
    }
}

object AiClient {
    data class Reply(val ok: Boolean, val text: String?, val error: String?)

    private class HttpResult(val code: Int, val body: String)

    private val gate = Mutex()
    private var lastGeminiAt = 0L
    // Intervalo curto por padrão; só desacelera (7 s) por 10 min depois que o Gemini responder 429.
    @Volatile private var slowUntil = 0L
    private val cooldownUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun cool(provider: String, ms: Long) {
        cooldownUntil[provider] = System.currentTimeMillis() + ms
    }
    private const val CONNECT_MS = 10000
    private const val READ_MS = 45000

    private fun request(method: String, urlStr: String, headers: Map<String, String>, body: String?): HttpResult {
        val c = URL(urlStr).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = CONNECT_MS
            c.readTimeout = READ_MS
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val txt = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return HttpResult(code, txt)
        } finally {
            c.disconnect()
        }
    }

    /** Só a mensagem do erro da API (o corpo inteiro em JSON é ilegível na tela). */
    private fun short(s: String): String {
        val msg = try {
            JSONObject(s).optJSONObject("error")?.optString("message")?.ifBlank { null }
        } catch (e: Exception) {
            null
        }
        return (msg ?: s).replace("\n", " ").replace(Regex("\\s+"), " ").take(140)
    }

    // ---------------------------------------------------------------- cotas do Gemini por modelo

    private fun blockPrefs(ctx: Context) = ctx.getSharedPreferences("ai_block", Context.MODE_PRIVATE)

    /** Modelo com a cota DIÁRIA esgotada: não é tentado de novo até amanhã (economiza tempo e chamadas). */
    private fun blockedToday(ctx: Context, model: String): Boolean = blockPrefs(ctx).getString(model, "") == today()

    private fun blockForToday(ctx: Context, model: String) {
        blockPrefs(ctx).edit().putString(model, today()).apply()
    }

    /** 429 por cota diária (ou plano sem cota) x limite por minuto (passa esperando alguns segundos). */
    private fun dailyQuota(body: String): Boolean {
        val b = body.lowercase()
        return b.contains("perday") || b.contains("per day") || b.contains("limit: 0") ||
            (b.contains("exceeded your current quota") && !b.contains("perminute"))
    }

    /** "retryDelay": "17s" no corpo do 429 por minuto. */
    private fun retryDelayMs(body: String): Long? =
        Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)(?:\\.\\d+)?s\"").find(body)?.groupValues?.get(1)?.toLongOrNull()?.times(1000L)

    private fun today(): String = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())

    fun usedToday(ctx: Context): Int {
        val p = ctx.getSharedPreferences("ai_usage", Context.MODE_PRIVATE)
        return if (p.getString("day", "") == today()) p.getInt("count", 0) else 0
    }

    /** Plano gratuito do Gemini: respeita intervalo mínimo e teto diário (só vale para o Gemini). */
    private suspend fun throttleGemini(ctx: Context): String? = gate.withLock {
        val p = ctx.getSharedPreferences("ai_usage", Context.MODE_PRIVATE)
        val used = if (p.getString("day", "") == today()) p.getInt("count", 0) else 0
        val cap = Settings.get(ctx, Settings.DAILY_CAP, "80").toIntOrNull() ?: 80
        if (used >= cap) return@withLock "teto diário de $cap chamadas atingido"
        val gap = if (System.currentTimeMillis() < slowUntil) 7000L else 1200L
        val wait = gap - (System.currentTimeMillis() - lastGeminiAt)
        if (wait > 0) delay(wait)
        lastGeminiAt = System.currentTimeMillis()
        p.edit().putString("day", today()).putInt("count", used + 1).apply()
        null
    }

    // ---------------------------------------------------------------- descoberta de modelos

    private fun listGemini(key: String): List<String> {
        val r = request("GET", "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200", mapOf("x-goog-api-key" to key), null)
        if (r.code !in 200..299) return emptyList()
        val arr = JSONObject(r.body).optJSONArray("models") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val methods = o.optJSONArray("supportedGenerationMethods")
            var gen = methods == null
            if (methods != null) for (j in 0 until methods.length()) if (methods.optString(j) == "generateContent") gen = true
            if (gen) out.add(o.optString("name"))
        }
        return out
    }

    private fun listCompat(base: String, key: String): List<String> {
        val r = request("GET", "$base/models", mapOf("Authorization" to "Bearer $key"), null)
        if (r.code !in 200..299) return emptyList()
        val arr = JSONObject(r.body).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.optString("id")?.let { out.add(it) }
        return out
    }

    private suspend fun geminiModels(ctx: Context, key: String, forceDiscover: Boolean): List<String> {
        val setting = Settings.get(ctx, Settings.GEMINI_MODEL, Settings.DEFAULT_GEMINI_MODEL)
        if (setting.isNotBlank() && !setting.equals("auto", true)) return listOf(setting)
        val cached = Settings.get(ctx, Settings.GEMINI_RESOLVED, "").split(",").filter { it.isNotBlank() }
        if (cached.isNotEmpty() && !forceDiscover) return cached
        val ranked = withContext(Dispatchers.IO) {
            try { ModelPicker.rankGemini(listGemini(key)) } catch (e: Exception) { emptyList<String>() }
        }.let { r ->
            // os 4 melhores + um "lite" (cota separada) como reserva
            val top = r.filter { !it.contains("lite") }.take(4)
            top + r.filter { it.contains("lite") }.take(1)
        }
        if (ranked.isNotEmpty()) Settings.put(ctx, Settings.GEMINI_RESOLVED, ranked.joinToString(","))
        return ranked.ifEmpty { cached.ifEmpty { listOf("gemini-2.5-flash") } }
    }

    private suspend fun compatModel(ctx: Context, base: String, key: String, forceDiscover: Boolean): String {
        val setting = Settings.get(ctx, Settings.COMPAT_MODEL, Settings.DEFAULT_COMPAT_MODEL)
        if (setting.isNotBlank() && !setting.equals("auto", true) && !forceDiscover) return setting
        val cached = Settings.get(ctx, Settings.COMPAT_RESOLVED, "")
        if (cached.isNotBlank() && !forceDiscover) return cached
        val picked = withContext(Dispatchers.IO) {
            try { ModelPicker.pickCompat(listCompat(base, key)) } catch (e: Exception) { null }
        }
        if (picked != null) Settings.put(ctx, Settings.COMPAT_RESOLVED, picked)
        return picked ?: cached.ifBlank { "llama-3.3-70b-versatile" }
    }

    // ---------------------------------------------------------------- provedores

    private suspend fun gemini(ctx: Context, prompt: String, jpeg: ByteArray?): Reply = withContext(Dispatchers.IO) {
        val key = Settings.get(ctx, Settings.GEMINI_KEY, "")
        if (key.isBlank()) return@withContext Reply(false, null, "chave do Gemini não configurada")
        var models = geminiModels(ctx, key, false).filter { !blockedToday(ctx, it) }
        if (models.isEmpty()) return@withContext Reply(false, null, "cota diária do Gemini esgotada em todos os modelos (volta amanhã)")
        var rediscovered = false
        var waited = false
        var withThinking = true
        var lastErr = "falha desconhecida"
        var idx = 0
        val capErr = throttleGemini(ctx)
        if (capErr != null) return@withContext Reply(false, null, capErr)
        while (idx < models.size) {
            val model = models[idx]
            Diag.aiCalls.incrementAndGet()
            AiStatus.set("Gemini ($model) — modelo ${idx + 1} de ${models.size}")
            val parts = JSONArray().put(JSONObject().put("text", prompt))
            if (jpeg != null) {
                parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))))
            }
            val gen = JSONObject().put("temperature", 0.1).put("responseMimeType", "application/json").put("maxOutputTokens", 2048)
            if (withThinking) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts))).put("generationConfig", gen).toString()
            try {
                val r = request("POST", "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", mapOf("x-goog-api-key" to key), body)
                if (r.code in 200..299) {
                    val t = JSONObject(r.body).optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
                    if (t.isNullOrBlank()) return@withContext Reply(false, null, "resposta vazia")
                    // Este modelo respondeu: na próxima chamada ele é o primeiro a ser tentado.
                    if (idx > 0) Settings.put(ctx, Settings.GEMINI_RESOLVED, ModelPicker.learnedOrder(models, listOf(model)).joinToString(","))
                    return@withContext Reply(true, t, null)
                }
                lastErr = "$model: HTTP ${r.code} " + short(r.body)
                if (r.code == 429) {
                    if (dailyQuota(r.body)) {
                        blockForToday(ctx, model)
                        lastErr = "$model: cota diária esgotada"
                    } else {
                        slowUntil = System.currentTimeMillis() + 600000L
                        val wait = retryDelayMs(r.body)
                        if (!waited && wait != null && wait <= 20000L) {
                            waited = true
                            AiStatus.set("Gemini ($model): limite por minuto, aguardando ${wait / 1000}s…")
                            delay(wait + 500L)
                            continue
                        }
                    }
                }
                if (r.code == 400 && withThinking && r.body.contains("thinking", true)) {
                    withThinking = false
                    continue
                }
                if ((r.code == 404 || r.code == 400) && !rediscovered && r.body.contains("model", true)) {
                    rediscovered = true
                    models = geminiModels(ctx, key, true).filter { !blockedToday(ctx, it) }
                    idx = 0
                    continue
                }
                if (r.code != 429 && r.code != 503 && r.code != 500) break
            } catch (e: SocketTimeoutException) {
                lastErr = "$model: tempo esgotado (${READ_MS / 1000}s)"
            } catch (e: Exception) {
                lastErr = "$model: " + (e.message ?: e.javaClass.simpleName)
            }
            idx++
        }
        Reply(false, null, lastErr)
    }

    private suspend fun claude(ctx: Context, prompt: String, jpeg: ByteArray?): Reply = withContext(Dispatchers.IO) {
        val key = Settings.get(ctx, Settings.CLAUDE_KEY, "")
        if (key.isBlank()) return@withContext Reply(false, null, "chave do Claude não configurada")
        val model = Settings.get(ctx, Settings.CLAUDE_MODEL, Settings.DEFAULT_CLAUDE_MODEL).ifBlank { Settings.DEFAULT_CLAUDE_MODEL }
        AiStatus.set("Claude ($model)")
        Diag.aiCalls.incrementAndGet()
        val content = JSONArray()
        if (jpeg != null) {
            content.put(
                JSONObject().put("type", "image").put(
                    "source",
                    JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                )
            )
        }
        content.put(JSONObject().put("type", "text").put("text", prompt))
        val body = JSONObject().put("model", model).put("max_tokens", 2048)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content))).toString()
        try {
            val r = request("POST", "https://api.anthropic.com/v1/messages", mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), body)
            if (r.code in 200..299) {
                val t = JSONObject(r.body).optJSONArray("content")?.optJSONObject(0)?.optString("text")
                if (t.isNullOrBlank()) Reply(false, null, "resposta vazia") else Reply(true, t, null)
            } else Reply(false, null, "HTTP ${r.code}: " + short(r.body))
        } catch (e: SocketTimeoutException) {
            Reply(false, null, "tempo esgotado (${READ_MS / 1000}s)")
        } catch (e: Exception) {
            Reply(false, null, e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun compatVisionModel(ctx: Context, base: String, key: String): String? {
        val cached = Settings.get(ctx, Settings.COMPAT_VISION, "")
        // "none@instante": sem modelo com visão na última consulta; tenta de novo depois de 12 h
        if (cached.startsWith("none")) {
            val at = cached.substringAfter("@", "0").toLongOrNull() ?: 0L
            if (System.currentTimeMillis() - at < 12L * 3600000L) return null
        } else if (cached.isNotBlank()) return cached
        val picked = withContext(Dispatchers.IO) {
            try { ModelPicker.pickCompatVision(listCompat(base, key)) } catch (e: Exception) { null }
        }
        Settings.put(ctx, Settings.COMPAT_VISION, picked ?: ("none@" + System.currentTimeMillis()))
        return picked
    }

    private suspend fun compat(ctx: Context, prompt: String, jpeg: ByteArray?): Reply = withContext(Dispatchers.IO) {
        val key = Settings.get(ctx, Settings.COMPAT_KEY, "")
        if (key.isBlank()) return@withContext Reply(false, null, "chave alternativa não configurada")
        val base = Settings.get(ctx, Settings.COMPAT_BASE, Settings.DEFAULT_COMPAT_BASE).trimEnd('/')
        var model: String
        if (jpeg != null) {
            model = compatVisionModel(ctx, base, key) ?: return@withContext Reply(false, null, "este provedor não lista modelo com visão")
        } else {
            model = compatModel(ctx, base, key, false)
        }
        var rediscovered = false
        var lastErr = "falha desconhecida"
        for (attempt in 1..2) {
            AiStatus.set("Provedor alternativo ($model)" + (if (jpeg != null) " com imagem" else ""))
            val messages = JSONArray()
            if (jpeg != null) {
                val content = JSONArray()
                content.put(JSONObject().put("type", "text").put("text", prompt))
                content.put(
                    JSONObject().put("type", "image_url").put(
                        "image_url", JSONObject().put("url", "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP))
                    )
                )
                messages.put(JSONObject().put("role", "user").put("content", content))
            } else {
                messages.put(JSONObject().put("role", "user").put("content", prompt))
            }
            val req = JSONObject().put("model", model).put("temperature", 0.2).put("messages", messages)
            if (jpeg == null) req.put("response_format", JSONObject().put("type", "json_object"))
            try {
                val r = request("POST", "$base/chat/completions", mapOf("Authorization" to "Bearer $key"), req.toString())
                if (r.code in 200..299) {
                    val t = JSONObject(r.body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                    return@withContext if (t.isNullOrBlank()) Reply(false, null, "resposta vazia") else Reply(true, t, null)
                }
                lastErr = "HTTP ${r.code}: " + short(r.body)
                if ((r.code == 404 || r.code == 400) && !rediscovered && jpeg == null) {
                    rediscovered = true
                    model = compatModel(ctx, base, key, true)
                    continue
                }
                if (r.code != 429 && r.code != 503) break
            } catch (e: SocketTimeoutException) {
                lastErr = "tempo esgotado (${READ_MS / 1000}s)"
            } catch (e: Exception) {
                lastErr = e.message ?: e.javaClass.simpleName
            }
            if (attempt == 1) delay(3000L)
        }
        Reply(false, null, lastErr)
    }

    private fun learnPrefs(ctx: Context) = ctx.getSharedPreferences("ai_learn", Context.MODE_PRIVATE)

    private fun winners(ctx: Context, hasImage: Boolean): List<String> =
        (learnPrefs(ctx).getString(if (hasImage) "win_img" else "win_txt", "") ?: "").split(",").filter { it.isNotBlank() }

    /** Quem respondeu sobe para o topo; quem falhou sai da lista de vencedores (imagem e texto aprendem separado). */
    private fun learn(ctx: Context, hasImage: Boolean, provider: String, ok: Boolean) {
        val cur = winners(ctx, hasImage).filter { it != provider }
        val next = if (ok) listOf(provider) + cur else cur
        learnPrefs(ctx).edit().putString(if (hasImage) "win_img" else "win_txt", next.joinToString(",")).apply()
    }

    /**
     * Ordem: o último provedor que respondeu vem primeiro (aprendido), depois o preferido nos Ajustes e o resto.
     * Só entram os que têm chave.
     */
    fun providerOrder(ctx: Context, hasImage: Boolean): List<String> {
        val have = ArrayList<String>()
        if (Settings.get(ctx, Settings.GEMINI_KEY, "").isNotBlank()) have.add("gemini")
        if (Settings.get(ctx, Settings.CLAUDE_KEY, "").isNotBlank()) have.add("claude")
        if (Settings.get(ctx, Settings.COMPAT_KEY, "").isNotBlank()) have.add("compat")
        val pref = Settings.get(ctx, Settings.PREFERRED, "gemini")
        val base = if (pref in have) listOf(pref) + have.filter { it != pref } else have
        val ordered = ModelPicker.learnedOrder(base, winners(ctx, hasImage))
        // quem acabou de falhar vai para o fim da fila (evita esperar de novo um provedor sobrecarregado)
        val now = System.currentTimeMillis()
        return ordered.filter { (cooldownUntil[it] ?: 0L) <= now } + ordered.filter { (cooldownUntil[it] ?: 0L) > now }
    }

    suspend fun askProvider(ctx: Context, provider: String, prompt: String, jpeg: ByteArray?): Reply = when (provider) {
        "gemini" -> gemini(ctx, prompt, jpeg)
        "claude" -> claude(ctx, prompt, jpeg)
        else -> compat(ctx, prompt, jpeg)
    }

    private fun label(p: String): String = when (p) {
        "gemini" -> "Gemini"
        "claude" -> "Claude"
        else -> "Alternativo"
    }

    /** Tenta cada provedor com chave, em ordem, com orçamento total de tempo. Nunca fica preso. */
    suspend fun ask(ctx: Context, prompt: String, jpeg: ByteArray?, budgetMs: Long = 150000L): Reply {
        val deadline = System.currentTimeMillis() + budgetMs
        val order = providerOrder(ctx, jpeg != null)
        if (order.isEmpty()) return Reply(false, null, "Nenhuma chave de IA configurada. Abra Ajustes e cole a chave do Google AI Studio.")
        val errors = ArrayList<String>()
        for (p in order) {
            if (System.currentTimeMillis() > deadline) {
                errors.add("tempo total esgotado")
                break
            }
            val started = System.currentTimeMillis()
            val r = askProvider(ctx, p, prompt, jpeg)
            if (r.ok) {
                cooldownUntil.remove(p)
                learn(ctx, jpeg != null, p, true)
                AiStatus.set("")
                return r
            }
            learn(ctx, jpeg != null, p, false)
            cool(p, 180000L)
            errors.add(label(p) + " (" + ((System.currentTimeMillis() - started) / 1000) + "s): " + (r.error ?: "erro"))
        }
        AiStatus.set("")
        return Reply(false, null, errors.joinToString(" | "))
    }

    /** Teste rápido de cada provedor configurado: mostra se responde e quanto tempo leva. */
    suspend fun test(ctx: Context): String {
        val order = providerOrder(ctx, false)
        if (order.isEmpty()) return "Nenhuma chave configurada."
        val lines = ArrayList<String>()
        for (p in order) {
            val t0 = System.currentTimeMillis()
            val r = askProvider(ctx, p, "Responda somente com o JSON {\"ok\": true}", null)
            val secs = (System.currentTimeMillis() - t0) / 100 / 10.0
            val model = when (p) {
                "gemini" -> Settings.get(ctx, Settings.GEMINI_RESOLVED, "").ifBlank { Settings.get(ctx, Settings.GEMINI_MODEL, "auto") }
                "claude" -> Settings.get(ctx, Settings.CLAUDE_MODEL, Settings.DEFAULT_CLAUDE_MODEL)
                else -> Settings.get(ctx, Settings.COMPAT_RESOLVED, "").ifBlank { Settings.get(ctx, Settings.COMPAT_MODEL, "auto") }
            }
            lines.add(if (r.ok) "✔ ${label(p)} ($model): respondeu em ${secs}s" else "✘ ${label(p)} ($model): ${r.error}")
        }
        AiStatus.set("")
        return lines.joinToString("\n")
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
        "formation (ex.: \"4-4-2\" ou \"4-4-2 B\"), playStyle (estilo de jogo), marking (\"À zona\" ou \"Individual\"), " +
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
        Osm.style(clean(j.optString("playStyle")))?.let { out[K.RIVAL_PLAN] = Reading(it, c) }
        Osm.marking(clean(j.optString("marking")))?.let { out[K.RIVAL_MARKING] = Reading(it, c) }
        yesNo(j.optString("offside"))?.let { out[K.RIVAL_OFFSIDE] = Reading(it, c) }
        Osm.tackle(clean(j.optString("tackle")))?.let { out[K.RIVAL_TACKLE] = Reading(it, c) }
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

/** Roda ao encerrar a captura (e sob demanda): reprocessa quadros sem slot e consulta a IA só para o que o OCR local não resolveu. */
object Processor {
    data class ReadResult(val state: String, val changed: Int, val note: String)

    private suspend fun readScreen(ctx: Context, repo: Repo, s: ScreenEntity): ReadResult {
        val hasVision = Settings.get(ctx, Settings.GEMINI_KEY, "").isNotBlank() || Settings.get(ctx, Settings.CLAUDE_KEY, "").isNotBlank() ||
            Settings.get(ctx, Settings.COMPAT_KEY, "").isNotBlank()
        if (!hasVision) return ReadResult("skipped", 0, "sem chave de IA com leitura de imagem (Gemini, Claude ou Groq)")
        if (s.slotId == 0) return ReadResult("pending", 0, "aguardando identificação de slot")
        val path = s.imagePath
        val bytes = if (path != null) File(path).takeIf { it.exists() }?.readBytes() else null
        if (bytes == null) return ReadResult("failed", 0, "imagem ausente")
        val type = runCatching { ScreenType.valueOf(s.type) }.getOrDefault(ScreenType.OTHER_OSM)
        val prompt = if (type == ScreenType.SQUAD) AiPrompts.squad() else AiPrompts.report()
        val reply = AiClient.ask(ctx, prompt, bytes, 100000L)
        val json = AiClient.parseJson(reply.text)
        if (!reply.ok || json == null) {
            val err = reply.error ?: "IA devolveu JSON inválido"
            Diag.lastError = err
            return ReadResult("failed", 0, err.take(200))
        }
        val now = System.currentTimeMillis()
        if (type != ScreenType.SQUAD && AiMapper.reportKind(json) != "rival_report") {
            return ReadResult("done", 0, "IA: não é o relatório do rival (" + AiMapper.reportKind(json).ifBlank { "?" } + ")")
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
        return ReadResult("done", changed, "IA aplicou $changed campos")
    }

    suspend fun run(ctx: Context, sessionId: String) {
        val repo = Repo(ctx)
        val dao = repo.dao
        val unassignedN = dao.unassignedWithImage(40).size
        val pending = dao.pendingAi(8)
        ProcessState.begin("Reprocessando telas sem slot…", unassignedN + pending.size)
        val fixed = FramePipeline.get(ctx).reprocessUnassigned(40) { ProcessState.tick() }
        var applied = 0
        var failed = 0
        var skipped = 0
        var noData = 0
        for ((i, s) in pending.withIndex()) {
            ProcessState.label("Lendo tela ${i + 1} de ${pending.size} com IA…")
            val r = readScreen(ctx, repo, s)
            dao.updateScreenAi(s.id, r.state, s.extracted + r.changed, r.note)
            when {
                r.state == "failed" -> failed++
                r.state == "skipped" -> skipped++
                r.changed > 0 -> applied += r.changed
                else -> noData++
            }
            ProcessState.tick()
        }
        val since = dao.session(sessionId)?.startedAt
        val gone = if (since != null) {
            try { repo.reconcileSquads(since) } catch (e: Exception) { 0 }
        } else 0
        ProcessState.finish(
            "Telas reprocessadas: $fixed • IA: $applied campos aplicados, $noData sem dados do rival, $failed falhas, $skipped ignoradas" +
                (if (gone > 0) " • $gone jogador(es) que não existem mais saíram do elenco" else "")
        )
    }

    /** Lê as últimas telas guardadas do slot até achar o relatório do rival. */
    suspend fun readLatest(ctx: Context, slot: Int) {
        val repo = Repo(ctx)
        val list = repo.dao.savedForSlot(slot, 6)
        if (list.isEmpty()) {
            ProcessState.begin("Procurando telas do rival…", 0)
            ProcessState.finish("Nenhuma tela guardada deste slot. No jogo, abra a análise/relatório do rival, deixe parada por 3 segundos e tente de novo.")
            return
        }
        ProcessState.begin("Lendo telas do rival com IA…", list.size)
        var applied = 0
        var tried = 0
        for ((i, s) in list.withIndex()) {
            ProcessState.label("Lendo tela ${i + 1} de ${list.size} com IA…")
            val r = readScreen(ctx, repo, s)
            repo.dao.updateScreenAi(s.id, r.state, s.extracted + r.changed, r.note)
            tried++
            applied += r.changed
            ProcessState.tick()
            if (r.changed > 0) break
        }
        ProcessState.finish(
            if (applied > 0) "IA aplicou $applied campo(s) do relatório do rival."
            else "Li $tried tela(s); nenhuma trazia dados novos do relatório do rival. Abra o relatório do rival no jogo e tente de novo."
        )
    }

    suspend fun readOne(ctx: Context, id: Long) {
        val repo = Repo(ctx)
        val s = repo.dao.screen(id) ?: return
        ProcessState.begin("Lendo tela com IA…", 1)
        val r = readScreen(ctx, repo, s)
        repo.dao.updateScreenAi(s.id, r.state, s.extracted + r.changed, r.note)
        ProcessState.tick()
        ProcessState.finish(r.note)
    }
}
