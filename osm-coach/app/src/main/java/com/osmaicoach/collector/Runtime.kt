package com.osmaicoach.collector

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object AppScope {
    // Sem handler, uma exceção não tratada num launch derruba o processo inteiro.
    private val onError = CoroutineExceptionHandler { _, e ->
        Diag.lastError = "Tarefa em segundo plano: " + (e.message ?: e.javaClass.simpleName)
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + onError)
}

/** O app está na tela (o botão flutuante "Encerrar" some quando o próprio app está aberto). */
object AppVisible {
    @Volatile var resumed = false
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

/** Progresso do processamento pós-captura (barra de progresso na tela Hoje). */
object ProcessState {
    @Volatile var running = false
    @Volatile var title = ""
    @Volatile var total = 0
    @Volatile var done = 0
    @Volatile var startedAt = 0L
    @Volatile var finishedAt = 0L
    @Volatile var summary = ""

    fun begin(title: String, total: Int) {
        running = true
        this.title = title
        this.total = total
        done = 0
        startedAt = System.currentTimeMillis()
        finishedAt = 0L
        summary = ""
    }

    fun label(t: String) { title = t }

    fun tick() { done++ }

    fun finish(summary: String) {
        this.summary = summary
        finishedAt = System.currentTimeMillis()
        running = false
    }
}

/** Tarefas de IA (tática/mercado) vivem fora da tela: continuam mesmo trocando de aba ou fechando o app. */
object GenState {
    data class Job(val kind: String, val slot: Int, val startedAt: Long, val finishedAt: Long, val ok: Boolean?, val error: String?)

    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    fun get(kind: String, slot: Int): Job? = jobs["$kind:$slot"]

    fun running(kind: String, slot: Int): Boolean = get(kind, slot).let { it != null && it.ok == null }

    /** Marca a tarefa como iniciada; false se já havia uma igual em andamento (dois toques rápidos). */
    fun begin(kind: String, slot: Int): Boolean {
        var started = false
        jobs.compute("$kind:$slot") { _, cur ->
            if (cur != null && cur.ok == null) cur
            else Job(kind, slot, System.currentTimeMillis(), 0L, null, null).also { started = true }
        }
        return started
    }

    fun finish(kind: String, slot: Int, ok: Boolean, error: String?) {
        val j = get(kind, slot) ?: return
        jobs["$kind:$slot"] = j.copy(finishedAt = System.currentTimeMillis(), ok = ok, error = error)
    }
}

fun startGeneration(ctx: Context, kind: String, slot: Int) {
    val app = ctx.applicationContext
    if (!GenState.begin(kind, slot)) return
    AppScope.scope.launch(Dispatchers.IO) {
        val r = try {
            when (kind) {
                "tactic" -> Director.generateTacticLocal(Repo(app), slot)
                "tactic_ai" -> Director.refineTactic(app, Repo(app), slot)
                else -> Director.marketNote(app, Repo(app), slot)
            }
        } catch (e: Exception) {
            Director.Outcome(false, null, e.message ?: e.javaClass.simpleName)
        }
        GenState.finish(kind, slot, r.ok, r.error)
    }
}

fun startReadOne(ctx: Context, id: Long) {
    val app = ctx.applicationContext
    if (ProcessState.running) return
    AppScope.scope.launch(Dispatchers.IO) {
        try {
            Processor.readOne(app, id)
        } catch (e: Exception) {
            ProcessState.finish("Erro: " + (e.message ?: e.javaClass.simpleName))
        }
    }
}

fun startReadLatest(ctx: Context, slot: Int) {
    val app = ctx.applicationContext
    if (ProcessState.running) return
    AppScope.scope.launch(Dispatchers.IO) {
        try {
            Processor.readLatest(app, slot)
        } catch (e: Exception) {
            ProcessState.finish("Erro: " + (e.message ?: e.javaClass.simpleName))
        }
    }
}

object AiTest {
    @Volatile var running = false
    @Volatile var result = ""
}

fun startAiTest(ctx: Context) {
    val app = ctx.applicationContext
    if (AiTest.running) return
    AiTest.running = true
    AiTest.result = "Testando…"
    AppScope.scope.launch(Dispatchers.IO) {
        AiTest.result = try { AiClient.test(app) } catch (e: Exception) { "Erro: " + (e.message ?: "?") }
        AiTest.running = false
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
                try {
                    Notifier.reschedule(ctx.applicationContext)
                    Notifier.directorSummary(ctx.applicationContext)
                } catch (e: Exception) {
                    Diag.lastError = "Notificações: " + (e.message ?: e.javaClass.simpleName)
                }
            } catch (e: Exception) {
                Diag.lastError = "Processamento: " + (e.message ?: e.javaClass.simpleName)
                ProcessState.finish("Erro no processamento: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                val cur = dao.session(id)
                if (cur != null) dao.putSession(cur.copy(state = "done"))
            }
        }
    }
}
