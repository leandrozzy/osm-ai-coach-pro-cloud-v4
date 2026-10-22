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
        startWatchdog()
    }

    private var watchdog: kotlinx.coroutines.Job? = null

    /**
     * Alguns celulares (ASUS/ROG, Xiaomi) apagam os alarmes quando o app é deslizado para fora dos recentes. Este
     * serviço o sistema religa sozinho: aqui os alarmes são conferidos a cada minuto e refeitos (e os avisos que
     * ficaram atrasados saem na hora).
     */
    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try { Notifier.reschedule(applicationContext) } catch (e: Exception) { Diag.lastError = "Avisos: " + (e.message ?: e.javaClass.simpleName) }
                delay(60_000L)
            }
        }
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
        watchdog?.cancel()
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
                // O próprio OSM Coach na tela: não é o jogo. Antes as abas Tática/Elenco/Resumo do app eram lidas como
                // "Relatório"/"Tática"/"Estádio" do OSM e podiam gravar lixo nos slots.
                if (AppVisible.resumed || rootInActiveWindow?.packageName?.toString() == packageName) {
                    delay(1200L)
                    continue
                }
                try {
                    // Os botões somem por um instante na hora da foto: o OCR lê o texto do jogo que estava atrás
                    // deles (antes lia "Encerrar"/"App" colado nos nomes, estatísticas e notas).
                    val hid = withContext(Dispatchers.Main) {
                        val b = overlayBox
                        if (b != null && b.alpha > 0f) { b.alpha = 0f; true } else false
                    }
                    if (hid) delay(60L)
                    val bmp = try { takeShot() } finally {
                        if (hid) withContext(Dispatchers.Main) { overlayBox?.alpha = 0.95f }
                    }
                    if (bmp != null) {
                        Overlay.shotClean = hid
                        try {
                            pipeline.onShot(sid, bmp)
                        } finally {
                            Overlay.shotClean = false
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
    private var cardView: LinearLayout? = null
    private var listView: LinearLayout? = null
    private var headView: TextView? = null
    private var badgeView: TextView? = null
    private var overlayLp: WindowManager.LayoutParams? = null
    private var hintJob: Job? = null
    private var hintKey = ""
    private var hints: List<Hints.Hint> = emptyList()
    private var hintSlot = 0
    private var hintType = ""

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

    /** Texto da tática do slot atual (ou do próximo jogo, se o slot não foi reconhecido). */
    private suspend fun tacticText(): String = try {
        val repo = Repo(applicationContext)
        val now = System.currentTimeMillis()
        val slot = Diag.currentSlot.takeIf { it > 0 } ?: (1..4).mapNotNull { s ->
            val p = repo.dao.plan(s, "tactic") ?: return@mapNotNull null
            val j = runCatching { org.json.JSONObject(p.json) }.getOrNull() ?: return@mapNotNull null
            val f = repo.fieldMap(s)
            if (!Director.tacticValid(j, p.at, f, now)) return@mapNotNull null
            Pair(s, f[K.MATCH_AT]?.value?.toLongOrNull() ?: Long.MAX_VALUE)
        }.minByOrNull { it.second }?.first ?: 0
        if (slot <= 0) "Entre no slot (pré-jogo) para eu saber qual tática mostrar."
        else {
            val p = repo.dao.plan(slot, "tactic")
            val j = p?.json?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
            if (p == null || j == null) "S$slot: nenhuma tática gerada. Gere no app (aba Tática)."
            else {
                val ok = Director.tacticValid(j, p.at, repo.fieldMap(slot), now)
                val ch = rivalChangesOf(repo, slot)
                val warn = if (ch.isNotEmpty()) "⚠ O rival mudou: " + RivalWatch.headline(ch) + ". Gere uma nova tática.\n" else ""
                "S$slot • vs ${j.optString("rival")}\n" + (if (ok) "" else "⚠ Tática de outro jogo: gere de novo.\n") + warn + Director.tacticSummary(j)
            }
        }
    } catch (e: Exception) {
        "Não consegui carregar a tática: " + (e.message ?: e.javaClass.simpleName)
    }

    private fun onTacticTap() {
        scope.launch {
            val t = tacticText()
            withContext(Dispatchers.Main) { showCard(listOf(Hints.Hint("📋", "Tática", t)), "Tática do jogo", allowDone = false) }
        }
    }

    private fun removeOverlay() {
        val v = overlayBox ?: return
        hintJob?.cancel()
        hintJob = null
        hintKey = ""
        Overlay.bounds = null
        overlayBox = null
        overlay = null
        toggleView = null
        noticeView = null
        cardView = null
        listView = null
        headView = null
        badgeView = null
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

    /** Monta a lista do cartão: cada dica com ícone, título, detalhe e o "✓ feito". */
    private fun showCard(list: List<Hints.Hint>, head: String, allowDone: Boolean = true) {
        val card = cardView ?: return
        val lv = listView ?: return
        val dp = resources.displayMetrics.density
        headView?.text = head
        lv.removeAllViews()
        if (list.isEmpty()) {
            lv.addView(TextView(this).apply {
                text = "✅ Nada pendente."
                setTextColor(0xFFB8C7E6.toInt()); textSize = 12f
                setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            })
        }
        for (h in list.take(6)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
                background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(0x33FFFFFF) }
            }
            row.addView(TextView(this).apply { text = h.icon; textSize = 18f; setPadding(0, 0, (8 * dp).toInt(), 0) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = h.title; setTextColor(android.graphics.Color.WHITE); textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            col.addView(TextView(this).apply { text = h.detail; setTextColor(0xFFB8C7E6.toInt()); textSize = 11f; maxLines = if (allowDone) 3 else 30 })
            row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (allowDone) {
                val done = TextView(this).apply {
                    text = "✓"; textSize = 16f; setTextColor(0xFF22C55E.toInt()); gravity = Gravity.CENTER
                    setPadding((10 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt())
                    setOnClickListener {
                        Hints.dismiss(applicationContext, h.title)
                        hints = hints.filter { it.title != h.title }
                        lv.removeView(row)
                        updateBadge()
                    }
                }
                row.addView(done)
            }
            lv.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (6 * dp).toInt() })
        }
        card.visibility = View.VISIBLE
        overlayBox?.post { overlayBox?.let { markBounds(it) } }
    }

    private fun updateBadge() {
        val b = badgeView ?: return
        val n = hints.count { it.action }
        b.text = n.toString()
        b.visibility = if (n > 0) View.VISIBLE else View.GONE
    }

    /** Recalcula o que fazer quando a tela/slot muda ou chega leitura nova; dica que sumiu = feito. */
    private fun startHints() {
        if (hintJob != null) return
        hintJob = scope.launch {
            while (isActive) {
                val type = Diag.currentType
                val slot = Diag.currentSlot
                val key = "$type|$slot|${Diag.lastUpdateAt}"
                if (key != hintKey && type != ScreenType.NON_OSM.name) {
                    hintKey = key
                    val st = runCatching { ScreenType.valueOf(type) }.getOrDefault(ScreenType.OTHER_OSM)
                    val fresh = runCatching { Hints.forScreen(applicationContext, slot, st) }.getOrDefault(emptyList())
                    val sameScreen = slot == hintSlot && type == hintType
                    val gone = if (sameScreen) hints.filter { o -> fresh.none { it.title == o.title } && o.icon != "📋" } else emptyList()
                    hints = fresh
                    hintSlot = slot
                    hintType = type
                    withContext(Dispatchers.Main) {
                        updateBadge()
                        if (gone.isNotEmpty()) notice("✅ Feito: " + gone.first().title)
                        if (cardView?.visibility == View.VISIBLE && headView?.text?.startsWith("Tática") != true) {
                            showCard(hints, "S$slot • " + Hints.screenLabel(st))
                        }
                    }
                }
                delay(1500L)
            }
        }
    }

    /**
     * Bola por cima do jogo (como os apps de coach): mostra quantas coisas há para fazer na tela aberta e, ao
     * tocar, o que fazer (treinar tal, vender tal por X, melhorar o estádio, a tática). Arrastável; gruda na
     * borda. App / Tática / Encerrar ficam dentro do cartão.
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
        val sw = resources.displayMetrics.widthPixels
        val bubbleSize = (58 * dp).toInt()
        val maxW = minOf((340 * dp).toInt(), (sw * 0.78f).toInt())

        // bola: escudo do app num círculo azul com borda dourada + contador vermelho
        val bubble = android.widget.FrameLayout(this)
        val ring = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF2F7BFF.toInt(), 0xFF0B2A6B.toInt())).apply {
                shape = GradientDrawable.OVAL
                setStroke((2.5f * dp).toInt(), 0xFFFFD24A.toInt())
            }
            elevation = 8 * dp
        }
        bubble.addView(ring, android.widget.FrameLayout.LayoutParams(bubbleSize, bubbleSize))
        val icon = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_fg)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            elevation = 9 * dp
        }
        bubble.addView(icon, android.widget.FrameLayout.LayoutParams((bubbleSize * 0.9f).toInt(), (bubbleSize * 0.9f).toInt(), Gravity.CENTER))
        val badge = TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE); textSize = 11f; gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFE5484D.toInt()); setStroke((1.5f * dp).toInt(), android.graphics.Color.WHITE) }
            elevation = 10 * dp
            visibility = View.GONE
        }
        bubble.addView(badge, android.widget.FrameLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt(), Gravity.TOP or Gravity.END))

        // cartão: título, lista de dicas e os botões App / Tática / Encerrar
        val head = TextView(this).apply {
            setTextColor(0xFFFFD24A.toInt()); textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val end = chip("■ Encerrar", 0xE6D32F2F.toInt(), dp)
        val toggle = chip("📱 App", 0xE61F6BFF.toInt(), dp)
        val tactic = chip("📋 Tática", 0xE6B8860B.toInt(), dp)
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            fun lp() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = (6 * dp).toInt() }
            addView(toggle, lp()); addView(tactic, lp()); addView(end, lp())
        }
        val scroll = android.widget.ScrollView(this).apply { addView(list) }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xF2132A5C.toInt(), 0xF20B1530.toInt())).apply {
                cornerRadius = 18 * dp
                setStroke((1 * dp).toInt(), 0x6672B8FF)
            }
            elevation = 12 * dp
            addView(head)
            addView(scroll, LinearLayout.LayoutParams(maxW, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (4 * dp).toInt() })
            addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (10 * dp).toInt() })
            visibility = View.GONE
        }
        // a lista não passa de ~55% da tela (o resto rola)
        scroll.layoutParams = LinearLayout.LayoutParams(maxW, LinearLayout.LayoutParams.WRAP_CONTENT)
        scroll.viewTreeObserver.addOnGlobalLayoutListener {
            val cap = (resources.displayMetrics.heightPixels * 0.55f).toInt()
            if (scroll.height > cap) scroll.layoutParams = scroll.layoutParams.apply { height = cap }
        }
        val notice = chip("", 0xF2FFB300.toInt(), dp).apply {
            setTextColor(android.graphics.Color.BLACK)
            maxWidth = maxW
            visibility = View.GONE
        }
        fun gap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (6 * dp).toInt() }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            alpha = 0.97f
            addView(bubble, LinearLayout.LayoutParams(bubbleSize + (6 * dp).toInt(), bubbleSize + (6 * dp).toInt()))
            addView(card, gap())
            addView(notice, gap())
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (resources.displayMetrics.heightPixels * 0.30f).toInt()
        }
        overlayLp = lp
        fun dragOrTap(v: View, snap: Boolean, onTap: () -> Unit) {
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
                            lp.x = (startX + if (lp.gravity and Gravity.END == Gravity.END) -dx.toInt() else dx.toInt()).coerceAtLeast(0)
                            lp.y = (startY + dy.toInt()).coerceAtLeast(0)
                            try { wm.updateViewLayout(box, lp) } catch (ex: Exception) { }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) onTap()
                        else if (snap) {
                            // gruda na borda mais perto (esquerda ou direita), como as bolhas de chat
                            val right = e.rawX > sw / 2f
                            lp.gravity = Gravity.TOP or (if (right) Gravity.END else Gravity.START)
                            lp.x = 0
                            box.gravity = if (right) Gravity.END else Gravity.START
                            try { wm.updateViewLayout(box, lp) } catch (ex: Exception) { }
                        }
                        box.post { markBounds(box) }
                    }
                }
                true
            }
        }
        dragOrTap(bubble, snap = true) {
            if (card.visibility == View.VISIBLE) {
                card.visibility = View.GONE
                box.post { markBounds(box) }
            } else {
                val st = runCatching { ScreenType.valueOf(Diag.currentType) }.getOrDefault(ScreenType.OTHER_OSM)
                val slot = Diag.currentSlot
                showCard(hints, if (slot > 0) "S$slot • " + Hints.screenLabel(st) else "Entre num slot do OSM")
            }
        }
        dragOrTap(end, snap = false) { onOverlayTap() }
        dragOrTap(toggle, snap = false) { onToggleTap() }
        dragOrTap(tactic, snap = false) { onTacticTap() }
        dragOrTap(notice, snap = false) { notice.visibility = View.GONE }
        box.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> markBounds(v) }
        try {
            wm.addView(box, lp)
            overlayBox = box
            overlay = end
            toggleView = toggle
            noticeView = notice
            cardView = card
            listView = list
            headView = head
            badgeView = badge
            updateToggle()
            startHints()
        } catch (e: Exception) {
            Diag.lastError = "Botão flutuante: " + (e.message ?: e.javaClass.simpleName)
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
