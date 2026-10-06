package com.osmaicoach.collector

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executor

class OsmCaptureAccessibilityService : AccessibilityService() {
    private lateinit var repository: SessionRepository
    private val handler = Handler(Looper.getMainLooper())
    private val executor: Executor by lazy { mainExecutor }
    private var lastCaptureAt = 0L
    private var lastFingerprint: Long? = null
    private var osmWasForeground = false
    private var pendingCapture = false
    private var finishRunnable: Runnable? = null

    /*
     * V14: não dependemos mais apenas de eventos de acessibilidade.
     * Alguns aparelhos/ROMs não entregam TYPE_WINDOW_* de forma confiável para
     * jogos. O heartbeat consulta a janela ativa e mantém a captura viva.
     */
    private val heartbeat = object : Runnable {
        override fun run() {
            runCatching { pollForegroundAndCapture() }
                .onFailure { CollectorState.lastError = "Heartbeat: ${it.message ?: it.javaClass.simpleName}" }
            handler.postDelayed(this, 900L)
        }
    }

    override fun onServiceConnected() {
        repository = SessionRepository(applicationContext)
        getSharedPreferences("collector_runtime", MODE_PRIVATE)
            .edit()
            .putBoolean("accessibility_connected", true)
            .putLong("service_connected_at", System.currentTimeMillis())
            .apply()
        CollectorState.lastError = null
        CollectorState.setServiceReady(true)
        handler.removeCallbacks(heartbeat)
        handler.post(heartbeat)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString().orEmpty()
        if (pkg.isNotBlank()) {
            CollectorState.currentForegroundPackage = pkg
            getSharedPreferences("collector_runtime", MODE_PRIVATE).edit()
                .putString("last_foreground_package", pkg)
                .putLong("last_accessibility_event_at", System.currentTimeMillis())
                .apply()
            handleForeground(pkg)
        }
    }

    private fun pollForegroundAndCapture() {
        val pkg = detectForegroundPackage()
        if (!pkg.isNullOrBlank()) {
            CollectorState.currentForegroundPackage = pkg
            getSharedPreferences("collector_runtime", MODE_PRIVATE).edit()
                .putString("last_foreground_package", pkg)
                .putLong("last_heartbeat_at", System.currentTimeMillis())
                .apply()
            handleForeground(pkg)
        }
    }

    private fun detectForegroundPackage(): String? {
        rootInActiveWindow?.packageName?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        return runCatching {
            windows
                .asSequence()
                .sortedByDescending { if (it.isActive) 1 else 0 }
                .mapNotNull { it.root?.packageName?.toString() }
                .firstOrNull { it.isNotBlank() }
        }.getOrNull()
    }

    private fun handleForeground(pkg: String) {
        when (pkg) {
            OSM_PACKAGE -> {
                finishRunnable?.let(handler::removeCallbacks)
                finishRunnable = null
                ensureOsmSession()
                scheduleCapture()
            }
            packageName -> {
                if (osmWasForeground) scheduleFinish()
            }
            else -> {
                // Propaganda, navegador, Play Store, seletor de arquivos etc.
                // NÃO encerram a sessão. Quando o OSM voltar ao primeiro plano,
                // a captura continua na mesma sessão.
            }
        }
    }

    private fun ensureOsmSession() {
        val runtime = getSharedPreferences("collector_runtime", MODE_PRIVATE)
        val requestedAt = runtime.getLong("requested_session_at", 0L)
        val pending = runtime.getBoolean("start_new_session_pending", false)
        val current = repository.current()

        if (pending) {
            if (current == null || current.startedAt + 500L < requestedAt) {
                repository.beginNewSession()
            }
            lastFingerprint = null
            runtime.edit().putBoolean("start_new_session_pending", false).apply()
        } else if (current == null) {
            // Também funciona se o usuário abrir o OSM diretamente pelo ícone,
            // sem passar pelo botão "Abrir OSM" do Coach.
            repository.beginNewSession()
            lastFingerprint = null
        }

        osmWasForeground = true
        val now = System.currentTimeMillis()
        runtime.edit()
            .putBoolean("osm_seen_in_session", true)
            .putLong("last_osm_seen_at", now)
            .apply()
        CollectorState.setRecording(true)
    }

