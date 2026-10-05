package com.osmaicoach.collector

import android.webkit.JavascriptInterface

class CoachBridge(
    private val activity: MainActivity,
    private val repository: SessionRepository
) {
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
}
