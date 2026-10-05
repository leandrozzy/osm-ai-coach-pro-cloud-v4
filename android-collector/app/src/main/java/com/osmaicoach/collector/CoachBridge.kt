package com.osmaicoach.collector

import android.content.Context
import android.webkit.JavascriptInterface

class CoachBridge(
    private val activity: MainActivity,
    private val repository: SessionRepository
) {
    private val prefs by lazy {
        activity.getSharedPreferences("coach_native_store", Context.MODE_PRIVATE)
    }

    @JavascriptInterface
    fun startOsmSession(): String {
        activity.runOnUiThread { activity.openOsmFromBridge() }
        return "{\"ok\":true}"
    }

    @JavascriptInterface
    fun collectorStatus(): String = org.json.JSONObject().apply {
        put("serviceReady", CollectorState.isServiceReady())
        put("recording", CollectorState.isRecording())
        put("lastError", CollectorState.lastError ?: org.json.JSONObject.NULL)
    }.toString()

    @JavascriptInterface
    fun latestSession(): String = repository.latestSessionJson()

    @JavascriptInterface
    fun latestFrameBase64(index: Int): String = repository.latestFrameBase64(index) ?: ""

    @JavascriptInterface
    fun saveCoachState(json: String): Boolean = runCatching {
        require(json.length <= 8_000_000)
        prefs.edit().putString("coach_state_v1", json).commit()
    }.getOrDefault(false)

    @JavascriptInterface
    fun loadCoachState(): String = prefs.getString("coach_state_v1", "") ?: ""

    @JavascriptInterface
    fun saveApiKeys(json: String): Boolean = runCatching {
        require(json.length <= 20_000)
        prefs.edit().putString("api_keys_v1", json).commit()
    }.getOrDefault(false)

    @JavascriptInterface
    fun loadApiKeys(): String = prefs.getString("api_keys_v1", "") ?: ""

    @JavascriptInterface
    fun clearApiKeys(): Boolean = prefs.edit().remove("api_keys_v1").commit()
}
