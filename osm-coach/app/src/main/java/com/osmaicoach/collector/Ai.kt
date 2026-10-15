package com.osmaicoach.collector

import android.content.Context
import android.util.Base64
import java.io.File
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        // IA principal: o antigo padrão "gemini" vira "automático" (a ordem passa a seguir o teste de cada IA)
        if (get(ctx, "settings_v4", "") != "1") {
            if (get(ctx, PREFERRED, "gemini") == "gemini") put(ctx, PREFERRED, "auto")
            put(ctx, COMPAT_VISION, "")
            put(ctx, "settings_v4", "1")
        }
        if (get(ctx, "settings_v3", "") == "1") return
        if (get(ctx, "gemini_model", "") == "gemini-flash-latest") put(ctx, "gemini_model", "auto")
        if (get(ctx, "compat_model", "") == "llama-3.3-70b-versatile") put(ctx, "compat_model", "auto")
        put(ctx, "gemini_model_resolved", "")
        put(ctx, "compat_model_resolved", "")
        put(ctx, "compat_vision_resolved", "")
        put(ctx, "settings_v3", "1")
    }

    /**
     * Uma ou mais chaves do Gemini (separadas por vírgula, espaço ou linha). Cada chave de um PROJETO diferente do
     * Google tem a sua própria cota gratuita diária: quando uma esgota, o app passa para a próxima.
     */
    fun geminiKeys(ctx: Context): List<String> = splitKeys(get(ctx, GEMINI_KEY, ""))

    fun splitKeys(raw: String): List<String> = raw.split(Regex("[,;\\s]+")).map { it.trim() }.filter { it.length >= 20 }.distinct()

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
    const val MISTRAL_KEY = "mistral_key"
    const val MISTRAL_BASE = "https://api.mistral.ai/v1"
    const val OPENROUTER_KEY = "openrouter_key"
    const val OPENROUTER_BASE = "https://openrouter.ai/api/v1"
    /** "auto" = ordem pelo último teste e por quem respondeu por último; ou o id do provedor escolhido. */
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
    /** Modelo listado pelo provedor: visão e gratuidade quando a API informa (OpenRouter e Mistral informam). */
    data class ModelInfo(val id: String, val vision: Boolean? = null, val free: Boolean? = null)

    fun parseModels(body: String): List<ModelInfo> {
        val arr = JSONObject(body).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<ModelInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            var vision: Boolean? = null
            val mods = o.optJSONObject("architecture")?.optJSONArray("input_modalities")
            if (mods != null) vision = (0 until mods.length()).any { mods.optString(it) == "image" }
            val caps = o.optJSONObject("capabilities")
            if (caps != null && caps.has("vision")) vision = caps.optBoolean("vision")
            var free: Boolean? = null
            val price = o.optJSONObject("pricing")
            if (price != null) free = (price.optString("prompt").toDoubleOrNull() ?: 1.0) == 0.0 && (price.optString("completion").toDoubleOrNull() ?: 1.0) == 0.0
            if (id.endsWith(":free")) free = true
            out.add(ModelInfo(id, vision, free))
        }
        return out
    }

    /** No OpenRouter só valem os modelos gratuitos (a chave sem crédito não paga os outros). */
    private fun usable(models: List<ModelInfo>, base: String): List<ModelInfo> =
        if (base.contains("openrouter")) models.filter { it.free == true } else models

    /** Modelos com visão a tentar, do melhor para o pior: marcados pela API, de nome conhecido e os conhecidos do provedor. */
    fun compatVisionCandidates(models: List<ModelInfo>, base: String): List<String> {
        val use = usable(models, base)
        val flagged = use.filter { it.vision == true }.map { it.id }
        val unknown = use.filter { it.vision == null }.map { it.id }
        val ids = models.map { it.id }.toSet()
        val known = knownVision(base).filter { models.isEmpty() || it in ids }
        return (rankCompatVision(flagged) + flagged + rankCompatVision(unknown) + known).distinct()
    }

    fun pickCompatText(models: List<ModelInfo>, base: String): String? = pickCompat(usable(models, base).map { it.id })

    fun defaultText(base: String): String = when {
        base.contains("mistral.ai") -> "mistral-small-latest"
        base.contains("openrouter") -> "meta-llama/llama-3.3-70b-instruct:free"
        else -> "openai/gpt-oss-120b"
    }

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
    fun pickCompatVision(ids: List<String>): String? = rankCompatVision(ids).firstOrNull()

    /** Todos os modelos com visão que o provedor lista, do melhor para o pior. */
    fun rankCompatVision(ids: List<String>): List<String> {
        val prefs = listOf(
            "llama-4-maverick", "llama-4-scout", "llama-4", "gpt-4.1", "gpt-4o", "gpt-5", "grok-4", "vision", "pixtral",
            "mistral-medium", "mistral-small", "-vl", "qwen3.9", "qwen3.8", "qwen3.7", "qwen3.6", "qwen3.5", "gemma-4", "gemma-3", "llava"
        )
        val bad = listOf("guard", "embed", "tts", "whisper", "audio", "image-gen", "dall-e", "realtime", "transcribe")
        val ok = ids.filter { id -> bad.none { id.contains(it, ignoreCase = true) } }
        val out = ArrayList<String>()
        for (p in prefs) for (id in ok) if (id.contains(p, ignoreCase = true) && id !in out) out.add(id)
        return out
    }

    /**
     * Modelos com visão conhecidos de cada provedor compatível, para quando a lista de modelos falha ou vem
     * sem nenhum nome reconhecido (o app testa um a um e guarda o que responder).
     */
    fun knownVision(base: String): List<String> {
        val b = base.lowercase()
        return when {
            // o Groq tirou o Llama 4 do plano gratuito (jun/2026); o Qwen 3.x de 27B lê imagem
            b.contains("groq.com") -> listOf("qwen/qwen3.6-27b", "qwen/qwen3.8-27b", "meta-llama/llama-4-scout-17b-16e-instruct", "meta-llama/llama-4-maverick-17b-128e-instruct")
            b.contains("mistral.ai") -> listOf("mistral-small-latest", "mistral-medium-latest", "pixtral-12b-2409")
            b.contains("x.ai") -> listOf("grok-4", "grok-2-vision-1212", "grok-2-vision-latest")
            b.contains("openrouter") -> listOf("google/gemma-3-27b-it:free", "meta-llama/llama-4-maverick:free", "qwen/qwen2.5-vl-72b-instruct:free", "mistralai/mistral-small-3.2-24b-instruct:free")
            b.contains("openai.com") -> listOf("gpt-4.1-mini", "gpt-4o-mini")
            else -> emptyList()
        }
    }

    /** Modelos do plano gratuito do Gemini (cada um com cota própria): entram como reserva dos descobertos. */
    val GEMINI_FALLBACK = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.0-flash", "gemini-2.0-flash-lite")

    /** Descobertos (até 4 + 1 lite) seguidos das reservas do plano gratuito, sem repetir. */
    fun geminiCandidates(discovered: List<String>): List<String> {
        val ranked = rankGemini(discovered)
        val top = ranked.filter { !it.contains("lite") }.take(4) + ranked.filter { it.contains("lite") }.take(1)
        return (top + GEMINI_FALLBACK).distinct()
    }

    /** Escolhe um modelo de texto grande entre os que o provedor (Groq/xAI/OpenAI-compatível) lista. */
    fun pickCompat(ids: List<String>): String? {
        val bad = listOf("whisper", "tts", "guard", "embed", "playai", "orpheus", "distil", "moderation", "image", "ocr", "codestral", "devstral", "voxtral", "saba")
        val ok = ids.filter { id -> bad.none { id.contains(it, ignoreCase = true) } }
        // modelo que "pensa" antes (melhor para tática) primeiro; os demais como reserva
        val prefs = listOf(
            "gpt-oss-120b", "qwen3", "llama-3.3-70b", "llama-3.1-70b", "gpt-oss-20b", "deepseek", "qwen", "grok",
            "mistral-medium-latest", "mistral-small-latest", "mistral-large-latest", "mistral-medium", "mistral-small", "gemma-3-27b", "llama-3.1-8b", "llama"
        )
        for (p in prefs) {
            val hit = ok.firstOrNull { it.contains(p, ignoreCase = true) }
            if (hit != null) return hit
        }
        return ok.firstOrNull()
    }
}

object AiClient {
    data class Reply(val ok: Boolean, val text: String?, val error: String?, val by: String? = null)

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

    // ---------------------------------------------------------------- cotas do Gemini por chave e modelo

    private fun blockPrefs(ctx: Context) = ctx.getSharedPreferences("ai_block", Context.MODE_PRIVATE)

    /** Rótulo curto e não secreto da chave (só os 4 últimos caracteres), para a tela e o diagnóstico. */
    fun keyLabel(key: String): String = "…" + key.takeLast(4)

