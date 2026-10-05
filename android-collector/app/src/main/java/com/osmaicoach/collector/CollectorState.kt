package com.osmaicoach.collector

import java.util.concurrent.CopyOnWriteArrayList

object CollectorState {
    @Volatile var currentForegroundPackage: String? = null
    @Volatile var lastError: String? = null
    @Volatile private var serviceReady = false
    @Volatile private var recording = false
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun isServiceReady() = serviceReady
    fun isRecording() = recording
    fun setServiceReady(value: Boolean) {
        if (serviceReady == value) return
        serviceReady = value
        notifyChanged()
    }
    fun setRecording(value: Boolean) {
        if (recording == value) return
        recording = value
        notifyChanged()
    }
    fun signalFrameCaptured() = notifyChanged()
    fun signalSessionReady() = notifyChanged()
    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    private fun notifyChanged() = listeners.forEach { runCatching { it() } }
}
