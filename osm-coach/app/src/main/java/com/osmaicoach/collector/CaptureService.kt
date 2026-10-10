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
import android.widget.LinearLayout
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
        Health.install(applicationContext)
        Health.beat(applicationContext)
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
                Health.beat(applicationContext)
                val sid = Control.activeSession(applicationContext)
                // Botão "Encerrar" por cima do jogo enquanto captura (some quando o próprio app está na tela).
                withContext(Dispatchers.Main) { syncOverlay(sid != null) }
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

    private var overlayBox: LinearLayout? = null
    private var toggleView: TextView? = null
    private var noticeView: TextView? = null
    private var panelView: TextView? = null

    /** Aviso curto por cima do OSM (ex.: tática montada diferente da gerada). Some sozinho. */
    fun notice(text: String) {
        mainExecutor.execute {
            val tv = noticeView ?: return@execute
            tv.text = text
            tv.visibility = View.VISIBLE
            tv.removeCallbacks(hideNotice)
            tv.postDelayed(hideNotice, 10000L)
        }
    }

    /** Guarda onde os botões estão na tela para o OCR não ler o próprio painel do app. */
    private fun markBounds(v: View) {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val sw = resources.displayMetrics.widthPixels.toFloat()
        val sh = resources.displayMetrics.heightPixels.toFloat()
        if (sw <= 0f || sh <= 0f) return
        Overlay.bounds = floatArrayOf(loc[0] / sw, loc[1] / sh, (loc[0] + v.width) / sw, (loc[1] + v.height) / sh)
    }

    private val hideNotice = Runnable { noticeView?.visibility = View.GONE }

    /** Painel com a tática do slot atual, para montar no OSM sem trocar de app. */
    private fun onTacticTap() {
        val pv = panelView ?: return
        if (pv.visibility == View.VISIBLE) {
            pv.visibility = View.GONE
            return
        }
        val app = applicationContext
        scope.launch {
            val text = try {
                val slot = Diag.currentSlot
                if (slot <= 0) "Entre no slot (pré-jogo) para eu saber qual tática mostrar."
                else {
                    val repo = Repo(app)
                    val p = repo.dao.plan(slot, "tactic")
                    val j = p?.json?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
                    if (p == null || j == null) "S$slot: nenhuma tática gerada. Gere no app (aba Tática)."
                    else {
                        val ok = Director.tacticValid(j, p.at, repo.fieldMap(slot), System.currentTimeMillis())
                        "S$slot • vs ${j.optString("rival")}\n" + (if (ok) "" else "⚠ Tática de outro jogo: gere de novo.\n") + Director.tacticSummary(j)
                    }
                }
            } catch (e: Exception) {
                "Não consegui carregar a tática: " + (e.message ?: e.javaClass.simpleName)
            }
            withContext(Dispatchers.Main) {
                panelView?.text = text + "\n(toque para fechar)"
                panelView?.visibility = View.VISIBLE
            }
        }
    }

    private fun removeOverlay() {
        val v = overlayBox ?: return
        Overlay.bounds = null
        overlayBox = null
        overlay = null
        toggleView = null
        noticeView = null
        panelView = null
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Exception) {
            // a janela já foi removida pelo sistema
        }
    }

    /** Chamado pela tela do app ao abrir/fechar: troca o rótulo do botão de alternar na hora. */
    fun refreshOverlay() {
        mainExecutor.execute { updateToggle() }
    }

    private fun updateToggle() {
        toggleView?.text = if (AppVisible.resumed) "⚽ OSM" else "📱 App"
    }

    private fun chip(text: String, color: Int, dp: Float): TextView = TextView(this).apply {
        this.text = text
        setTextColor(android.graphics.Color.WHITE)
        textSize = 12f
        setPadding((12 * dp).toInt(), (7 * dp).toInt(), (12 * dp).toInt(), (7 * dp).toInt())
        background = GradientDrawable().apply {
            cornerRadius = 40 * dp
            setColor(color)
        }
    }

    /**
     * Botões por cima de tudo enquanto captura: alternar OSM <-> app e Encerrar (toque duplo para confirmar).
     * Arrastáveis pela borda da tela.
     */
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
            updateToggle()
            return
        }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density
        val end = chip("■ Encerrar", 0xE6D32F2F.toInt(), dp)
        val toggle = chip("📱 App", 0xE61F6BFF.toInt(), dp)
        val tactic = chip("📋 Tática", 0xE6B8860B.toInt(), dp)
        val maxW = (resources.displayMetrics.widthPixels * 0.72f).toInt()
        val notice = chip("", 0xF2FFB300.toInt(), dp).apply {
            setTextColor(android.graphics.Color.BLACK)
            maxWidth = maxW
            visibility = View.GONE
        }
        val panel = TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 12f
            maxWidth = maxW
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp
                setColor(0xF20B1220.toInt())
            }
            visibility = View.GONE
        }
        fun gap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (6 * dp).toInt() }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            alpha = 0.95f
            addView(toggle)
            addView(tactic, gap())
            addView(end, gap())
            addView(notice, gap())
            addView(panel, gap())
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (resources.displayMetrics.heightPixels * 0.40f).toInt()
        }
        fun dragOrTap(v: View, onTap: () -> Unit) {
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var moved = false
            v.setOnTouchListener { _: View, e: MotionEvent ->
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
                            try { wm.updateViewLayout(box, lp) } catch (ex: Exception) { }
                            box.post { markBounds(box) }
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) onTap()
                }
                true
            }
        }
        dragOrTap(end) { onOverlayTap() }
        dragOrTap(toggle) { onToggleTap() }
        dragOrTap(tactic) { onTacticTap() }
        dragOrTap(panel) { panel.visibility = View.GONE }
        dragOrTap(notice) { notice.visibility = View.GONE }
        box.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> markBounds(v) }
        try {
            wm.addView(box, lp)
            overlayBox = box
            overlay = end
            toggleView = toggle
            noticeView = notice
            panelView = panel
            updateToggle()
        } catch (e: Exception) {
            Diag.lastError = "Botões flutuantes: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    /** No app vai para o OSM; no OSM (ou em outro lugar) volta para o app. A captura continua. */
    private fun onToggleTap() {
        try {
            if (AppVisible.resumed) {
                val i = packageManager.getLaunchIntentForPackage(OSM_PACKAGE) ?: return
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            } else {
                startActivity(
                    android.content.Intent(this, MainActivity::class.java).addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                )
            }
        } catch (e: Exception) {
            Diag.lastError = "Alternar app/OSM: " + (e.message ?: e.javaClass.simpleName)
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