    private fun keyHash(key: String): String = Integer.toHexString(key.hashCode())

    private val PACIFIC: java.util.TimeZone = java.util.TimeZone.getTimeZone("America/Los_Angeles")

    /** A cota gratuita do Gemini renova à meia-noite do horário do Pacífico (não à meia-noite daqui). */
    fun quotaDay(now: Long = System.currentTimeMillis()): String {
        val f = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        f.timeZone = PACIFIC
        return f.format(java.util.Date(now))
    }

    /** Próxima renovação da cota (meia-noite do Pacífico). */
    fun quotaResetAt(now: Long = System.currentTimeMillis()): Long {
        val c = java.util.Calendar.getInstance(PACIFIC)
        c.timeInMillis = now
        c.add(java.util.Calendar.DAY_OF_MONTH, 1)
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun resetText(): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(quotaResetAt()))

    /** Chave+modelo com a cota DIÁRIA esgotada (ou chave recusada, ou modelo que não existe): não é tentado de novo. */
    private fun blockedToday(ctx: Context, key: String, model: String): Boolean {
        val p = blockPrefs(ctx)
        val day = quotaDay()
        if (p.getString(keyHash(key) + "|" + model, "") == day) return true
        if (p.getString(keyHash(key) + "|*", "") == day) return true
        return gone(ctx, model)
    }

    private fun gone(ctx: Context, model: String): Boolean =
        (blockPrefs(ctx).getString("gone|$model", "")?.toLongOrNull() ?: 0L) > System.currentTimeMillis()

    private fun blockForToday(ctx: Context, key: String, model: String) {
        blockPrefs(ctx).edit().putString(keyHash(key) + "|" + model, quotaDay()).apply()
    }

    /** Chave recusada pelo Google (inválida, sem permissão, projeto sem a API): fora até a cota renovar. */
    private fun blockKeyForToday(ctx: Context, key: String) {
        blockPrefs(ctx).edit().putString(keyHash(key) + "|*", quotaDay()).apply()
    }

    /** Modelo que não existe mais (404): fora da lista por 7 dias. */
    private fun markGone(ctx: Context, model: String) {
        blockPrefs(ctx).edit().putString("gone|$model", (System.currentTimeMillis() + 7L * 86400000L).toString()).apply()
    }

    /** Modelo sobrecarregado (503) ou lento: vai para o fim da fila por alguns minutos. */
    private val busyUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

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

    /** Plano gratuito do Gemini: respeita intervalo mínimo e teto diário (o teto vale por chave). */
    private suspend fun throttleGemini(ctx: Context, keys: Int): String? = gate.withLock {
        val p = ctx.getSharedPreferences("ai_usage", Context.MODE_PRIVATE)
        val used = if (p.getString("day", "") == today()) p.getInt("count", 0) else 0
        val cap = (Settings.get(ctx, Settings.DAILY_CAP, "80").toIntOrNull() ?: 80) * keys.coerceAtLeast(1)
        if (used >= cap) return@withLock "teto diário de $cap chamadas ao Gemini atingido (Ajustes → Teto diário)"
        val gap = if (System.currentTimeMillis() < slowUntil) 7000L else 1200L
        val wait = gap - (System.currentTimeMillis() - lastGeminiAt)
        if (wait > 0) delay(wait)
        lastGeminiAt = System.currentTimeMillis()
        p.edit().putString("day", today()).putInt("count", used + 1).apply()
        null
    }

    /** Modelos que serão tentados, sem consultar a rede (para saber se ainda há cota hoje). */
    private fun cachedGeminiModels(ctx: Context): List<String> {
        val setting = Settings.get(ctx, Settings.GEMINI_MODEL, Settings.DEFAULT_GEMINI_MODEL)
        val fixed = if (setting.isNotBlank() && !setting.equals("auto", true)) listOf(setting) else emptyList()
        val cached = Settings.get(ctx, Settings.GEMINI_RESOLVED, "").split(",").filter { it.isNotBlank() }
        return (fixed + cached + ModelPicker.GEMINI_FALLBACK).distinct()
    }

    /** Ainda há alguma chave+modelo do Gemini com cota hoje? (sem gastar chamada) */
    fun geminiAvailable(ctx: Context): Boolean {
        val keys = Settings.geminiKeys(ctx)
        if (keys.isEmpty()) return false
        return cachedGeminiModels(ctx).any { m -> keys.any { k -> !blockedToday(ctx, k, m) } }
    }

    private fun exhaustedMsg(ctx: Context): String {
        val n = Settings.geminiKeys(ctx).size
        return "cota gratuita de hoje esgotada em todos os modelos d" + (if (n > 1) "as $n chaves" else "a chave") +
            " (renova às ${resetText()}). Para continuar hoje, cole em Ajustes a chave de OUTRO projeto do Google AI Studio " +
            "(separada por vírgula) ou uma chave do Groq."
    }

    /** Situação do Gemini para a tela de Ajustes. */
    fun geminiStatus(ctx: Context): String {
        val keys = Settings.geminiKeys(ctx)
        if (keys.isEmpty()) return "sem chave"
        val models = cachedGeminiModels(ctx)
        val pairs = models.size * keys.size
        val free = models.sumOf { m -> keys.count { k -> !blockedToday(ctx, k, m) } }
        val busy = models.filter { (busyUntil[it] ?: 0L) > System.currentTimeMillis() }
        return "${keys.size} chave(s) (${keys.joinToString { keyLabel(it) }}) • $free de $pairs combinações chave/modelo com cota hoje" +
            (if (free < pairs) " • a cota renova às ${resetText()}" else "") +
            (if (busy.isNotEmpty()) " • sobrecarregado agora: ${busy.joinToString()}" else "")
    }

