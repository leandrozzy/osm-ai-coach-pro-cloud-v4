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
    private var pendingCapture = false
    private var finishRunnable: Runnable? = null

    override fun onServiceConnected() {
        repository = SessionRepository(applicationContext)
        CollectorState.lastError = null
        CollectorState.setServiceReady(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        val nowOsm = pkg == OSM_PACKAGE
        CollectorState.currentForegroundPackage = pkg

        if (nowOsm) {
            finishRunnable?.let(handler::removeCallbacks)
            finishRunnable = null

            osmWasForeground = true
            repository.startIfNeeded()
            CollectorState.setRecording(true)
            scheduleCapture()
            return
        }

        if (osmWasForeground) {
            scheduleFinish()
        }
    }

    private fun scheduleFinish() {
        finishRunnable?.let(handler::removeCallbacks)

        val task = Runnable {
            if (CollectorState.currentForegroundPackage != OSM_PACKAGE && osmWasForeground) {
                repository.finish()
                osmWasForeground = false
                pendingCapture = false
                CollectorState.setRecording(false)
                CollectorState.signalSessionReady()
            }
        }

        finishRunnable = task
        handler.postDelayed(task, 1200L)
    }

    private fun scheduleCapture(extraDelay: Long = 0L) {
        if (pendingCapture) return

        // AccessibilityService.takeScreenshot exige intervalo mínimo entre capturas.
        val elapsed = System.currentTimeMillis() - lastCaptureAt
        val minInterval = 1250L
        val delay = maxOf(minInterval - elapsed, 180L, extraDelay)

        pendingCapture = true
        handler.postDelayed({
            pendingCapture = false
            captureNow()
        }, delay)
    }

    private fun captureNow() {
        if (CollectorState.currentForegroundPackage != OSM_PACKAGE) return

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            executor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    val colorSpace = screenshot.colorSpace
                    val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                    buffer.close()

                    if (hardwareBitmap == null) {
                        CollectorState.lastError = "Falha de captura: bitmap indisponível"
                        return
                    }

                    val bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                    hardwareBitmap.recycle()

                    val fingerprint = BitmapFingerprint.aHash(bitmap)
                    val previous = lastFingerprint

                    if (previous == null || BitmapFingerprint.distance(previous, fingerprint) > 3) {
                        repository.saveFrame(bitmap, fingerprint)
                        lastFingerprint = fingerprint
                    }

                    lastCaptureAt = System.currentTimeMillis()
                    CollectorState.lastError = null
                    CollectorState.signalFrameCaptured()
                    bitmap.recycle()
                }

                override fun onFailure(errorCode: Int) {
                    if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        // O Android pediu mais intervalo; tenta novamente sem marcar falha permanente.
                        lastCaptureAt = System.currentTimeMillis()
                        scheduleCapture(1350L)
                        return
                    }

                    CollectorState.lastError = "Falha de captura: $errorCode"
                    CollectorState.signalFrameCaptured()
                }
            }
        )
    }

    override fun onInterrupt() {
        finishRunnable?.let(handler::removeCallbacks)
        repository.finish()
        osmWasForeground = false
        pendingCapture = false
        CollectorState.setRecording(false)
        CollectorState.setServiceReady(false)
    }

    override fun onDestroy() {
        finishRunnable?.let(handler::removeCallbacks)
        repository.finish()
        osmWasForeground = false
        pendingCapture = false
        CollectorState.setRecording(false)
        CollectorState.setServiceReady(false)
        super.onDestroy()
    }
}
