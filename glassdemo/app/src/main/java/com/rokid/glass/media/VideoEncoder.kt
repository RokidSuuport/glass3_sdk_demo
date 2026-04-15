package com.rokid.glass.media

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import androidx.annotation.WorkerThread
import com.rokid.security.glass3.open.sdk.uitls.log.L
import kotlin.concurrent.withLock

/**
 * 视频编码器
 * @param videoData 视频参数
 * @param callback 回调
 */
class VideoEncoder(
    private val videoData: VideoData,
    bufferHelper: ByteBufferHelper,
    callback: Callback
) : MediaEncoder(callback, bufferHelper) {
    private val TAG = "MyMedia"

    override val mediaType: MediaType = MediaType.VIDEO

    override val mediaCodec = run {
        val format = MediaFormat.createVideoFormat(
            mediaType.mimeType, videoData.frameSize.width, videoData.frameSize.height
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, videoData.bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, videoData.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, videoData.frameInterval)

            L.i(this@VideoEncoder.TAG, ": width = ${videoData.frameSize.width}, height = ${videoData.frameSize.height}  bitRate = ${videoData.bitRate / 1024 /1024}  frameRate = ${videoData.frameRate}, frameInterval = ${videoData.frameInterval}")
        }
        val codec = MediaCodec.createEncoderByType(mediaType.mimeType).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        codec
    }

    // 绑定到编码器的 Surface
    val surface = mediaCodec.createInputSurface()

    override fun release(t: Throwable?) {
        super.release(t)
        surface.release()
    }

    override fun enqueueEndStream() {
        lockEnqueue.withLock {
            L.i(this@VideoEncoder.TAG,"Enqueue end stream.")
            mediaCodec.signalEndOfInputStream()
        }
    }

    /**
     * 手动绘制图像
     * @param bitmap 图像
     */
    @WorkerThread
    fun enqueueVideoBitmap(bitmap: Bitmap) {
        lockEnqueue.withLock {
            // 除非调用结束流或正在进行编码，否则不执行任何操作
            if (isCalledEndStream || !isEncoding) return

            val canvas = surface.lockCanvas(Rect(0, 0, bitmap.width, bitmap.height))
            canvas.drawBitmap(bitmap, 0f, 0f, Paint())
            surface.unlockCanvasAndPost(canvas)
        }
    }
}