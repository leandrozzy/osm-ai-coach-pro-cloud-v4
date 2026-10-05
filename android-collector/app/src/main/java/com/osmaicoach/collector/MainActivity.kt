package com.osmaicoach.collector

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
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
        webView.removeJavascriptInterface("OsmCollector")
        webView.destroy()
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
        val enable = Button(this).apply {
            text = "1. Ativar leitura automática"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val openOsm = Button(this).apply {
            text = "2. Abrir OSM e começar"
            setOnClickListener { openOsmFromBridge() }
        }
        setupPanel.addView(status)
        setupPanel.addView(enable)
        setupPanel.addView(openOsm)

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

        root.addView(setupPanel, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        root.addView(webView, LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        setContentView(root)
    }

    fun openOsmFromBridge() {
        if (!CollectorState.isServiceReady()) {
            status.text = "Ative primeiro o serviço 'OSM AI Coach — leitura automática'."
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        OsmLauncher.open(this)
    }

    private fun refreshState() {
        val text = when {
            !CollectorState.isServiceReady() -> "Leitura automática: DESATIVADA"
            CollectorState.isRecording() -> "Leitura automática: GRAVANDO OSM"
            else -> "Leitura automática: PRONTA"
        }
        status.text = text + (CollectorState.lastError?.let { "\n$it" } ?: "")
        notifyWebCollectorChanged()
    }

    private fun notifyWebCollectorChanged() {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('osm-collector-change'))", null
        )
    }

    private fun placeholderHtml() = """
        <!doctype html><html><meta name='viewport' content='width=device-width,initial-scale=1'>
        <body style='font-family:sans-serif;padding:24px'>
          <h2>OSM AI Coach Collector</h2>
          <p>O coletor Android está instalado. Falta apenas definir a URL de produção do Coach em <code>app/build.gradle.kts</code>.</p>
          <p>Você já pode ativar a leitura acima e testar a captura abrindo o OSM.</p>
        </body></html>
    """.trimIndent()
}
