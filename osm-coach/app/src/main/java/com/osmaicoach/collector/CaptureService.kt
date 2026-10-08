package com.osmaicoach.collector

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Só observa o OSM (packageNames no XML) e só tira screenshot enquanto existe uma sessão ativa.
 * Não clica, não toca, não executa nenhuma ação no jogo.
 */
class CaptureService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    companion object {
        @Volatile
        var instance: CaptureService? = null
    }

    override fun onServiceConnected() {
        instance = this
        Diag.serviceConnected = true
        Diag.lastError = null
        startLoop()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == OSM_PACKAGE) Diag.lastOsmEventAt = System.currentTimeMillis()
    }

    override fun onInterrupt() {
        Diag.lastError = "Serviço de acessibilidade interrompido pelo Android"
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Diag.serviceConnected = false
        instance = null
        loop?.cancel()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Diag.serviceConnected = false
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    private fun startLoop() {
        loop?.cancel()
        loop = scope.launch {
            val pipeline = FramePipeline.get(applicationContext)
            while (isActive) {
                val sid = Control.activeSession(applicationContext)
                if (sid == null) {
                    delay(1500L)
                    continue
                }
                try {
                    val bmp = takeShot()
                    if (bmp != null) {
                        try {
                            pipeline.onShot(sid, bmp)
                        } finally {
                            bmp.recycle()
                        }
                    }
                } catch (e: Exception) {
                    Diag.lastError = "Captura: " + (e.message ?: e.javaClass.simpleName)
                }
                delay(pipeline.nextDelayMs())
            }
        }
    }

    private suspend fun takeShot(): Bitmap? = suspendCancellableCoroutine { cont ->
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    val copy = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    buffer.close()
                    cont.resume(copy)
                }

                override fun onFailure(errorCode: Int) {
                    Diag.lastError = "Screenshot falhou (código $errorCode)"
                    cont.resume(null)
                }
            })
        } catch (e: Exception) {
            Diag.lastError = "Screenshot: " + (e.message ?: e.javaClass.simpleName)
            cont.resume(null)
        }
    }
}