    private fun scheduleFinish() {
        finishRunnable?.let(handler::removeCallbacks)
        val task = Runnable {
            val pkg = detectForegroundPackage() ?: CollectorState.currentForegroundPackage
            if (pkg == packageName && osmWasForeground) {
                repository.finish()
                getSharedPreferences("collector_runtime", MODE_PRIVATE).edit()
                    .putBoolean("osm_seen_in_session", false)
                    .putLong("session_finished_at", System.currentTimeMillis())
                    .apply()
                osmWasForeground = false
                pendingCapture = false
                CollectorState.setRecording(false)
                CollectorState.signalSessionReady()
            }
        }
        finishRunnable = task
        handler.postDelayed(task, 900L)
    }

    private fun scheduleCapture() {
        if (pendingCapture) return
        val delay = maxOf(1500L - (System.currentTimeMillis() - lastCaptureAt), 120L)
        pendingCapture = true
        handler.postDelayed({
            pendingCapture = false
            captureNow()
        }, delay)
    }

    private fun visibleText(): String {
        val root = rootInActiveWindow ?: return ""
        val out = LinkedHashSet<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 20 || out.size >= 320) return
            n.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(out::add)
            n.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(out::add)
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        runCatching { walk(root, 0) }
        return out.joinToString(" | ").take(12000)
    }

    private fun captureNow() {
        val pkg = detectForegroundPackage() ?: CollectorState.currentForegroundPackage
        if (pkg != OSM_PACKAGE) return

        ensureOsmSession()
        val hint = visibleText()
        takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                buffer.close()
                if (hardware == null) {
                    CollectorState.lastError = "Falha de captura: bitmap"
                    scheduleCapture()
                    return
                }

                val bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false)
                hardware.recycle()
                val fp = BitmapFingerprint.aHash(bitmap)
                val old = lastFingerprint
                val now = System.currentTimeMillis()

                // Em jogos há animações pequenas. Para não gerar centenas de frames,
                // salva quando a tela muda OU a cada 8s como amostra de segurança.
                val forceSample = now - getSharedPreferences("collector_runtime", MODE_PRIVATE)
                    .getLong("last_saved_frame_at", 0L) >= 8000L

                if (old == null || BitmapFingerprint.distance(old, fp) > 2 || forceSample) {
                    repository.saveFrame(bitmap, fp, hint)
                    lastFingerprint = fp
                    getSharedPreferences("collector_runtime", MODE_PRIVATE).edit()
                        .putLong("last_saved_frame_at", now)
                        .putString("last_capture_title", ScreenClassifier.classify(hint).title.take(80))
                        .apply()
                    CollectorState.signalFrameCaptured()
                }

                lastCaptureAt = now
                CollectorState.lastError = null
                bitmap.recycle()
                if ((detectForegroundPackage() ?: CollectorState.currentForegroundPackage) == OSM_PACKAGE) {
                    scheduleCapture()
                }
            }

            override fun onFailure(errorCode: Int) {
                lastCaptureAt = System.currentTimeMillis()
                CollectorState.lastError = "Falha de captura: $errorCode"
                getSharedPreferences("collector_runtime", MODE_PRIVATE).edit()
                    .putString("last_capture_error", "screenshot:$errorCode")
                    .putLong("last_capture_error_at", lastCaptureAt)
                    .apply()
                if ((detectForegroundPackage() ?: CollectorState.currentForegroundPackage) == OSM_PACKAGE) {
                    scheduleCapture()
                }
            }
        })
    }

    override fun onInterrupt() {
        // Não finalizamos sessão apenas por interrupção temporária do serviço.
        CollectorState.setRecording(false)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::repository.isInitialized) repository.finish()
        osmWasForeground = false
        pendingCapture = false
        CollectorState.setRecording(false)
        CollectorState.setServiceReady(false)
        super.onDestroy()
    }
}
