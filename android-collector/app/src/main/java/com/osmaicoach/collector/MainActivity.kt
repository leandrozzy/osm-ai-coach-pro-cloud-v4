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

class MainActivity : Activity() {
    private lateinit var repository: SessionRepository
    private lateinit var webView: WebView
    private lateinit var setupPanel: LinearLayout
    private lateinit var status: TextView
    private lateinit var enableButton: Button
    private lateinit var openOsmButton: Button

    private val stateListener: () -> Unit = { runOnUiThread { refreshState() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = SessionRepository(this)
        buildUi()
        CollectorState.addListener(stateListener)
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
        notifyWebCollectorChanged()
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

        setupPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 30, 32, 24)
            setBackgroundColor(Color.rgb(16, 27, 36))
        }

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
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

        if (BuildConfig.COACH_URL != "https://example.invalid") {
            webView.loadUrl(BuildConfig.COACH_URL)
        } else {
            webView.loadDataWithBaseURL(null, placeholderHtml(), "text/html", "UTF-8", null)
        }

        root.addView(
            setupPanel,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        root.addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
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

        OsmLauncher.open(this)
    }

    private fun refreshState() {
        val enabled = isAccessibilityServiceEnabled()
        CollectorState.setServiceReady(enabled)

        val text = when {
            !enabled -> "Leitura automática: DESATIVADA"
            CollectorState.isRecording() -> "Leitura automática: GRAVANDO OSM"
            else -> "Leitura automática: PRONTA"
        }

        enableButton.text = if (enabled) {
            "1. Leitura automática ATIVADA"
        } else {
            "1. Ativar leitura automática"
        }
        enableButton.isEnabled = !enabled
        openOsmButton.isEnabled = enabled

        status.text = text + (CollectorState.lastError?.let { "\n$it" } ?: "")
        notifyWebCollectorChanged()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, OsmCaptureAccessibilityService::class.java)
            .flattenToString()

        val accessibilityEnabled = runCatching {
            Settings.Secure.getInt(
                contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            ) == 1
        }.getOrDefault(false)

        if (!accessibilityEnabled) return false

        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices
            .split(':')
            .any { it.equals(expected, ignoreCase = true) }
    }

    private fun notifyWebCollectorChanged() {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('osm-collector-change'))",
            null
        )
    }

    private fun placeholderHtml() = """
        <!doctype html><html><meta name='viewport' content='width=device-width,initial-scale=1'>
        <body style='font-family:sans-serif;padding:24px'>
          <h2>OSM AI Coach Collector</h2>
          <p>O coletor Android está instalado. Falta apenas definir a URL de produção do Coach em <code>app/build.gradle.kts</code>.</p>
          <p>Ative a leitura uma vez e depois use <b>Abrir OSM e começar</b>.</p>
        </body></html>
    """.trimIndent()
}
