package com.rokid.glass.camera

import com.rokid.glesse.R
import com.rokid.security.glass3.sdk.base.data.media.CameraShareConfig
import com.rokid.security.glass3.sdk.base.data.media.CameraShareConfigV2

/** Each mode calls a different public Helper entry point; none silently falls back. */
internal enum class Nv21ExportMode(val label: Int, val hint: Int, val method: String) {
    OPTIONS_BUFFER(R.string.nv21_mode_options_buffer, R.string.nv21_hint_options_buffer,
        "initNv21BufferExportWithOptions"),
    OPTIONS_ARRAY(R.string.nv21_mode_options_array, R.string.nv21_hint_options_array,
        "initNv21ExportWithOptions"),
    CONFIG_BUFFER(R.string.nv21_mode_config_buffer, R.string.nv21_hint_config_buffer,
        "initNv21BufferExportWithConfig");

    val hasZoomSession: Boolean get() = this != CONFIG_BUFFER
    val hasFrameEvidence: Boolean get() = this == OPTIONS_BUFFER
    val zoomOptions: List<Float> get() = if (this == CONFIG_BUFFER) {
        listOf(1f, 2f, 3f, 4f, 6f, 8f)
    } else {
        listOf(1f, 1.25f, 1.575f, 2f, 3f, 4f, 6f, 8f)
    }
}

/** V1 supports integers only. Reject fractions instead of silently truncating them. */
internal fun nv21IntegerConfig(config: CameraShareConfigV2): CameraShareConfig {
    val ratio = config.zoom.zoomRatio
    require(ratio.isFinite() && ratio >= 1f && ratio <= 8f && ratio == ratio.toInt().toFloat())
    return CameraShareConfig(
        previewWidth = config.previewWidth,
        previewHeight = config.previewHeight,
        previewTargetFps = config.previewTargetFps,
        enableVideoStabilization = config.enableVideoStabilization,
        zoomLevel = ratio.toInt(),
        useAsyncCallback = false, // Borrowed shared memory is consumed before the Binder call returns.
    )
}
