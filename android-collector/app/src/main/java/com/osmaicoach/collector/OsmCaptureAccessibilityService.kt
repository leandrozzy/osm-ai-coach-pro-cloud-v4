package com.osmaicoach.collector

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executor

class OsmCaptureAccessibilityService : AccessibilityService() {
    private lateinit var repository: SessionRepository
    private val handler = Handler(Looper.getMainLooper())
    private val executor: Executor by lazy { mainExecutor }
    private var lastCaptureAt = 0L
    private var lastFingerprint: Long? = null
    private var osmWasForeground = false
    private var pending = false

    override fun onServiceConnected() {
        repository = SessionRepository(applicationContext)
        CollectorState.setServiceReady(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        val nowOsm = pkg == OSM_PACKAGE

        if (nowOsm) {
            osmWasForeground = true
            repository.startIfNeeded()
            scheduleCapture()
            CollectorState.setRecording(true)
        } else if (osmWasForeground) {
            // Pequeno atraso evita encerrar durante diálogos do sistema/teclado.
            handler.postDelayed({
                if (CollectorState.currentForegroundPackage != OSM_PACKAGE) {
                    repository.finish()
                    osmWasForeground = false
                    CollectorState.setRecording(false)
                    CollectorState.signalSessionReady()
                }
            }, 900)
        }
        CollectorState.currentForegroundPackage = pkg
    }

    private fun scheduleCapture() {
        if (pending) return
        val elapsed = System.currentTimeMillis() - lastCaptureAt
        val delay = (850L - elapsed).coerceAtLeast(120L)
        pending = true
        handler.postDelayed({ pending = false; captureNow() }, delay)
    }

    private fun captureNow() {
        if (CollectorState.currentForegroundPackage != OSM_PACKAGE) return
        takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val buffer = screenshot.hardwareBuffer
                val colorSpace = screenshot.colorSpace
                val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                buffer.close()
                if (hardwareBitmap == null) return
                val bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                hardwareBitmap.recycle()
                val fingerprint = BitmapFingerprint.aHash(bitmap)
                val previous = lastFingerprint
                // Distância <= 3 = tela visualmente quase igual, não salva novamente.
                if (previous == null || BitmapFingerprint.distance(previous, fingerprint) > 3) {
                    repository.saveFrame(bitmap, fingerprint)
                    lastFingerprint = fingerprint
                    lastCaptureAt = System.currentTimeMillis()
                    CollectorState.signalFrameCaptured()
                }
                bitmap.recycle()
            }

            override fun onFailure(errorCode: Int) {
                CollectorState.lastError = "Falha de captura: $errorCode"
            }
        })
    }

    override fun onInterrupt() {
        CollectorState.setServiceReady(false)
        CollectorState.setRecording(false)
    }

    override fun onDestroy() {
        CollectorState.setServiceReady(false)
        CollectorState.setRecording(false)
        super.onDestroy()
    }
}
