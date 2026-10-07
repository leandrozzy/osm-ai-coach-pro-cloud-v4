package com.osmaicoach.collector

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object AppScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

/** Contadores e estado para a tela de diagnóstico. */
object Diag {
    @Volatile var serviceConnected = false
    @Volatile var lastOsmEventAt = 0L
    @Volatile var lastShotAt = 0L
    @Volatile var currentType = "—"
    @Volatile var currentSlot = 0
    @Volatile var lastUpdateAt = 0L
    @Volatile var lastError: String? = null
    val valid = AtomicInteger()
    val discarded = AtomicInteger()
    val dedup = AtomicInteger()
    val ocr = AtomicInteger()
    val parsed = AtomicInteger()
    val extracted = AtomicInteger()
    val unassigned = AtomicInteger()
    val aiCalls = AtomicInteger()

    private val recent = ArrayList<String>()

    /** Últimos eventos do pipeline (mais novo primeiro), sem repetir a mesma mensagem em sequência. */
    @Synchronized
    fun log(msg: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()) + "  " + msg
        if (recent.isNotEmpty() && recent[0].substring(10) == msg) return
        recent.add(0, line)
        while (recent.size > 14) recent.removeAt(recent.size - 1)
    }

    @Synchronized
    fun recentEvents(): List<String> = ArrayList(recent)

    fun resetCounters() {
        valid.set(0); discarded.set(0); dedup.set(0); ocr.set(0)
        parsed.set(0); extracted.set(0); unassigned.set(0); aiCalls.set(0)
    }
}

/** Controle da sessão de captura (sobrevive à morte do processo: o estado fica em SharedPreferences + Room). */
object Control {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("collector_runtime", Context.MODE_PRIVATE)

    fun activeSession(ctx: Context): String? = prefs(ctx).getString("active_session", null)

    suspend fun start(ctx: Context): String {
        val dao = CoachDb.get(ctx).dao()
        val now = System.currentTimeMillis()
        val id = "S" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        dao.putSession(SessionEntity(id, now, null, "recording"))
        prefs(ctx).edit().putString("active_session", id).apply()
        Diag.resetCounters()
        Diag.lastError = null
        return id
    }

    /** Encerra a captura imediatamente e processa o que ficou pendente em segundo plano. */
    suspend fun end(ctx: Context) {
        val id = activeSession(ctx) ?: return
        val dao = CoachDb.get(ctx).dao()
        prefs(ctx).edit().remove("active_session").apply()
        val s = dao.session(id)
        if (s != null) dao.putSession(s.copy(endedAt = System.currentTimeMillis(), state = "processing"))
        AppScope.scope.launch {
            try {
                Processor.run(ctx.applicationContext, id)
            } catch (e: Exception) {
                Diag.lastError = "Processamento: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                val cur = dao.session(id)
                if (cur != null) dao.putSession(cur.copy(state = "done"))
            }
        }
    }
}
