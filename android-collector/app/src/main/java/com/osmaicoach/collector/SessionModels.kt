package com.osmaicoach.collector

data class CaptureFrame(
    val index: Int,
    val fileName: String,
    val capturedAt: Long,
    val sourcePackage: String = OSM_PACKAGE,
    val width: Int,
    val height: Int,
    val fingerprint: Long,
    val textHint: String = "",
    val screenType: String = "other",
    val screenTitle: String = "",
    val slotId: Int = 0,
    val ocrText: String = "",
    val analysisState: String = "captured",
    val extractedFields: Int = 0
)

data class CaptureSession(
    val id: String,
    val startedAt: Long,
    var endedAt: Long? = null,
    val frames: MutableList<CaptureFrame> = mutableListOf(),
    var state: String = "recording"
)

data class FrameAnalysisUpdate(
    val slotId: Int = 0,
    val screenType: String = "other",
    val screenTitle: String = "Tela do OSM",
    val ocrText: String = "",
    val analysisState: String = "ocr",
    val extractedFields: Int = 0
)

const val OSM_PACKAGE = "com.gamebasics.osm"
