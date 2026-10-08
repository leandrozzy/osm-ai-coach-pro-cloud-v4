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
     * V16
     * Em alguns aparelhos/ROMs, jogos não aparecem como rootInActiveWindow e o
     * Android reporta com.android.systemui mesmo com o OSM visível. Portanto a
     * captura NÃO pode depender do nome do pacote para uma sessão iniciada pelo
     * botão "Abrir OSM". Enquanto a sessão explícita estiver armada, capturamos
     * a tela do display; anúncios também podem ser capturados e depois ignorados
     * pelo classificador, sem interromper a sessão.
     */
    private val heartbeat = object : Runnable {
        override fun run() {
            runCatching { pollForegroundAndCapture() }
                .onFailure { CollectorState.lastError = "Heartbeat: ${it.message ?: it.javaClass.simpleName}" }
            handler.postDelayed(this, 900L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = SessionRepository(applicationContext)
        getSharedPreferences("collector_runtime", MODE_PRIVATE).edit()
            .putLong("service_created_at", System.currentTimeMillis())
            .putBoolean("service_instance_alive", true)
            .apply()
    }

    override fun onServiceConnected() {
        if (!::repository.isInitialized) repository = SessionRepository(applicationContext)
        val now = System.currentTimeMillis()
        getSharedPreferences("collector_runtime", MODE_PRIVATE)
            .edit()
            .putBoolean("accessibility_connected", true)
            .putBoolean("service_instance_alive", true)
            .putLong("service_connected_at", now)
            .putLong("last_heartbeat_at", now)
            .apply()
        CollectorState.lastError = null
        CollectorState.setServiceReady(true)
        handler.removeCallbacks(heartbeat)
        handler.post(heartbeat)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString().orEmpty()
        val runtime = getSharedPreferences("collector_runtime", MODE_PRIVATE)
        runtime.edit()
            .putLong("last_accessibility_event_at", System.currentTimeMillis())
            .apply()

        if (pkg.isNotBlank()) {
            CollectorState.currentForegroundPackage = pkg
            runtime.edit().putString("last_foreground_package", pkg).apply()
            handleForeground(pkg)
        }

        // Sessão iniciada pelo Coach: evento de qualquer pacote serve apenas
        // como gatilho adicional. Não usamos o pacote como trava de captura.
        if (isExplicitSessionArmed()) {
            ensureOsmSession()
            scheduleCapture()
        }
    }

    private fun pollForegroundAndCapture() {
        val now = System.currentTimeMillis()
        val runtime = getSharedPreferences("collector_runtime", MODE_PRIVATE)
        runtime.edit()
            .putLong("last_heartbeat_at", now)
            .putBoolean("accessibility_connected", true)
            .putBoolean("service_instance_alive", true)
            .apply()

        val pkg = detectForegroundPackage()
        if (!pkg.isNullOrBlank()) {
            CollectorState.currentForegroundPackage = pkg
            runtime.edit().putString("last_foreground_package", pkg).apply()
            handleForeground(pkg)
        }

        // Caminho principal no seu aparelho: o sistema pode informar SystemUI
        // enquanto o OSM está na tela. A sessão explícita é a fonte de verdade.
        if (isExplicitSessionArmed()) {
            ensureOsmSession()
            scheduleCapture()
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

    private fun isExplicitSessionArmed(): Boolean {
        val runtime = getSharedPreferences("collector_runtime", MODE_PRIVATE)
        val requestedAt = runtime.getLong("requested_session_at", 0L)
        val launchAt = runtime.getLong("coach_launch_at", 0L)
        val current = repository.current()
        return requestedAt > 0L && launchAt > 0L && current != null && current.state == "recording"
    }

    private fun shouldKeepCapturing(): Boolean {
        if (isExplicitSessionArmed()) return true
        val pkg = detectForegroundPackage() ?: CollectorState.currentForegroundPackage
        return pkg == OSM_PACKAGE
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
                // Para sessão explícita, MainActivity finaliza ao voltar ao Coach.
                // Não finalizamos aqui porque alguns aparelhos reportam o pacote
                // incorreto e anúncios podem abrir janelas externas.
                if (osmWasForeground && !isExplicitSessionArmed()) scheduleFinish()
            }
            else -> {
                // Propaganda / SystemUI / navegador não encerram a sessão.
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
            if (pkg == packageName && osmWasForeground && !isExplicitSessionArmed()) {
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
        if (!shouldKeepCapturing()) return
        val delay = maxOf(900L - (System.currentTimeMillis() - lastCaptureAt), 90L)
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
        if (!shouldKeepCapturing()) return

        ensureOsmSession()
        val hint = visibleText()
        val runtime = getSharedPreferences("collector_runtime", MODE_PRIVATE)
        runtime.edit()
            .putLong("last_capture_attempt_at", System.currentTimeMillis())
            .putString("last_capture_attempt_package", detectForegroundPackage() ?: "NI")
            .apply()

        takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                buffer.close()
                if (hardware == null) {
                    CollectorState.lastError = "Falha de captura: bitmap"
                    runtime.edit()
                        .putString("last_capture_error", "bitmap-null")
                        .putLong("last_capture_error_at", System.currentTimeMillis())
                        .apply()
                    scheduleCapture()
                    return
                }

                val bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false)
                hardware.recycle()
                val fp = BitmapFingerprint.aHash(bitmap)
                val old = lastFingerprint
                val now = System.currentTimeMillis()

                val forceSample = now - runtime.getLong("last_saved_frame_at", 0L) >= 7000L

                if (old == null || BitmapFingerprint.distance(old, fp) > 2 || forceSample) {
                    repository.saveFrame(bitmap, fp, hint)
                    lastFingerprint = fp
                    runtime.edit()
                        .putLong("last_saved_frame_at", now)
                        .putString("last_capture_title", ScreenClassifier.classify(hint).title.take(80))
                        .remove("last_capture_error")
                        .apply()
                    CollectorState.signalFrameCaptured()
                }

                lastCaptureAt = now
                CollectorState.lastError = null
                bitmap.recycle()
                if (shouldKeepCapturing()) scheduleCapture()
            }

            override fun onFailure(errorCode: Int) {
                lastCaptureAt = System.currentTimeMillis()
                CollectorState.lastError = "Falha de captura: $errorCode"
                runtime.edit()
                    .putString("last_capture_error", "screenshot:$errorCode")
                    .putLong("last_capture_error_at", lastCaptureAt)
                    .apply()
                if (shouldKeepCapturing()) scheduleCapture()
            }
        })
    }

    override fun onInterrupt() {
        CollectorState.setRecording(false)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        osmWasForeground = false
        pendingCapture = false
        getSharedPreferences("collector_runtime", MODE_PRIVATE).edit()
            .putBoolean("accessibility_connected", false)
            .putBoolean("service_instance_alive", false)
            .putLong("service_destroyed_at", System.currentTimeMillis())
            .apply()
        CollectorState.setRecording(false)
        CollectorState.setServiceReady(false)
        super.onDestroy()
    }
}
