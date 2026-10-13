package com.rokid.glass.camera

import com.rokid.glass.utils.demoString
import com.rokid.glesse.R
import com.rokid.security.glass3.sdk.base.data.media.CameraShareConfig
import com.rokid.security.glass3.sdk.base.data.media.CameraShareConfigV2
import com.rokid.security.glass3.sdk.base.data.media.CameraZoomConfig
import com.rokid.security.glass3.sdk.base.data.media.CameraZoomState
import com.rokid.security.glass3.sdk.base.data.media.TextWatermarkConfig
import com.rokid.security.glass3.sdk.base.data.media.TextWatermarkItem

/** Values are output dimensions for the October 2026 SDK, not Camera2 sensor dimensions. */
internal data class CameraApiRequest(
    val width: Int,
    val height: Int,
    val fps: Int,
    val zoom: Float,
    val eis: Boolean = false,
) {
    init {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "Width and height must be positive even numbers"
        }
        require(width.toLong() * height <= 4096L * 4096L) { "Resolution is too large" }
        require(fps in 1..60) { "FPS must be 1..60" }
        require(zoom.isFinite() && zoom >= 1f) { "Zoom must be a finite number >= 1" }
    }

    fun options() = CameraShareConfigV2(
        previewWidth = width, previewHeight = height, previewTargetFps = fps,
        enableVideoStabilization = eis, zoom = CameraZoomConfig.explicit(zoom),
    )

    // The V1 example deliberately uses 1x; never silently round a requested fractional zoom.
    fun legacy() = CameraShareConfig(
        previewWidth = width, previewHeight = height, previewTargetFps = fps,
        enableVideoStabilization = eis, zoomLevel = 1,
    )
}

internal fun cameraApiWatermark(text: String): TextWatermarkConfig {
    require(text.length <= 160) { "Watermark must be at most 160 characters" }
    return TextWatermarkConfig(
        enabled = true,
        items = listOf(TextWatermarkItem(id = "demo", text = text)),
        timestampEnabled = true,
    )
}

internal fun cameraApiState(state: CameraZoomState?): String = state?.let {
    "ready=${it.ready}, requested=${it.requestedZoom}, applied=${it.appliedZoom}, " +
        "generation=${it.frameGeneration}, source=${it.sourceWidth}×${it.sourceHeight}, " +
        "limited=${it.limitedByCapability}"
} ?: "State not available"

internal fun cameraZoomResult(method: String, code: Int): String = "$method: code=$code; " + when (code) {
    0 -> "accepted; wait for ready/appliedZoom (not yet proof of effect)"
    -1 -> "invalid parameter"
    -2 -> "session not created or closed"
    else -> "control error"
}

internal fun cameraZoomAwaitResult(requested: Float, actual: Float?): String =
    if (actual == null) demoString(R.string.camera_zoom_unconfirmed_result, requested.toString())
    else demoString(R.string.camera_zoom_confirmed_result, requested.toString(), actual.toString())

/** Show a measured zoom only after the SDK has confirmed the current configuration. */
internal fun cameraActualZoomText(state: CameraZoomState?): String =
    if (state?.ready == true && state.appliedZoom.isFinite()) {
        demoString(R.string.camera_zoom_actual, state.appliedZoom.toString())
    } else {
        demoString(R.string.camera_zoom_pending)
    }

/** Strict evidence gate: an old frame must never be labelled with a newer zoom configuration. */
internal object CameraFrameEvidence {
    fun accepts(ready: Boolean, appliedZoom: Float, frameGeneration: Long, confirmedGeneration: Long,
        width: Int, height: Int, bytesAvailable: Int): Boolean {
        if (!ready || !appliedZoom.isFinite() || frameGeneration <= 0 || frameGeneration != confirmedGeneration) return false
        if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0 || bytesAvailable < 0) return false
        // Multiply available bytes instead of pixel count so untrusted dimensions cannot overflow.
        return width.toLong() * height <= bytesAvailable.toLong() * 2 / 3
    }
}
