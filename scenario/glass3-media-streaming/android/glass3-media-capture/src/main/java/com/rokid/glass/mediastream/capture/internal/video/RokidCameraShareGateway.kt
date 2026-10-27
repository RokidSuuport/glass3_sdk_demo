package com.rokid.glass.mediastream.capture.internal.video

import com.rokid.glass.mediastream.capture.VideoCaptureOptions
import com.rokid.security.glass3.open.sdk.camera.CameraShareHelper
import com.rokid.security.glass3.sdk.base.data.media.CameraShareConfig

internal class RokidCameraShareGateway(
    private val helper: CameraShareHelper = CameraShareHelper(),
) : CameraShareGateway {
    override fun start(options: VideoCaptureOptions, callback: CameraShareGateway.Callback) {
        helper.initNv21ExportWithConfig(
            enableMix = options.enableMix,
            config = CameraShareConfig(
                previewWidth = options.width,
                previewHeight = options.height,
                previewTargetFps = options.fps,
                enableVideoStabilization = options.enableVideoStabilization,
                zoomLevel = 1,
                useAsyncCallback = true,
            ),
            callback = object : CameraShareHelper.Nv21Callback {
                override fun onCameraOpened(width: Int, height: Int) {
                    callback.onOpened()
                }

                override fun onNv21Frame(
                    nv21: ByteArray,
                    width: Int,
                    height: Int,
                    timestamp: Long,
                ) {
                    val timestampNs = try {
                        Math.multiplyExact(timestamp, NANOS_PER_MILLISECOND)
                    } catch (error: ArithmeticException) {
                        callback.onError(
                            TIMESTAMP_OVERFLOW,
                            "CameraShare timestamp is out of nanosecond range: $timestamp",
                        )
                        return
                    }
                    callback.onFrame(nv21, width, height, timestampNs)
                }

                override fun onCameraClosed() {
                    callback.onClosed()
                }

                override fun onError(code: Int, msg: String) {
                    callback.onError(code, msg)
                }
            },
        )
    }

    override fun stop() {
        helper.releaseNv21Export()
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val TIMESTAMP_OVERFLOW = -10_003
    }
}
