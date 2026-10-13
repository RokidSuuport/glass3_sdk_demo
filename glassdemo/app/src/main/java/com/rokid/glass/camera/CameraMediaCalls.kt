package com.rokid.glass.camera

import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.camera.CameraMediaOptions
import com.rokid.security.glass3.open.sdk.camera.CameraZoomSession
import com.rokid.security.glass3.sdk.base.data.media.CameraShareConfigV2
import com.rokid.security.glass3.sdk.base.data.media.CameraShareConfig
import com.rokid.security.glass3.sdk.base.data.media.CameraZoomConfig
import com.rokid.security.glass3.sdk.base.data.media.RecordConfig
import com.rokid.security.glass3.sdk.base.data.media.TextWatermarkConfig
import com.rokid.security.glass3.sdk.base.data.media.TextWatermarkItem
import com.rokid.security.system.server.media.ICameraPhotoCallback
import com.rokid.security.system.server.media.callback.VideoCallback

/** Four independent SDK calls used by the existing photo and recording buttons. Invoke off the UI thread. */
internal object CameraMediaCalls {
    fun takeLegacyPhoto(resolutionType: Int, path: String) {
        checkNotNull(GlassSdk.getGlassMediaService()) { "Media service not connected" }
            .takePhoto(resolutionType, path)
        // Result: the page's registered PhotoFileCallback.
    }

    /**
     * Intermediate SDK compatibility call. It is intentionally not a third page mode:
     * CONFIG_V1 only adds configured photo output/quality and has no matching V1 recorder.
     */
    fun takeConfigV1Photo(config: CameraShareConfig, path: String, jpegQuality: Int) {
        checkNotNull(GlassSdk.getGlassMediaService()) { "Media service not connected" }
            .takePhotoWithConfig(config, path, jpegQuality)
        // Result: the caller's registered PhotoFileCallback.
    }

    fun takeOptionsPhoto(width: Int, height: Int, zoom: Float, reuseOpenedResolution: Boolean,
        path: String, jpegQuality: Int, callback: ICameraPhotoCallback) {
        val config = CameraShareConfigV2(
            previewWidth = width,
            previewHeight = height,
            previewTargetFps = 24,
            zoom = CameraZoomConfig.explicit(zoom),
        )
        CameraMediaOptions.takePhoto(config, reuseOpenedResolution, path, jpegQuality, callback)
        // Result: this request's onPhoto/onError callback; no global photo listener is needed.
    }

    fun startLegacyRecord(callback: VideoCallback, config: RecordConfig) {
        checkNotNull(GlassSdk.getGlassMediaService()) { "Media service not connected" }
            .startRecord(callback, config)
    }

    fun startOptionsRecord(callback: VideoCallback, config: RecordConfig, zoom: Float,
        watermark: Boolean, session: CameraZoomSession) {
        val textWatermark = if (watermark) TextWatermarkConfig(
            enabled = true,
            items = listOf(TextWatermarkItem(id = "demo", text = "SDK Demo")),
            timestampEnabled = true,
        ) else null
        CameraMediaOptions.startRecord(
            callback, config, CameraZoomConfig.explicit(zoom),
            textWatermark, session,
        )
        // onStart confirms recording; session.state confirms the applied zoom.
    }
}
