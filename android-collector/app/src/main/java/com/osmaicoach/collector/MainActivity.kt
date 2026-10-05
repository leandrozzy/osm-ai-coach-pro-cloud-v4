package com.osmaicoach.collector

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var repository: SessionRepository
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var sessionStatus: TextView
    private lateinit var enableButton: Button
    private lateinit var openOsmButton: Button

    private val stateListener: () -> Unit = {
        runOnUiThread {
            refreshState()
            refreshSessionSummary()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = SessionRepository(this)
        buildUi()
        CollectorState.addListener(stateListener)
        refreshState()
        refreshSessionSummary()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
        refreshSessionSummary()
        window.decorView.postDelayed({
            refreshSessionSummary()
            notifyWebCollectorChanged()
        }, 1400)
    }

    override fun onDestroy() {
        CollectorState.removeListener(stateListener)
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("OsmCollector")
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 27, 36))
        }

        val setupPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 18)
            setBackgroundColor(Color.rgb(16, 27, 36))
        }

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
        }

        sessionStatus = TextView(this).apply {
            setTextColor(Color.rgb(180, 220, 190))
            textSize = 14f
            setPadding(0, 10, 0, 10)
        }

        enableButton = Button(this).apply {
            text = "1. Ativar leitura automática"
            setOnClickListener {
                if (!isAccessibilityServiceEnabled()) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } else {
                    refreshState()
                }
            }
        }

        openOsmButton = Button(this).apply {
            text = "2. Abrir OSM e começar"
            setOnClickListener { openOsmFromBridge() }
        }

        setupPanel.addView(status)
        setupPanel.addView(sessionStatus)
        setupPanel.addView(enableButton)
        setupPanel.addView(openOsmButton)

        webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            addJavascriptInterface(CoachBridge(this@MainActivity, repository), "OsmCollector")
        }

        webView.loadUrl(BuildConfig.COACH_URL)

        root.addView(setupPanel, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        root.addView(webView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    fun openOsmFromBridge() {
        val enabled = isAccessibilityServiceEnabled()
        CollectorState.setServiceReady(enabled)
        if (!enabled) {
            status.text = "Ative primeiro o serviço 'OSM AI Coach — leitura automática'."
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        sessionStatus.text = "Sessão OSM iniciando… navegue normalmente pelos seus slots."
        OsmLauncher.open(this)
    }

    private fun refreshState() {
        val enabled = isAccessibilityServiceEnabled()
        CollectorState.setServiceReady(enabled)
        status.text = when {
            !enabled -> "Leitura automática: DESATIVADA"
            CollectorState.isRecording() -> "Leitura automática: GRAVANDO OSM"
            else -> "Leitura automática: PRONTA"
        } + (CollectorState.lastError?.let { "\n$it" } ?: "")
        enableButton.text = if (enabled) "1. Leitura automática ATIVADA" else "1. Ativar leitura automática"
        enableButton.isEnabled = !enabled
        openOsmButton.isEnabled = enabled
    }

    private fun refreshSessionSummary() {
        val raw = runCatching { repository.latestSessionJson() }.getOrDefault("null")
        if (raw == "null") {
            sessionStatus.text = if (CollectorState.isRecording()) "Capturando telas do OSM…" else "Nenhuma sessão concluída ainda."
            return
        }
        runCatching {
            val json = JSONObject(raw)
            val frames = json.optJSONArray("frames")?.length() ?: 0
            val ready = json.optString("state") == "ready"
            sessionStatus.text = if (ready) {
                "✓ Sessão concluída: $frames tela(s) capturada(s). Coach conectado abaixo."
            } else {
                "Sessão em andamento: $frames tela(s) capturada(s)."
            }
        }.onFailure {
            sessionStatus.text = "Sessão encontrada, mas não foi possível ler o resumo."
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, OsmCaptureAccessibilityService::class.java).flattenToString()
        val accessibilityEnabled = runCatching {
            Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        }.getOrDefault(false)
        if (!accessibilityEnabled) return false
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun notifyWebCollectorChanged() {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('osm-collector-change'))", null)
    }
}
