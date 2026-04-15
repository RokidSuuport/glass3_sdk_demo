package com.rokid.glass.utils

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * NV21 → MP4 视频录制器（仅视频，无音频）
 *
 * 将NV21帧数据编码为H.264并通过MediaMuxer写入MP4文件
 */
class Nv21VideoRecorder(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val bitRate: Int = 2_000_000,
    private val frameRate: Int = 30
) {
    private val TAG = "Nv21VideoRecorder"

    private var videoEncoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var muxerStarted = false
    private val bufferInfo = MediaCodec.BufferInfo()
    private val nv12Temp = ByteArray(width * height * 3 / 2)

    var outputPath: String = ""
        private set

    private val encodeThread = HandlerThread("Nv21RecordThread").apply { start() }
    private val encodeHandler = Handler(encodeThread.looper)

    @Volatile
    private var isRecording = false
    private var videoPtsUs = 0L
    private var startTimeUs = -1L
    private val frameDurationUs = 1_000_000L / frameRate

    fun start(): String {
        val dir = File(context.getExternalFilesDir("recordings"), "")
        if (!dir.exists()) dir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        outputPath = File(dir, "mix_record_$timestamp.mp4").absolutePath

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            isRecording = true
            videoPtsUs = 0L
            startTimeUs = -1L

            Log.d(TAG, "Recording started: $outputPath (${width}x${height})")
        } catch (e: Exception) {
            Log.e(TAG, "start error", e)
            releaseInternal()
        }

        return outputPath
    }

    fun encodeFrame(nv21: ByteArray) {
        if (!isRecording) return
        encodeHandler.post {
            try {
                val encoder = videoEncoder ?: return@post
                val inputIdx = encoder.dequeueInputBuffer(10_000)
                if (inputIdx >= 0) {
                    val inputBuffer = encoder.getInputBuffer(inputIdx) ?: return@post
                    inputBuffer.clear()

                    nv21ToNv12(nv21, nv12Temp, width, height)
                    inputBuffer.put(nv12Temp)

                    val nowUs = System.nanoTime() / 1000
                    if (startTimeUs < 0) startTimeUs = nowUs
                    val wallUs = nowUs - startTimeUs
                    videoPtsUs = if (wallUs <= videoPtsUs) videoPtsUs + frameDurationUs else wallUs

                    encoder.queueInputBuffer(inputIdx, 0, nv12Temp.size, videoPtsUs, 0)
                }
                drainEncoder(false)
            } catch (e: Exception) {
                Log.e(TAG, "encodeFrame error", e)
            }
        }
    }

    fun stop(onStopped: ((String) -> Unit)? = null) {
        if (!isRecording) {
            onStopped?.invoke(outputPath)
            return
        }
        isRecording = false

        encodeHandler.post {
            try {
                videoEncoder?.let { enc ->
                    val idx = enc.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val ib = enc.getInputBuffer(idx)
                        ib?.clear()
                        enc.queueInputBuffer(idx, 0, 0, videoPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                }
                drainEncoder(true)
            } catch (e: Exception) {
                Log.e(TAG, "drain error during stop", e)
            }
            releaseInternal()
            Log.d(TAG, "Recording stopped: $outputPath")
            onStopped?.invoke(outputPath)
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val encoder = videoEncoder ?: return
        while (true) {
            val outputIdx = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outputIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = encoder.outputFormat
                    try {
                        val m = muxer ?: break
                        videoTrackIndex = m.addTrack(newFormat)
                        m.start()
                        muxerStarted = true
                        Log.d(TAG, "Muxer started, track=$videoTrackIndex")
                    } catch (e: Exception) {
                        Log.e(TAG, "muxer addTrack/start error", e)
                    }
                }
                outputIdx >= 0 -> {
                    val encoded = encoder.getOutputBuffer(outputIdx) ?: continue
                    if (bufferInfo.size > 0 && muxerStarted) {
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        muxer?.writeSampleData(videoTrackIndex, encoded, bufferInfo)
                    }
                    val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    encoder.releaseOutputBuffer(outputIdx, false)
                    if (eos || endOfStream) break
                }
            }
        }
    }

    private fun releaseInternal() {
        try { videoEncoder?.stop() } catch (_: Exception) {}
        try { videoEncoder?.release() } catch (_: Exception) {}
        videoEncoder = null
        try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        muxer = null
        muxerStarted = false
        videoTrackIndex = -1
    }

    fun release() {
        isRecording = false
        encodeHandler.post { releaseInternal() }
        encodeThread.quitSafely()
    }

    private fun nv21ToNv12(nv21: ByteArray, nv12: ByteArray, w: Int, h: Int) {
        val frameSize = w * h
        System.arraycopy(nv21, 0, nv12, 0, frameSize)
        var i = 0
        while (i < frameSize / 2) {
            nv12[frameSize + i] = nv21[frameSize + i + 1]
            nv12[frameSize + i + 1] = nv21[frameSize + i]
            i += 2
        }
    }
}