    /** Linhas para o diagnóstico: bloqueios gravados (sem as chaves). */
    fun blockDump(ctx: Context): JSONObject {
        val out = JSONObject()
        for ((k, v) in blockPrefs(ctx).all) out.put(k, v.toString())
        out.put("quotaDay", quotaDay())
        out.put("resetAt", quotaResetAt())
        return out
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

    private fun listCompat(base: String, key: String): List<ModelPicker.ModelInfo> {
        val r = request("GET", "$base/models", mapOf("Authorization" to "Bearer $key"), null)
        if (r.code !in 200..299) return emptyList()
        return ModelPicker.parseModels(r.body)
    }

    /** Modelo fixo dos Ajustes (se houver) + descobertos pela chave + reservas do plano gratuito. */
    private suspend fun geminiModels(ctx: Context, key: String, forceDiscover: Boolean): List<String> {
        val setting = Settings.get(ctx, Settings.GEMINI_MODEL, Settings.DEFAULT_GEMINI_MODEL)
        val fixed = if (setting.isNotBlank() && !setting.equals("auto", true)) listOf(setting) else emptyList()
        val cached = Settings.get(ctx, Settings.GEMINI_RESOLVED, "").split(",").filter { it.isNotBlank() }
        val found = if (cached.isEmpty() || forceDiscover) {
            val names = withContext(Dispatchers.IO) { try { listGemini(key) } catch (e: Exception) { emptyList<String>() } }
            if (names.isNotEmpty()) ModelPicker.geminiCandidates(names).also { Settings.put(ctx, Settings.GEMINI_RESOLVED, it.joinToString(",")) }
            else cached
        } else cached
        return (fixed + found + ModelPicker.GEMINI_FALLBACK).distinct()
    }

    /** Pares chave+modelo com cota: o melhor modelo em todas as chaves primeiro; sobrecarregados no fim. */
    private fun geminiPairs(ctx: Context, keys: List<String>, models: List<String>): List<Pair<String, String>> {
        val now = System.currentTimeMillis()
        val pairs = ArrayList<Pair<String, String>>()
        for (m in models) for (k in keys) if (!blockedToday(ctx, k, m)) pairs.add(Pair(k, m))
        return pairs.filter { (busyUntil[it.second] ?: 0L) <= now } + pairs.filter { (busyUntil[it.second] ?: 0L) > now }
    }

    /** Modelo de texto do provedor: o fixo dos Ajustes (só Groq), o já descoberto ou o melhor que a chave lista. */
    private suspend fun compatModel(ctx: Context, p: Compat, base: String, key: String, forceDiscover: Boolean): String {
        val setting = p.modelPref?.let { Settings.get(ctx, it, Settings.DEFAULT_COMPAT_MODEL) } ?: "auto"
        if (setting.isNotBlank() && !setting.equals("auto", true) && !forceDiscover) return setting
        val cached = Settings.get(ctx, p.resolvedPref, "")
        if (cached.isNotBlank() && !forceDiscover) return cached
        val picked = ModelPicker.pickCompatText(compatModels(base, key, forceDiscover), base)
        if (picked != null) Settings.put(ctx, p.resolvedPref, picked)
        return picked ?: cached.ifBlank { ModelPicker.defaultText(base) }
    }

    // ---------------------------------------------------------------- provedores

    private suspend fun gemini(ctx: Context, prompt: String, jpeg: ByteArray?, deadline: Long): Reply = withContext(Dispatchers.IO) {
        val keys = Settings.geminiKeys(ctx)
        if (keys.isEmpty()) return@withContext Reply(false, null, "chave do Gemini não configurada")
        var models = geminiModels(ctx, keys.first(), false)
        var pairs = geminiPairs(ctx, keys, models)
        if (pairs.isEmpty()) return@withContext Reply(false, null, exhaustedMsg(ctx))
        val capErr = throttleGemini(ctx, keys.size)
        if (capErr != null) return@withContext Reply(false, null, capErr)
        var rediscovered = false
        var waited = false
        var withThinking = true
        var lastErr = "falha desconhecida"
        var idx = 0
        var attempts = 0
        while (idx < pairs.size && attempts < 12) {
            if (System.currentTimeMillis() > deadline) {
                lastErr = "tempo total esgotado ($lastErr)"
                break
            }
            val (key, model) = pairs[idx]
            // a chave pode ter sido recusada numa tentativa anterior desta mesma chamada
            if (blockedToday(ctx, key, model)) {
                idx++
                continue
            }
            attempts++
            Diag.aiCalls.incrementAndGet()
            val tag = if (keys.size > 1) "$model, chave ${keyLabel(key)}" else model
            AiStatus.set("Gemini ($tag) — tentativa ${idx + 1} de ${pairs.size}")
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
                    if (!t.isNullOrBlank()) {
                        busyUntil.remove(model)
                        // Este modelo respondeu: na próxima chamada ele é o primeiro a ser tentado.
                        // (a ordem guarda todos os modelos, inclusive os sem cota hoje: amanhã eles voltam)
                        if (models.firstOrNull() != model) Settings.put(ctx, Settings.GEMINI_RESOLVED, ModelPicker.learnedOrder(models, listOf(model)).joinToString(","))
                        return@withContext Reply(true, t, null)
                    }
                    lastErr = "$tag: resposta vazia"
                } else {
                    lastErr = "$tag: HTTP ${r.code} " + short(r.body)
                    val b = r.body.lowercase()
                    when {
                        r.code == 429 && dailyQuota(r.body) -> {
                            blockForToday(ctx, key, model)
                            lastErr = "$tag: cota diária esgotada"
                        }
                        r.code == 429 -> {
                            slowUntil = System.currentTimeMillis() + 600000L
                            val wait = retryDelayMs(r.body)
                            if (!waited && wait != null && wait <= 20000L && System.currentTimeMillis() + wait < deadline) {
                                waited = true
                                AiStatus.set("Gemini ($tag): limite por minuto, aguardando ${wait / 1000}s…")
                                delay(wait + 500L)
                                continue
                            }
                        }
                        // 400 "invalid argument": alguns modelos (ex. *-lite novos) recusam o thinkingConfig. Tenta sem ele.
                        r.code == 400 && withThinking && (b.contains("thinking") || b.contains("invalid argument")) -> {
                            withThinking = false
                            continue
                        }
                        r.code == 401 || r.code == 403 || b.contains("api_key_invalid") || b.contains("api key not valid") -> {
                            blockKeyForToday(ctx, key)
                            lastErr = "chave ${keyLabel(key)} recusada pelo Google (inválida ou sem permissão)"
                        }
                        r.code == 404 || (r.code == 400 && b.contains("model") && (b.contains("not found") || b.contains("not supported"))) -> {
                            markGone(ctx, model)
                            if (!rediscovered) {
                                rediscovered = true
                                models = geminiModels(ctx, keys.first(), true)
                                pairs = geminiPairs(ctx, keys, models)
                                idx = 0
                                continue
                            }
                        }
                        r.code >= 500 -> {
                            busyUntil[model] = System.currentTimeMillis() + 300000L
                            lastErr = "$tag: sobrecarregado no Google (HTTP ${r.code})"
                        }
                    }
                }
            } catch (e: SocketTimeoutException) {
                busyUntil[model] = System.currentTimeMillis() + 180000L
                lastErr = "$tag: tempo esgotado (${READ_MS / 1000}s)"
            } catch (e: Exception) {
                lastErr = "$tag: " + (e.message ?: e.javaClass.simpleName)
            }
            idx++
        }
        if (!geminiAvailable(ctx)) Reply(false, null, exhaustedMsg(ctx)) else Reply(false, null, lastErr)
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

    /**
     * Provedores no formato OpenAI (chat/completions): Groq (URL editável, serve também para xAI), Mistral e
     * OpenRouter. Cada um com a sua chave e os seus modelos descobertos.
     */
    class Compat(
        val id: String, val label: String, val keyPref: String, private val fixedBase: String?,
        val modelPref: String?, val resolvedPref: String, val visionPref: String
    ) {
        fun base(ctx: Context): String = (fixedBase ?: Settings.get(ctx, Settings.COMPAT_BASE, Settings.DEFAULT_COMPAT_BASE)).trim().trimEnd('/')
        fun key(ctx: Context): String = Settings.get(ctx, keyPref, "")
    }

    val COMPATS = listOf(
        Compat("compat", "Groq", Settings.COMPAT_KEY, null, Settings.COMPAT_MODEL, Settings.COMPAT_RESOLVED, Settings.COMPAT_VISION),
        Compat("mistral", "Mistral", Settings.MISTRAL_KEY, Settings.MISTRAL_BASE, null, "mistral_model_resolved", "mistral_vision_resolved"),
        Compat("openrouter", "OpenRouter", Settings.OPENROUTER_KEY, Settings.OPENROUTER_BASE, null, "openrouter_model_resolved", "openrouter_vision_resolved")
    )

    fun compatOf(id: String): Compat? = COMPATS.firstOrNull { it.id == id }

    /** Lista de modelos de cada provedor guardada por 12 h (evita uma consulta a cada imagem). */
    private val compatLists = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<ModelPicker.ModelInfo>>>()

    private suspend fun compatModels(base: String, key: String, force: Boolean = false): List<ModelPicker.ModelInfo> {
        val now = System.currentTimeMillis()
        val ck = base + "|" + key.takeLast(6)
        if (!force) compatLists[ck]?.takeIf { now - it.first < 12L * 3600000L }?.let { return it.second }
        val list = withContext(Dispatchers.IO) { try { listCompat(base, key) } catch (e: Exception) { emptyList() } }
        if (list.isNotEmpty()) compatLists[ck] = Pair(now, list)
        return list
    }

    /**
     * Modelos com visão a tentar: o que já respondeu antes, os marcados com visão pelo provedor, os de nome
     * conhecido e os conhecidos do provedor. Uma lista que falhou NÃO vira "sem visão".
     */
    private suspend fun compatVisionModels(ctx: Context, p: Compat, base: String, key: String): List<String> {
        val cached = Settings.get(ctx, p.visionPref, "").takeIf { it.isNotBlank() && !it.startsWith("none") }
        val listed = compatModels(base, key)
        return (listOfNotNull(cached) + ModelPicker.compatVisionCandidates(listed, base)).distinct().take(5)
    }

    private fun compatBody(model: String, prompt: String, jpeg: ByteArray?, base: String): String {
        val messages = JSONArray()
        if (jpeg != null) {
            val content = JSONArray()
            content.put(JSONObject().put("type", "text").put("text", prompt))
            val url = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
            // Mistral recebe a imagem como texto direto; os demais no formato da OpenAI ({"url": ...})
            val img: Any = if (base.contains("mistral.ai")) url else JSONObject().put("url", url)
            content.put(JSONObject().put("type", "image_url").put("image_url", img))
            messages.put(JSONObject().put("role", "user").put("content", content))
        } else {
            messages.put(JSONObject().put("role", "user").put("content", prompt))
        }
        val req = JSONObject().put("model", model).put("temperature", 0.2).put("messages", messages)
        // modelos gratuitos do OpenRouter nem sempre aceitam o modo JSON: o texto já pede só JSON
        if (jpeg == null && !base.contains("openrouter")) req.put("response_format", JSONObject().put("type", "json_object"))
        return req.toString()
    }

    private fun compatHeaders(base: String, key: String): Map<String, String> =
        if (base.contains("openrouter")) mapOf("Authorization" to "Bearer $key", "X-Title" to "OSM Coach")
        else mapOf("Authorization" to "Bearer $key")

