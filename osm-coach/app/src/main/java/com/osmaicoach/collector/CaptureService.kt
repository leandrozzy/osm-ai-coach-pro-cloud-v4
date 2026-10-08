package com.osmaicoach.collector

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import android.widget.Toast
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
import kotlinx.coroutines.withContext

/**
 * Só observa o OSM (packageNames no XML) e só tira screenshot enquanto existe uma sessão ativa.
 * Não clica, não toca, não executa nenhuma ação no jogo.
 */
class CaptureService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var overlay: TextView? = null
    private var armedAt = 0L

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
        removeOverlay()
        Diag.serviceConnected = false
        instance = null
        loop?.cancel()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeOverlay()
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
                // Botão "Encerrar" por cima do jogo enquanto captura (some quando o próprio app está na tela).
                withContext(Dispatchers.Main) { syncOverlay(sid != null && !AppVisible.resumed) }
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

    private fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Exception) {
            // a janela já foi removida pelo sistema
        }
    }

    /** Chip arrastável na borda esquerda. 1º toque arma ("toque de novo"), 2º toque em até 4 s encerra. */
    @SuppressLint("ClickableViewAccessibility")
    private fun syncOverlay(show: Boolean) {
        if (!show) {
            removeOverlay()
            return
        }
        val cur = overlay
        if (cur != null) {
            if (armedAt > 0L && System.currentTimeMillis() - armedAt > 4000L) {
                armedAt = 0L
                cur.text = "■ Encerrar"
            }
            return
        }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density
        val tv = TextView(this).apply {
            text = "■ Encerrar"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 12f
            setPadding((12 * dp).toInt(), (7 * dp).toInt(), (12 * dp).toInt(), (7 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 40 * dp
                setColor(0xE6D32F2F.toInt())
            }
            alpha = 0.9f
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (resources.displayMetrics.heightPixels * 0.42f).toInt()
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        tv.setOnTouchListener { v: View, e: MotionEvent ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (kotlin.math.abs(dx) > 12 * dp || kotlin.math.abs(dy) > 12 * dp) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        try { wm.updateViewLayout(v, lp) } catch (ex: Exception) { }
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) onOverlayTap()
            }
            true
        }
        try {
            wm.addView(tv, lp)
            overlay = tv
        } catch (e: Exception) {
            Diag.lastError = "Botão Encerrar: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun onOverlayTap() {
        val tv = overlay ?: return
        val now = System.currentTimeMillis()
        if (armedAt == 0L || now - armedAt > 4000L) {
            armedAt = now
            tv.text = "Toque de novo para encerrar"
            return
        }
        armedAt = 0L
        removeOverlay()
        AppScope.scope.launch { Control.end(applicationContext) }
        Toast.makeText(this, "Captura encerrada. Processando em segundo plano…", Toast.LENGTH_SHORT).show()
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
