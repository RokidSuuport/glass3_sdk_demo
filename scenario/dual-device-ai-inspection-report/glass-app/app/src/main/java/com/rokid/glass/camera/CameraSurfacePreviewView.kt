package com.rokid.glass.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.AttributeSet
import android.util.Log
import com.rokid.glass.view.glsurface.view.BackgroundGLSurfaceView
import com.rokid.security.glass3.open.sdk.camera.CameraShareHelper
import com.rokid.security.glass3.sdk.base.data.media.CameraShareConfig
import java.io.ByteArrayOutputStream

/**
 * 展示 SDK 共享相机 NV21 帧，并允许识别时获取最新帧的 JPEG。
 * Display SDK-shared camera NV21 frames and provide the latest frame as JPEG for recognition.
 *
 * 预览与识别消费同一个共享帧源；异步保存前必须复制回调数据。
 * Preview and recognition consume the same shared frame source; callback data must be copied before asynchronous use.
 */
class CameraSurfacePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : BackgroundGLSurfaceView(context, attrs) {
    data class CapturedFrame(
        val jpeg: ByteArray,
        val width: Int,
        val height: Int,
        val timestamp: Long,
    )

    var onPreviewError: ((Int, String) -> Unit)? = null
    var onPreviewOpened: (() -> Unit)? = null

    private data class Nv21Snapshot(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val timestamp: Long,
    )

    private val helper = CameraShareHelper()
    private val frameLock = Any()
    private var latestNv21: ByteArray? = null
    private var latestWidth = 0
    private var latestHeight = 0
    private var latestTimestamp = 0L

    fun startPreview() {
        if (helper.isNv21Active()) return
        helper.initNv21ExportWithConfig(
            false,
            CameraShareConfig(
                previewWidth = 1280,
                previewHeight = 720,
                previewTargetFps = 15,
                enableVideoStabilization = false,
                zoomLevel = 1,
            ),
            object : CameraShareHelper.Nv21Callback {
                override fun onCameraOpened(width: Int, height: Int) {
                    post { onPreviewOpened?.invoke() }
                }

                override fun onNv21Frame(
                    nv21: ByteArray,
                    width: Int,
                    height: Int,
                    timestamp: Long,
                ) {
                    if (width <= 0 || height <= 0 || nv21.size < width * height * 3 / 2) return
                    synchronized(frameLock) {
                        latestNv21 = nv21.copyOf()
                        latestWidth = width
                        latestHeight = height
                        latestTimestamp = timestamp
                    }
                    setPreviewData(nv21, width, height)
                }

                override fun onCameraClosed() {
                    synchronized(frameLock) {
                        latestNv21 = null
                        latestWidth = 0
                        latestHeight = 0
                        latestTimestamp = 0L
                    }
                    releasePreview()
                }

                override fun onError(code: Int, msg: String) {
                    Log.e(TAG, "camera preview failed code=$code msg=$msg")
                    post { onPreviewError?.invoke(code, msg) }
                }
            },
        )
    }

    fun stopPreview() {
        if (helper.isNv21Active()) helper.releaseNv21Export()
        synchronized(frameLock) {
            latestNv21 = null
            latestWidth = 0
            latestHeight = 0
            latestTimestamp = 0L
        }
        releasePreview()
    }

    fun captureJpeg(quality: Int = 85): CapturedFrame? {
        val snapshot = synchronized(frameLock) {
            val bytes = latestNv21?.copyOf() ?: return null
            Nv21Snapshot(bytes, latestWidth, latestHeight, latestTimestamp)
        }
        val output = ByteArrayOutputStream()
        val compressed = runCatching {
            YuvImage(snapshot.bytes, ImageFormat.NV21, snapshot.width, snapshot.height, null)
                .compressToJpeg(Rect(0, 0, snapshot.width, snapshot.height), quality, output)
        }.getOrDefault(false)
        if (!compressed) return null
        return CapturedFrame(
            jpeg = output.toByteArray(),
            width = snapshot.width,
            height = snapshot.height,
            timestamp = snapshot.timestamp,
        )
    }

    private companion object {
        const val TAG = "CameraSurfacePreviewView"
    }
}
