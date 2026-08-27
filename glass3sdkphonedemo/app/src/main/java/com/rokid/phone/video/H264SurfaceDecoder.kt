package com.rokid.phone.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/** Minimal low-latency AVC decoder that renders compressed frames directly to a Surface. */
class H264SurfaceDecoder(
    private val onFrameRendered: () -> Unit,
    private val onOutputFormatChanged: (DecodedVideoGeometry) -> Unit,
) {
    private var codec: MediaCodec? = null

    @Synchronized
    fun start(surface: Surface, width: Int, height: Int) {
        stop()
        if (!surface.isValid || width <= 0 || height <= 0) return
        codec = try {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height)
                }
                configure(format, surface, null, 0)
                start()
            }
        } catch (error: Exception) {
            Log.e(TAG, "decoder start failed", error)
            null
        }
        if (codec != null) Log.i(TAG, "decoder started: ${width}x$height")
    }

    @Synchronized
    fun queueAccessUnit(source: ByteBuffer) {
        val copy = ByteArray(source.remaining())
        source.duplicate().get(copy)
        queueAccessUnit(copy)
    }

    /** The transport ByteBuffer can be reused after its callback returns, so decode an owned copy. */
    @Synchronized
    fun queueAccessUnit(data: ByteArray) {
        val decoder = codec ?: return
        try {
            drainOutput(decoder)
            val inputIndex = decoder.dequeueInputBuffer(0)
            if (inputIndex >= 0) {
                val input = decoder.getInputBuffer(inputIndex) ?: return
                val size = data.size
                input.clear()
                if (size > input.remaining()) {
                    Log.w(TAG, "drop oversized access unit: $size > ${input.remaining()}")
                    return
                }
                input.put(data)
                decoder.queueInputBuffer(
                    inputIndex,
                    0,
                    size,
                    System.nanoTime() / 1_000L,
                    0
                )
            }
            drainOutput(decoder)
        } catch (error: IllegalStateException) {
            Log.e(TAG, "decode failed", error)
        }
    }

    private fun drainOutput(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = decoder.dequeueOutputBuffer(info, 0)
            when {
                outputIndex >= 0 -> {
                    decoder.releaseOutputBuffer(outputIndex, true)
                    if (info.size > 0) onFrameRendered()
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    dispatchOutputFormat(decoder.outputFormat)
                }

                outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> return
            }
        }
    }

    private fun dispatchOutputFormat(format: MediaFormat) {
        val codedWidth = format.integerOrNull(MediaFormat.KEY_WIDTH) ?: return
        val codedHeight = format.integerOrNull(MediaFormat.KEY_HEIGHT) ?: return
        val geometry = DecodedVideoGeometry.create(
            codedWidth = codedWidth,
            codedHeight = codedHeight,
            cropLeft = format.integerOrNull("crop-left"),
            cropTop = format.integerOrNull("crop-top"),
            cropRight = format.integerOrNull("crop-right"),
            cropBottom = format.integerOrNull("crop-bottom"),
            rotationDegrees = format.integerOrNull(MediaFormat.KEY_ROTATION) ?: 0,
        )
        Log.i(TAG, "output format changed: $format, geometry=$geometry")
        onOutputFormatChanged(geometry)
    }

    private fun MediaFormat.integerOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    @Synchronized
    fun stop() {
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        codec = null
    }

    private companion object {
        const val TAG = "H264SurfaceDecoder"
    }
}