    private suspend fun compat(ctx: Context, p: Compat, prompt: String, jpeg: ByteArray?, deadline: Long): Reply = withContext(Dispatchers.IO) {
        val key = p.key(ctx)
        if (key.isBlank()) return@withContext Reply(false, null, "chave do ${p.label} não configurada")
        val base = p.base(ctx)
        val headers = compatHeaders(base, key)
        if (jpeg != null) {
            val models = compatVisionModels(ctx, p, base, key)
            if (models.isEmpty()) {
                return@withContext Reply(false, null, "o ${p.label} não oferece hoje nenhum modelo que leia imagem para esta chave")
            }
            var lastErr = "falha desconhecida"
            for (model in models) {
                if (System.currentTimeMillis() > deadline) break
                AiStatus.set("${p.label} ($model) com imagem")
                Diag.aiCalls.incrementAndGet()
                try {
                    val r = request("POST", "$base/chat/completions", headers, compatBody(model, prompt, jpeg, base))
                    if (r.code in 200..299) {
                        val t = JSONObject(r.body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                        if (!t.isNullOrBlank()) {
                            Settings.put(ctx, p.visionPref, model)
                            return@withContext Reply(true, t, null)
                        }
                        lastErr = "$model: resposta vazia"
                    } else {
                        lastErr = "$model: HTTP ${r.code} " + short(r.body)
                        // modelo que não existe / não aceita imagem: esquece o guardado e tenta o próximo
                        if ((r.code == 404 || r.code == 400) && Settings.get(ctx, p.visionPref, "") == model) {
                            Settings.put(ctx, p.visionPref, "")
                        }
                        if (r.code == 401 || r.code == 403) {
                            lastErr = "chave do ${p.label} recusada (HTTP ${r.code})"
                            break
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    lastErr = "$model: tempo esgotado (${READ_MS / 1000}s)"
                } catch (e: Exception) {
                    lastErr = "$model: " + (e.message ?: e.javaClass.simpleName)
                }
            }
            return@withContext Reply(false, null, lastErr)
        }
        var model = compatModel(ctx, p, base, key, false)
        var rediscovered = false
        var lastErr = "falha desconhecida"
        for (attempt in 1..2) {
            if (System.currentTimeMillis() > deadline) break
            AiStatus.set("${p.label} ($model)")
            Diag.aiCalls.incrementAndGet()
            try {
                val r = request("POST", "$base/chat/completions", headers, compatBody(model, prompt, null, base))
                if (r.code in 200..299) {
                    val t = JSONObject(r.body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                    return@withContext if (t.isNullOrBlank()) Reply(false, null, "resposta vazia") else Reply(true, t, null)
                }
                lastErr = "$model: HTTP ${r.code} " + short(r.body)
                if (r.code == 401 || r.code == 403) return@withContext Reply(false, null, "chave do ${p.label} recusada (HTTP ${r.code})")
                if ((r.code == 404 || r.code == 400) && !rediscovered) {
                    rediscovered = true
                    model = compatModel(ctx, p, base, key, true)
                    continue
                }
                if (r.code != 429 && r.code < 500) break
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
     * Só entram os que têm chave. Gemini sem cota hoje em nenhuma chave vai para o fim (e nem é chamado).
     */
    /** Todos os provedores, na ordem padrão (gratuitos primeiro; Claude é pago). */
    val ALL = listOf("gemini", "compat", "mistral", "openrouter", "claude")

    fun label(p: String): String = when (p) {
        "gemini" -> "Gemini"
        "claude" -> "Claude"
        else -> compatOf(p)?.label ?: "Alternativo"
    }

    fun hasKey(ctx: Context, p: String): Boolean = when (p) {
        "gemini" -> Settings.geminiKeys(ctx).isNotEmpty()
        "claude" -> Settings.get(ctx, Settings.CLAUDE_KEY, "").isNotBlank()
        else -> compatOf(p)?.key(ctx)?.isNotBlank() == true
    }

    fun hasAnyKey(ctx: Context): Boolean = ALL.any { hasKey(ctx, it) }

    // ---------------------------------------------------------------- resultado do último teste

    /** Resultado do teste de um provedor: lê imagem, responde texto, segundos e o erro (se houve). */
    data class TestResult(val image: Boolean, val text: Boolean, val secs: Double, val error: String?, val model: String?, val at: Long)

    private fun testPrefs(ctx: Context) = ctx.getSharedPreferences("ai_test", Context.MODE_PRIVATE)

    fun lastTest(ctx: Context, p: String): TestResult? {
        val raw = testPrefs(ctx).getString(p, null) ?: return null
        return try {
            val j = JSONObject(raw)
            TestResult(j.optBoolean("img"), j.optBoolean("txt"), j.optDouble("secs", 0.0), j.optString("err").ifBlank { null }, j.optString("model").ifBlank { null }, j.optLong("at"))
        } catch (e: Exception) {
            null
        }
    }

    private fun saveTest(ctx: Context, p: String, r: TestResult) {
        val j = JSONObject().put("img", r.image).put("txt", r.text).put("secs", r.secs).put("err", r.error ?: "").put("model", r.model ?: "").put("at", r.at)
        testPrefs(ctx).edit().putString(p, j.toString()).apply()
    }

    /** Busca na internet: quem respondeu no último teste (ou o erro). */
    fun lastWebTest(ctx: Context): Pair<Boolean, String>? {
        val raw = testPrefs(ctx).getString("web", null) ?: return null
        return try { JSONObject(raw).let { Pair(it.optBoolean("ok"), it.optString("text")) } } catch (e: Exception) { null }
    }

    /**
     * Ordem automática a partir do teste: quem leu a imagem (mais rápido primeiro), depois quem só respondeu texto,
     * depois os não testados e por fim os que falharam no teste.
     */
    fun autoOrder(have: List<String>, tests: Map<String, TestResult?>, hasImage: Boolean): List<String> {
        fun score(p: String): Double {
            val t = tests[p] ?: return 2000.0
            val ok = if (hasImage) t.image else t.text || t.image
            return if (ok) t.secs else if (t.text || t.image) 1000.0 + t.secs else 3000.0
        }
        return have.withIndex().sortedWith(compareBy<IndexedValue<String>> { score(it.value) }.thenBy { it.index }).map { it.value }
    }

    /**
     * Ordem: no automático, a do último teste com quem respondeu por último na frente; com uma IA escolhida, ela
     * primeiro e as demais como reserva. Só entram os que têm chave. Gemini sem cota hoje vai para o fim.
     */
    fun providerOrder(ctx: Context, hasImage: Boolean): List<String> {
        val have = ALL.filter { hasKey(ctx, it) }
        val pref = Settings.get(ctx, Settings.PREFERRED, "auto")
        val auto = autoOrder(have, have.associateWith { lastTest(ctx, it) }, hasImage)
        val ordered = if (pref in have) listOf(pref) + ModelPicker.learnedOrder(auto.filter { it != pref }, winners(ctx, hasImage))
        else ModelPicker.learnedOrder(auto, winners(ctx, hasImage))
        // quem acabou de falhar vai para o fim da fila (evita esperar de novo um provedor sobrecarregado)
        val now = System.currentTimeMillis()
        val geminiOut = "gemini" in ordered && !geminiAvailable(ctx)
        fun late(p: String) = (cooldownUntil[p] ?: 0L) > now || (p == "gemini" && geminiOut)
        return ordered.filter { !late(it) } + ordered.filter { late(it) }
    }

    suspend fun askProvider(ctx: Context, provider: String, prompt: String, jpeg: ByteArray?, deadline: Long = System.currentTimeMillis() + 150000L): Reply =
        when (provider) {
            "gemini" -> gemini(ctx, prompt, jpeg, deadline)
            "claude" -> claude(ctx, prompt, jpeg)
            else -> compat(ctx, compatOf(provider) ?: COMPATS.first(), prompt, jpeg, deadline)
        }

    /** Tenta cada provedor com chave, em ordem, com orçamento total de tempo. Nunca fica preso. */
    suspend fun ask(ctx: Context, prompt: String, jpeg: ByteArray?, budgetMs: Long = 150000L): Reply {
        val deadline = System.currentTimeMillis() + budgetMs
        val order = providerOrder(ctx, jpeg != null)
        if (order.isEmpty()) return Reply(false, null, "Nenhuma chave de IA configurada. Abra Ajustes › Inteligência artificial e cole uma chave gratuita.")
        val errors = ArrayList<String>()
        for (p in order) {
            if (System.currentTimeMillis() > deadline) {
                errors.add("tempo total esgotado")
                break
            }
            // Gemini sem cota em todas as chaves/modelos: não gasta chamada nem tempo, só avisa
            if (p == "gemini" && !geminiAvailable(ctx)) {
                errors.add("Gemini: " + exhaustedMsg(ctx))
                continue
            }
            val started = System.currentTimeMillis()
            val r = askProvider(ctx, p, prompt, jpeg, deadline)
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
        val all = errors.joinToString(" | ")
        Diag.trace("IA falhou: $all")
        return Reply(false, null, all)
    }

    /** Imagem de teste: um número grande que a IA precisa ler (prova que o modelo enxerga imagem de verdade). */
    private fun testImage(n: Int): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(320, 160, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.rgb(20, 40, 70))
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 72f
            isFakeBoldText = true
        }
        c.drawText("FORÇA $n", 20f, 105f, paint)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun modelOf(ctx: Context, p: String, image: Boolean): String? = when (p) {
        "gemini" -> Settings.get(ctx, Settings.GEMINI_RESOLVED, "").substringBefore(",").ifBlank { null }
        "claude" -> Settings.get(ctx, Settings.CLAUDE_MODEL, Settings.DEFAULT_CLAUDE_MODEL)
        else -> compatOf(p)?.let { Settings.get(ctx, if (image) it.visionPref else it.resolvedPref, "").takeIf { m -> m.isNotBlank() && !m.startsWith("none") } }
    }

    /**
     * Testa cada provedor com chave: lê uma imagem com um número (o que importa para as telas do OSM), responde texto
     * (tática e Diretor) e, por fim, a busca na internet (táticas da comunidade). Guarda o resultado para a ordem
     * automática e para a tela de Ajustes.
     */
    suspend fun test(ctx: Context): String {
        val have = ALL.filter { hasKey(ctx, it) }
        if (have.isEmpty()) return "Nenhuma chave configurada."
        val lines = ArrayList<String>()
        for (p in have) {
            if (p == "gemini" && !geminiAvailable(ctx)) {
                val msg = exhaustedMsg(ctx)
                saveTest(ctx, p, TestResult(false, false, 0.0, msg, null, System.currentTimeMillis()))
                lines.add("✘ Gemini: $msg")
                continue
            }
            val n = 40 + (System.currentTimeMillis() % 50).toInt()
            AiStatus.set("Testando ${label(p)}: leitura de imagem…")
            val t0 = System.currentTimeMillis()
            val ri = askProvider(ctx, p, "Leia o número escrito na imagem. Responda somente com o JSON {\"n\": numero}", testImage(n), t0 + 60000L)
            val secs = (System.currentTimeMillis() - t0) / 100 / 10.0
            val imgOk = ri.ok && (ri.text ?: "").contains(n.toString())
            var txtOk = imgOk
            var err: String? = when {
                imgOk -> null
                ri.ok -> "respondeu, mas não leu o número da imagem"
                else -> ri.error
            }
            if (!imgOk) {
                AiStatus.set("Testando ${label(p)}: texto…")
                val rt = askProvider(ctx, p, "Responda somente com o JSON {\"ok\": true}", null, System.currentTimeMillis() + 45000L)
                txtOk = rt.ok
                if (!rt.ok) err = (err ?: "") + (if (err != null) " • texto: " else "") + (rt.error ?: "erro")
            }
            val model = modelOf(ctx, p, imgOk)
            saveTest(ctx, p, TestResult(imgOk, txtOk, secs, err, model, System.currentTimeMillis()))
            val m = model?.let { " ($it)" } ?: ""
            lines.add(
                when {
                    imgOk -> "✔ ${label(p)}$m: lê imagem e texto em ${secs}s"
                    txtOk -> "◐ ${label(p)}$m: só texto (não leu a imagem: ${err ?: "?"})"
                    else -> "✘ ${label(p)}$m: ${err ?: "erro"}"
                }
            )
        }
        AiStatus.set("Testando a busca na internet…")
        val w = askGrounded(ctx, "Pesquise na internet e responda em uma linha: qual é o site oficial do jogo Online Soccer Manager?")
        val webText = if (w.ok) "✔ Busca na internet: ${w.by ?: "ok"}" else "✘ Busca na internet: ${w.error ?: "erro"}"
        testPrefs(ctx).edit().putString("web", JSONObject().put("ok", w.ok).put("text", webText).put("at", System.currentTimeMillis()).toString()).apply()
        lines.add(webText)
        if ("gemini" in have) lines.add("Gemini: " + geminiStatus(ctx))
        AiStatus.set("")
        return lines.joinToString("\n")
    }

    /** Pergunta ao Gemini com busca no Google (fontes atuais da internet). Devolve o texto (com JSON no meio). */
    suspend fun askGrounded(ctx: Context, prompt: String): Reply = withContext(Dispatchers.IO) {
        val errs = ArrayList<String>()
        val keys = Settings.geminiKeys(ctx)
        if (keys.isNotEmpty()) {
            // modelos com busca no Google no plano gratuito (a busca tem cota própria, separada da normal)
            val models = (geminiModels(ctx, keys.first(), false).filter { !it.contains("lite") } + ModelPicker.GEMINI_FALLBACK).distinct()
            val g = groundedGemini(ctx, keys, prompt, models)
            if (g.ok) return@withContext g
            errs.add("Gemini: " + g.error)
        }
        // Groq: o modelo "compound" pesquisa na web sozinho
        val ck = Settings.get(ctx, Settings.COMPAT_KEY, "")
        val base = Settings.get(ctx, Settings.COMPAT_BASE, Settings.DEFAULT_COMPAT_BASE).trimEnd('/')
        if (ck.isNotBlank() && base.contains("groq.com")) {
            // gpt-oss no Groq com a ferramenta de busca no navegador (a chave gratuita costuma ter acesso)
            for (model in listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b")) {
                AiStatus.set("Pesquisando a comunidade do OSM na internet (Groq $model + busca)…")
                Diag.aiCalls.incrementAndGet()
                try {
                    val body = JSONObject().put("model", model).put("temperature", 0.2)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                        .put("tools", JSONArray().put(JSONObject().put("type", "browser_search"))).toString()
                    val r = request("POST", "$base/chat/completions", mapOf("Authorization" to "Bearer $ck"), body)
                    if (r.code in 200..299) {
                        val t = JSONObject(r.body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                        if (!t.isNullOrBlank()) {
                            AiStatus.set("")
                            return@withContext Reply(true, t, null, "Groq $model + busca")
                        }
                        errs.add("Groq $model (busca): resposta vazia")
                    } else errs.add("Groq $model (busca): HTTP ${r.code} " + short(r.body))
                } catch (e: Exception) {
                    errs.add("Groq $model (busca): " + (e.message ?: e.javaClass.simpleName))
                }
            }
            for (model in listOf("groq/compound", "groq/compound-mini")) {
                AiStatus.set("Pesquisando a comunidade do OSM na internet (Groq $model)…")
                Diag.aiCalls.incrementAndGet()
                try {
                    val body = JSONObject().put("model", model).put("temperature", 0.2)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt))).toString()
                    val r = request("POST", "$base/chat/completions", mapOf("Authorization" to "Bearer $ck"), body)
                    if (r.code in 200..299) {
                        val t = JSONObject(r.body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                        if (!t.isNullOrBlank()) {
                            AiStatus.set("")
                            return@withContext Reply(true, t, null, "Groq $model")
                        }
                        errs.add("Groq $model: resposta vazia")
                    } else errs.add("Groq $model: HTTP ${r.code} " + short(r.body))
                } catch (e: Exception) {
                    errs.add("Groq $model: " + (e.message ?: e.javaClass.simpleName))
                }
            }
        }
        AiStatus.set("")
        Reply(false, null, errs.joinToString(" | ").ifBlank { "nenhuma IA com busca na internet configurada" })
    }

    private fun groundedGemini(ctx: Context, keys: List<String>, prompt: String, models: List<String>): Reply {
        var lastErr = "falha desconhecida"
        var attempts = 0
        for (model in models) for (key in keys) {
            if (attempts >= 6) return Reply(false, null, lastErr)
            // a busca tem cota diária própria: bloqueia só "busca" daquele modelo naquela chave
            if (blockedToday(ctx, key, "search:$model") || gone(ctx, model)) continue
            attempts++
            Diag.aiCalls.incrementAndGet()
            val tag = if (keys.size > 1) "$model, chave ${keyLabel(key)}" else model
            AiStatus.set("Pesquisando a comunidade do OSM na internet ($tag)…")
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
                .put("generationConfig", JSONObject().put("temperature", 0.2).put("maxOutputTokens", 8192))
                .toString()
            try {
                val r = request("POST", "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", mapOf("x-goog-api-key" to key), body)
                if (r.code in 200..299) {
                    val parts = JSONObject(r.body).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    val sb = StringBuilder()
                    if (parts != null) for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text") ?: "")
                    if (sb.isNotBlank()) {
                        AiStatus.set("")
                        return Reply(true, sb.toString(), null, "Gemini $model + Google")
                    }
                    lastErr = "resposta vazia ($tag)"
                } else {
                    val b = r.body.lowercase()
                    lastErr = when {
                        r.code == 429 -> {
                            if (dailyQuota(r.body)) blockForToday(ctx, key, "search:$model")
                            "cota da busca esgotada ($tag)"
                        }
                        r.code == 401 || r.code == 403 || b.contains("api key not valid") -> {
                            blockKeyForToday(ctx, key)
                            "chave ${keyLabel(key)} recusada"
                        }
                        r.code == 404 -> {
                            markGone(ctx, model)
                            "modelo não existe ($model)"
                        }
                        else -> "HTTP ${r.code} ($tag) " + short(r.body)
                    }
                }
            } catch (e: Exception) {
                lastErr = (e.message ?: e.javaClass.simpleName) + " ($tag)"
            }
        }
        return Reply(false, null, lastErr)
    }

    fun parseJson(raw: String?): JSONObject? {
        if (raw == null) return null
        val s = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val a = s.indexOf('{')
        val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        try { return JSONObject(s.substring(a, b + 1)) } catch (e: Exception) { }
        // Texto com mais de um bloco (ex.: resposta da busca com explicação e citações): pega o maior objeto válido.
        var best: JSONObject? = null
        var bestLen = 0
        var i = s.indexOf('{')
        while (i >= 0) {
            var depth = 0
            var inStr = false
            var esc = false
            var end = -1
            for (j in i until s.length) {
                val c = s[j]
                if (inStr) {
                    if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
                    continue
                }
                if (c == '"') inStr = true
                else if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) {
                        end = j
                        break
                    }
                }
            }
            if (end < 0) break
            if (end - i + 1 > bestLen) {
                val o = try { JSONObject(s.substring(i, end + 1)) } catch (e: Exception) { null }
                if (o != null) {
                    best = o
                    bestLen = end - i + 1
                }
            }
            i = s.indexOf('{', end + 1)
        }
        return best
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
        "formation (ex.: \"4-4-2\" ou \"4-4-2 B\"), playStyle (estilo de jogo), marking (\"À zona\" ou \"Homem-a-homem\"), " +
        "offside (\"Sim\" ou \"Não\"), tackle (desarme: ex. \"Normal\" ou \"Agressivo\"), " +
        "secretTraining (\"Sim\" SOMENTE se houver um cadeado visível no relatório; caso contrário \"NI\"), " +
        "trainingCamp (\"Sim\"/\"Não\"/\"NI\"), rivalStrength (número), rivalValue (ex.: \"21,1M\" — mantenha a vírgula), " +
        "stadiumLevel (texto), stadiumBonus (texto), loginBonus (texto), referee (pela cor do termômetro ao lado de \"Árbitro\": azul = \"Muito brando\", verde = \"Brando\", amarelo = \"Normal\", laranja = \"Rigoroso\", vermelho = \"Muito rigoroso\")."

    fun squad(): String = HEAD +
        "É a lista de elenco. Chaves: team (nome do time no cabeçalho), players (lista). Cada jogador: " +
        "name, age (número), pos (código como ED, PL, MC, DC, GR), strength (a força em negrito: Ata para atacantes, " +
        "Med para meias, Def para defensores e goleiros), value (ex.: \"7,3M\"). " +
        "Não informe treino: a cor da camisa no plantel não indica treino."

    /** Tela do próximo jogo (pré-jogo): os dois times, quem é usuário, os círculos de força/bônus e o árbitro. */
    fun pregame(): String = HEAD +
        "É a tela do próximo jogo (pré-jogo) com dois times frente a frente. Chaves: " +
        "leftTeam, leftUser (nome de usuário escrito logo abaixo do time da ESQUERDA, ou \"NI\" se não houver), " +
        "leftCircle (texto dentro do círculo do time da esquerda: um número de força ex. \"84\" ou um bônus ex. \"+3%\"), " +
        "rightTeam, rightUser, rightCircle (o mesmo para o time da DIREITA), round (número da jornada), " +
        "refereeColor (cor do termômetro ao lado de \"Árbitro\": \"azul\", \"verde\", \"amarelo\", \"laranja\" ou \"vermelho\")."
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
        Referee.norm(clean(j.optString("referee")))?.let { out[K.REFEREE] = Reading(it, 0.6) }
        return out
    }

    /**
     * Pré-jogo lido pela IA. O meu time é o lado com o meu apelido (ou com o nome do meu time já guardado); o outro é o
     * rival. Círculo com número = força; com "+N%" = bônus de login (só humanos têm).
     */
    fun pregame(j: JSONObject, myTeam: String?): Map<String, Reading> {
        val out = LinkedHashMap<String, Reading>()
        val c = 0.7
        val lt = clean(j.optString("leftTeam"))
        val rt = clean(j.optString("rightTeam"))
        val lu = clean(j.optString("leftUser"))
        val ru = clean(j.optString("rightUser"))
        fun mine(team: String?, user: String?): Boolean =
            (user != null && Txt.sim(Txt.key(user), MY_NICK) >= 0.75) ||
                (team != null && myTeam != null && SlotMatcher.nameSim(Txt.key(team), Txt.key(myTeam)) >= 0.85)
        val mineLeft = when {
            mine(lt, lu) -> true
            mine(rt, ru) -> false
            else -> return out
        }
        val rivalTeam = if (mineLeft) rt else lt
        val rivalUser = (if (mineLeft) ru else lu)?.takeIf { Evidence.plausibleNick(it) }
        val myCircle = clean(j.optString(if (mineLeft) "leftCircle" else "rightCircle"))
        val rivalCircle = clean(j.optString(if (mineLeft) "rightCircle" else "leftCircle"))
        rivalTeam?.let { out[K.RIVAL_TEAM] = Reading(it, c) }
        out[K.HOME] = Reading(if (mineLeft) "Casa" else "Fora", c)
        fun circle(v: String?, strength: String, bonus: String) {
            val t = v?.replace(" ", "") ?: return
            Regex("^\\+?(\\d{1,2})%$").find(t)?.let { out[bonus] = Reading("+" + it.groupValues[1] + "%", c); return }
            t.toIntOrNull()?.takeIf { it in 30..130 }?.let { out[strength] = Reading(it.toString(), c) }
        }
        circle(myCircle, K.MY_STRENGTH, K.MY_BONUS)
        circle(rivalCircle, K.RIVAL_STRENGTH, K.RIVAL_LOGIN_BONUS)
        if (rivalUser != null) {
            out[K.RIVAL_NICK] = Reading(rivalUser, c)
            out[K.RIVAL_HUMAN] = Reading("Sim", c)
        }
        j.optString("round").trim().toIntOrNull()?.takeIf { it in 1..60 }?.let { out[K.ROUND] = Reading(it.toString(), 0.65) }
        Referee.norm(clean(j.optString("refereeColor")))?.let { out[K.REFEREE] = Reading(it, 0.65) }
        return out
    }

    fun squad(j: JSONObject): Pair<String?, List<PlayerRead>> {
        val team = clean(j.optString("team"))
        val arr = j.optJSONArray("players") ?: return Pair(team, emptyList())
        val out = ArrayList<PlayerRead>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = PlayerName.clean(o.optString("name")) ?: continue
            val age = o.optInt("age", -1).takeIf { it in 15..45 }
            val pos = o.optString("pos").trim().uppercase().takeIf { it.length in 1..4 && it.all { ch -> ch.isLetter() } }
            val strength = o.optInt("strength", -1).takeIf { it in 30..120 }
            val value = o.optString("value").trim().takeIf { Money.valid(it) }
            // treino vem só da tela de Treinamento (a camisa no plantel não indica treino)
            val training: Boolean? = null
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

    private const val WAITING = "aguardando a IA voltar: "

    /** Falha que passa sozinha (cota do dia, limite por minuto, servidor sobrecarregado, rede lenta). */
    fun transient(err: String): Boolean {
        val e = err.lowercase()
        return listOf("cota", "teto diário", "http 429", "http 500", "http 502", "http 503", "http 504", "sobrecarregad", "tempo esgotado",
            "overloaded", "unable to resolve host", "timeout", "failed to connect").any { e.contains(it) }
    }

    private suspend fun readScreen(ctx: Context, repo: Repo, s: ScreenEntity): ReadResult {
        if (!AiClient.hasAnyKey(ctx)) return ReadResult("skipped", 0, "sem chave de IA (Ajustes › Inteligência artificial)")
        if (s.slotId == 0) return ReadResult("pending", 0, "aguardando identificação de slot")
        val path = s.imagePath
        val bytes = if (path != null) File(path).takeIf { it.exists() }?.readBytes() else null
        if (bytes == null) return ReadResult("failed", 0, "imagem ausente")
        val type = runCatching { ScreenType.valueOf(s.type) }.getOrDefault(ScreenType.OTHER_OSM)
        val prompt = when (type) {
            ScreenType.SQUAD -> AiPrompts.squad()
            ScreenType.PREGAME -> AiPrompts.pregame()
            else -> AiPrompts.report()
        }
        val reply = AiClient.ask(ctx, prompt, bytes, 100000L)
        val json = AiClient.parseJson(reply.text)
        if (!reply.ok || json == null) {
            val err = reply.error ?: "IA devolveu JSON inválido"
            Diag.lastError = err
            // Sem cota / sobrecarga / tempo: a tela continua na fila e é lida quando a IA voltar (não se perde).
            if (!reply.ok && transient(err)) return ReadResult("pending", 0, WAITING + err.take(160))
            return ReadResult("failed", 0, err.take(200))
        }
        val now = System.currentTimeMillis()
        if (type == ScreenType.PREGAME) {
            val f = AiMapper.pregame(json, repo.fieldMap(s.slotId)[K.TEAM]?.value)
            if (f.isEmpty()) return ReadResult("done", 0, "IA: não achei o seu time nesta tela (confira o apelido)")
            val n = repo.apply(s.slotId, Extraction(ScreenType.PREGAME, fields = f), "ai", now)
            return ReadResult("done", n, "IA leu o pré-jogo: " + f.keys.joinToString(", ") { k -> ManualFields.spec(k, k).label } + " ($n novo(s))")
        }
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
        var lastFail = ""
        var waiting = 0
        for ((i, s) in pending.withIndex()) {
            ProcessState.label("Lendo tela ${i + 1} de ${pending.size} com IA…")
            val r = readScreen(ctx, repo, s)
            dao.updateScreenAi(s.id, r.state, s.extracted + r.changed, r.note)
            if (r.state == "pending" && r.note.startsWith(WAITING)) {
                // IA indisponível agora: não insiste nas outras telas (ficam na fila para a próxima vez)
                waiting = pending.size - i
                lastFail = r.note.removePrefix(WAITING)
                break
            }
            when {
                r.state == "failed" -> {
                    failed++
                    lastFail = r.note
                }
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
            listOfNotNull(
                "Telas reprocessadas: $fixed",
                if (pending.isEmpty()) "IA: nada pendente" else "IA: $applied campos aplicados",
                if (noData > 0) "$noData tela(s) sem relatório do rival" else null,
                if (failed > 0) "$failed não lida(s) pela IA (${lastFail.take(90)})" else null,
                if (waiting > 0) "$waiting tela(s) guardada(s) para ler quando a IA voltar (${lastFail.take(90)})" else null,
                if (skipped > 0) "$skipped ignorada(s)" else null
            ).joinToString(" • ") +
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


/**
 * Modelo da comunidade atualizado pela internet: o Gemini (com busca no Google) consulta fóruns e sites de OSM
 * e devolve a tática recomendada por situação. Fica guardado (slot 0) e vale por 30 dias; os resultados reais
 * dos 4 slots continuam ajustando por cima (MetaLearner).
 */
object MetaRefresh {
    private const val KIND = "metaremote"
    /** Última resposta da pesquisa (texto cru + o que foi aproveitado), para o diagnóstico exportado. */
    private const val LAST = "metaremote_last"
    @Volatile var running = false

    suspend fun remote(repo: Repo): JSONObject? = repo.dao.plan(0, KIND)?.json?.let { runCatching { JSONObject(it) }.getOrNull() }

    private val BASES = listOf("4-3-3", "4-4-2", "4-2-3-1", "4-5-1", "5-4-1", "5-3-2", "3-4-3", "5-2-3", "3-5-2")

    fun prompt(): String =
        "Pesquise na internet (fórum oficial do Online Soccer Manager, osmnextlevel.com, osmcoach.com, osmtacticsassistant.com, " +
            "osmguide.com, Reddit r/OnlineSoccerManager, vídeos do YouTube) as táticas que a comunidade recomenda HOJE no OSM " +
            "(Online Soccer Manager) para cada situação. Situação = relação de força + formação do rival: \"U\" (meu time MAIS FRACO), " +
            "\"E\" (forças parecidas) ou \"F\" (meu time MAIS FORTE), e a formação base do rival em $BASES. " +
            "Responda SOMENTE com um JSON neste formato exato (valores em português como no jogo; os três controles são números de 0 a 100):\n" +
            "{\"rules\": {\"F|4-4-2\": {\"formation\": \"4-3-3 A\", \"style\": \"Jogar pelas alas\", \"pressure\": 70, \"mentality\": 74, " +
            "\"tempo\": 65, \"attack\": \"Atacar apenas\", \"mid\": \"Manter posições\", \"def\": \"Defender atrás\", \"marking\": \"À zona\", " +
            "\"offside\": \"Não\", \"source\": \"osmnextlevel.com\"}, \"U|4-3-3\": {...}}}\n" +
            "formation: uma de ${Formations.OSM}. style: um de ${Osm.STYLES}. attack: um de ${Osm.ATTACK}. mid: um de ${Osm.MIDFIELD}. " +
            "def: um de ${Osm.DEFENSE}. marking: um de ${Osm.MARKING}. Use \"?\" no lugar da formação do rival para a regra geral " +
            "daquela relação de força (ex.: \"U|?\"). Traga o máximo de situações que as fontes cobrirem (ideal: as 3 relações " +
            "de força para cada formação). Nada de texto fora do JSON."

    /** "U|4-3-3", "weaker | 4-3-3 A", "F:442" -> "U|4-3-3" / "F|4-4-2"; null se não der para entender. */
    fun normKey(raw: String): String? {
        val parts = raw.trim().split(Regex("\\s*[|:/]\\s*"), limit = 2)
        if (parts.size != 2) return null
        val r = Txt.norm(parts[0])
        val rel = when {
            r == "u" || r.startsWith("under") || r.contains("weak") || r.contains("fraco") || r.contains("azar") -> "U"
            r == "e" || r.startsWith("even") || r.startsWith("equal") || r.contains("parelh") || r.contains("igual") || r.contains("equilib") -> "E"
            r == "f" || r.startsWith("fav") || r.contains("strong") || r.contains("forte") -> "F"
            else -> return null
        }
        val fp = parts[1].trim()
        val n = Txt.norm(fp)
        val base = if (fp == "?" || n.isEmpty() || n.contains("desconh") || n.contains("unknown") || n == "any" || n.contains("qualquer") || n.contains("geral")) "?"
        else Formations.parse(fp)?.let { Formations.base(it) } ?: return null
        return "$rel|$base"
    }

    private fun slider(o: JSONObject, vararg names: String): Int? {
        for (n in names) {
            if (!o.has(n) || o.isNull(n)) continue
            val v = Regex("(\\d{1,3})").find(o.optString(n))?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (v in 0..100) return v
        }
        return null
    }

    /** Uma regra limpa (só valores do jogo); null se faltar formação ou estilo reconhecíveis. */
    fun cleanRule(o: JSONObject, source: String): JSONObject? {
        val f = Formations.canonical(o.optString("formation")) ?: return null
        val st = Osm.style(o.optString("style").ifBlank { o.optString("playStyle") }) ?: return null
        val out = JSONObject().put("formation", f).put("style", st)
        slider(o, "pressure", "pressing")?.let { out.put("pressure", it) }
        slider(o, "mentality", "style_slider", "estilo")?.let { out.put("mentality", it) }
        slider(o, "tempo")?.let { out.put("tempo", it) }
        Osm.attack(o.optString("attack").ifBlank { o.optString("forwards") })?.let { out.put("attack", it) }
        Osm.midfield(o.optString("mid").ifBlank { o.optString("midfielders") })?.let { out.put("mid", it) }
        Osm.defense(o.optString("def").ifBlank { o.optString("defenders") })?.let { out.put("def", it) }
        Osm.marking(o.optString("marking"))?.let { out.put("marking", it) }
        val off = Txt.norm(o.optString("offside"))
        out.put("offside", if (off == "sim" || off == "yes" || off == "true") "Sim" else "Não")
        out.put("source", o.optString("source").take(50).ifBlank { "fóruns" } + source)
        return out
    }

    /**
     * Aceita os formatos que as IAs costumam devolver: {"rules": {"U|4-3-3": {...}}}, {"rules": [{"situation": "U|4-3-3", ...}]},
     * {"rules": {"U": {"4-3-3": {...}}}} ou as regras soltas na raiz.
     */
    fun cleanRules(json: JSONObject, source: String): JSONObject {
        val clean = JSONObject()
        fun take(key: String?, o: JSONObject?) {
            if (key == null || o == null) return
            val k = normKey(key) ?: return
            cleanRule(o, source)?.let { clean.put(k, it) }
        }
        val rules = json.opt("rules") ?: json
        when (rules) {
            is JSONObject -> for (k in rules.keys()) {
                val v = rules.opt(k)
                if (v !is JSONObject) continue
                if (normKey(k) != null) take(k, v)
                else for (k2 in v.keys()) take("$k|$k2", v.optJSONObject(k2))
            }
            is JSONArray -> for (i in 0 until rules.length()) {
                val o = rules.optJSONObject(i) ?: continue
                val key = o.optString("key").ifBlank { o.optString("situation") }.ifBlank {
                    val rel = o.optString("strength").ifBlank { o.optString("relation") }
                    val rf = o.optString("rivalFormation").ifBlank { o.optString("opponentFormation") }.ifBlank { "?" }
                    if (rel.isBlank()) "" else "$rel|$rf"
                }
                take(key.ifBlank { null }, o)
            }
        }
        return clean
    }

    suspend fun refresh(ctx: Context): String {
        if (running) return "já está atualizando"
        running = true
        val repo = Repo(ctx)
        val now = System.currentTimeMillis()
        Settings.put(ctx, "meta_try_at", now.toString())
        val log = JSONObject().put("at", now)
        try {
            var reply = AiClient.askGrounded(ctx, prompt())
            var viaSearch = true
            log.put("searchError", reply.error ?: "")
            if (!reply.ok) {
                // sem busca disponível (cota): usa o que a IA já sabe dos fóruns, marcado como tal
                val r2 = AiClient.ask(ctx, prompt() + " Se não puder pesquisar agora, use o que você sabe dessas fontes.", null, 90000L)
                if (r2.ok) {
                    reply = r2
                    viaSearch = false
                } else {
                    log.put("error", r2.error ?: "")
                    return "não consegui pesquisar agora (${reply.error}); sem busca também falhou (${r2.error})"
                }
            }
            log.put("raw", (reply.text ?: "").take(20000))
            val json = AiClient.parseJson(reply.text) ?: return "a IA respondeu sem o JSON das regras; tente de novo mais tarde"
            val clean = cleanRules(json, if (viaSearch) "" else " • sem busca")
            log.put("valid", clean.length())
            if (clean.length() == 0) {
                return "a pesquisa respondeu, mas nenhuma regra veio com formação e estilo do OSM; mantive o modelo atual " +
                    "(a resposta ficou guardada no diagnóstico)"
            }
            // junta com o que já havia: situações novas entram, as repetidas são atualizadas
            val old = remote(repo)?.optJSONObject("rules")
            val merged = JSONObject()
            if (old != null) for (k in old.keys()) merged.put(k, old.get(k))
            for (k in clean.keys()) merged.put(k, clean.get(k))
            repo.dao.putPlan(PlanEntity(0, KIND, JSONObject().put("at", now).put("rules", merged).toString(), now))
            return "modelo da comunidade atualizado: ${clean.length()} situação(ões) da pesquisa, ${merged.length()} no total" +
                (if (viaSearch) " (pesquisa na internet)" else " (a busca estava sem cota: usei o conhecimento da IA; tento a busca de novo na semana)")
        } finally {
            runCatching { repo.dao.putPlan(PlanEntity(0, LAST, log.toString(), now)) }
            running = false
        }
    }

    /** Situação do modelo da comunidade para a tela de Ajustes: o que está valendo e como foi a última pesquisa. */
    suspend fun status(repo: Repo): List<String> {
        val out = ArrayList<String>()
        val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
        val r = remote(repo)
        val rules = r?.optJSONObject("rules")
        if (r == null || rules == null || rules.length() == 0) {
            out.add("Nenhuma regra da internet ainda: a tática usa o modelo embutido do app + os resultados dos seus slots.")
        } else {
            val age = System.currentTimeMillis() - r.optLong("at")
            out.add(
                "${rules.length()} situação(ões) da comunidade, atualizadas em ${fmt.format(java.util.Date(r.optLong("at")))}" +
                    if (age > 30L * 86400000L) " (vencidas: mais de 30 dias, não estão sendo usadas)" else " (em uso)"
            )
            val names = mapOf("U" to "mais fraco", "E" to "parelho", "F" to "mais forte")
            for (k in rules.keys().asSequence().sorted()) {
                val o = rules.optJSONObject(k) ?: continue
                val rel = names[k.substringBefore('|')] ?: k.substringBefore('|')
                val rf = k.substringAfter('|').let { if (it == "?") "qualquer formação" else it }
                out.add(
                    "• Meu time $rel vs $rf: ${o.optString("formation")} • ${o.optString("style")}" +
                        (if (o.has("pressure")) " • pressão ${o.optInt("pressure")}" else "") +
                        (if (o.has("mentality")) " / estilo ${o.optInt("mentality")}" else "") +
                        (if (o.has("tempo")) " / ritmo ${o.optInt("tempo")}" else "") + " — ${o.optString("source")}"
                )
            }
        }
        repo.dao.plan(0, LAST)?.let { p ->
            val j = runCatching { JSONObject(p.json) }.getOrNull() ?: return@let
            val res = when {
                j.has("error") -> "falhou: " + j.optString("error").take(160)
                j.optInt("valid", -1) == 0 -> "a resposta veio sem regras utilizáveis"
                j.has("valid") -> "${j.optInt("valid")} regra(s) aproveitada(s)" + (if (j.optString("searchError").isNotBlank()) " (sem busca na internet: ${j.optString("searchError").take(120)})" else " (com busca na internet)")
                else -> "sem resultado"
            }
            out.add("Última pesquisa: ${fmt.format(java.util.Date(p.at))} — $res")
        }
        return out
    }

    /** Regra da comunidade pronta para a tela. */
    data class RuleView(
        val rel: String, val vs: String, val formation: String, val style: String, val pressure: Int?, val mentality: Int?, val tempo: Int?,
        val roles: String, val marking: String?, val offside: String?, val source: String
    )

    /** Situação da pesquisa: regras em uso, quando foi, se buscou na internet e o problema em palavras simples. */
    data class View(val rules: List<RuleView>, val at: Long?, val active: Boolean, val lastAt: Long?, val searched: Boolean?, val problem: String?)

    suspend fun view(repo: Repo): View {
        val r = remote(repo)
        val rules = r?.optJSONObject("rules")
        val out = ArrayList<RuleView>()
        val order = listOf("U", "E", "F")
        if (rules != null) {
            for (k in rules.keys().asSequence().sortedBy { order.indexOf(it.substringBefore('|')).let { i -> if (i < 0) 9 else i } }) {
                val o = rules.optJSONObject(k) ?: continue
                val vs = k.substringAfter('|').let { if (it == "?" || it.isBlank()) "qualquer formação" else it }
                val roles = listOfNotNull(
                    o.optString("attack").ifBlank { null }?.let { "Avançados: $it" },
                    o.optString("mid").ifBlank { null }?.let { "Médios: $it" },
                    o.optString("def").ifBlank { null }?.let { "Defesas: $it" }
                ).joinToString(" • ")
                out.add(
                    RuleView(
                        k.substringBefore('|'), vs, o.optString("formation"), o.optString("style"),
                        if (o.has("pressure")) o.optInt("pressure") else null, if (o.has("mentality")) o.optInt("mentality") else null,
                        if (o.has("tempo")) o.optInt("tempo") else null, roles,
                        o.optString("marking").ifBlank { null }, o.optString("offside").ifBlank { null },
                        o.optString("source").substringBefore(" • ").ifBlank { "IA" }
                    )
                )
            }
        }
        val at = r?.optLong("at")?.takeIf { it > 0 }
        val active = at != null && System.currentTimeMillis() - at <= 30L * 86400000L && out.isNotEmpty()
        var lastAt: Long? = null
        var searched: Boolean? = null
        var problem: String? = null
        repo.dao.plan(0, LAST)?.let { p ->
            val j = runCatching { JSONObject(p.json) }.getOrNull() ?: return@let
            lastAt = p.at
            val err = j.optString("error")
            val se = j.optString("searchError")
            searched = se.isBlank() && err.isBlank()
            problem = when {
                err.isNotBlank() -> "A última pesquisa falhou: " + plainError(err)
                j.optInt("valid", -1) == 0 -> "A IA respondeu, mas sem regras aproveitáveis."
                se.isNotBlank() -> "Sem busca na internet desta vez (" + plainError(se) + "): as regras vieram do conhecimento da própria IA."
                else -> null
            }
        }
        return View(out, at, active, lastAt, searched, problem)
    }

    /** Erro técnico -> frase curta. */
    private fun plainError(e: String): String {
        val n = e.lowercase()
        return when {
            n.contains("cota") || n.contains("quota") || n.contains("429") -> "a cota gratuita do Gemini para busca acabou hoje"
            n.contains("404") && n.contains("groq") -> "a chave da Groq não tem o modelo de busca"
            n.contains("sem chave") || n.contains("no key") -> "falta chave de IA"
            n.contains("timeout") || n.contains("tempo") -> "a internet demorou demais"
            else -> e.take(100)
        }
    }

    /** Atualiza sozinho uma vez por semana (em segundo plano); depois de uma tentativa, espera 24 h para tentar de novo. */
    fun refreshIfStale(ctx: Context) {
        if (running || !AiClient.hasAnyKey(ctx)) return
        val lastTry = Settings.get(ctx, "meta_try_at", "0").toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - lastTry < 24L * 3600000L) return
        AppScope.scope.launch(Dispatchers.IO) {
            val r = remote(Repo(ctx))
            if (r == null || System.currentTimeMillis() - r.optLong("at") > 7L * 86400000L) {
                runCatching { Diag.log("Modelo da comunidade: " + refresh(ctx)) }
            }
        }
    }
}
