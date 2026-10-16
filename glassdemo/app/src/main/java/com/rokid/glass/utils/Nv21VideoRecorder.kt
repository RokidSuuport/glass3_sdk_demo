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
 * NV21 to MP4 recorder for video only, without audio.
 *
 * 将NV21帧数据编码为H.264并通过MediaMuxer写入MP4文件
 * Encode NV21 frames as H.264 and write them to an MP4 file using MediaMuxer.
 */
class Nv21VideoRecorder(
    private val context: Context,
    val width: Int,
    val height: Int,
    private val bitRate: Int = 2_000_000,
    private val frameRate: Int = 30,
    /** 分段序号：分辨率变化后由调用方新建下一段录制器，1 表示首段。 */
    private val segmentIndex: Int = 1,
) {
    private val TAG = "Nv21VideoRecorder"

    private var videoEncoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var muxerStarted = false
    private val bufferInfo = MediaCodec.BufferInfo()
    private val nv12Temp = ByteArray(width * height * 3 / 2)

    /**
     * 首帧超时（毫秒）：启动后超过此时长仍没有任何编码帧，则回调 [onFirstFrameTimeout]。
     * First-frame timeout in milliseconds; [onFirstFrameTimeout] fires when no frame is encoded within it.
     */
    var firstFrameTimeoutMs: Long = 3_000

    /**
     * 首帧超时回调，运行在编码线程；调用方负责切回主线程再更新界面。
     * Callback for the first-frame timeout. Runs on the encoder thread; the caller must hop to the main thread.
     */
    var onFirstFrameTimeout: ((String) -> Unit)? = null

    /**
     * 录制中途断流阈值（毫秒）：已经有帧之后连续这么久没有新帧，视为画面中断并回调 [onFrameStall]。
     * Mid-recording stall threshold: after frames started, this much silence reports a stall through [onFrameStall].
     */
    var frameStallTimeoutMs: Long = 3_000

    /**
     * 画面中断回调（编码线程），参数为断流时长；调用方负责切主线程。
     * Stall callback on the encoder thread with the idle duration; the caller must hop to the main thread.
     */
    var onFrameStall: ((Long) -> Unit)? = null

    private var framesSubmitted = 0L
    private var framesWritten = 0L
    private var framesSkipped = 0L
    private var startWallMs = 0L
    private var firstFrameLogged = false
    private var sizeMismatchLogged = false
    private var stallReported = false

    @Volatile
    private var lastFrameWallMs = 0L

    private val frameStallWatchdog = object : Runnable {
        override fun run() {
            if (!isRecording) return
            val last = lastFrameWallMs
            if (last > 0L && !stallReported) {
                val idleMs = System.currentTimeMillis() - last
                if (idleMs >= frameStallTimeoutMs) {
                    stallReported = true
                    Log.w(TAG, "No new frame for ${idleMs}ms, reporting stall")
                    onFrameStall?.invoke(idleMs)
                }
            }
            if (isRecording) encodeHandler.postDelayed(this, FRAME_STALL_CHECK_INTERVAL_MS)
        }
    }

    private val firstFrameWatchdog = Runnable {
        if (isRecording && framesWritten == 0L) {
            Log.w(TAG, "No encoded frame within ${firstFrameTimeoutMs}ms (submitted=$framesSubmitted)")
            onFirstFrameTimeout?.invoke(outputPath)
        }
    }

    /**
     * 录制结果：帧数、文件大小与失败原因，供调用方判断是否真的录到了内容。
     * Result of a recording session: frame counts, file size and failure reason.
     */
    data class Result(
        val path: String,
        val framesSubmitted: Long,
        val framesWritten: Long,
        val framesSkipped: Long,
        val sizeBytes: Long,
        val durationMs: Long,
        val success: Boolean,
        val reason: String?,
    )

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
        val baseName = "mix_record_$timestamp"
        val fileName = if (segmentIndex <= 1) "$baseName.mp4" else "${baseName}_seg$segmentIndex.mp4"
        outputPath = File(dir, fileName).absolutePath

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
            framesSubmitted = 0L
            framesWritten = 0L
            framesSkipped = 0L
            firstFrameLogged = false
            sizeMismatchLogged = false
            stallReported = false
            lastFrameWallMs = 0L
            startWallMs = System.currentTimeMillis()
            encodeHandler.removeCallbacks(firstFrameWatchdog)
            encodeHandler.postDelayed(firstFrameWatchdog, firstFrameTimeoutMs)
            encodeHandler.removeCallbacks(frameStallWatchdog)
            encodeHandler.postDelayed(frameStallWatchdog, FRAME_STALL_CHECK_INTERVAL_MS)

            Log.d(TAG, "Recording started: $outputPath (${width}x${height})")
        } catch (e: Exception) {
            Log.e(TAG, "start error", e)
            releaseInternal()
        }

        return outputPath
    }

    fun encodeFrame(nv21: ByteArray) {
        if (!isRecording) return
        // 尺寸不符的帧不能喂给当前编码器：分辨率切换后必须先换新编码器，
        // Frames whose size differs from the configured resolution must never reach the encoder;
        // 否则画面会被拉伸/错行。这里直接丢弃并计数，由调用方按分辨率回调重建录制器。
        // otherwise the picture is stretched or skewed, so drop and count them here.
        val expectedSize = width * height * 3 / 2
        if (nv21.size != expectedSize) {
            framesSkipped++
            if (!sizeMismatchLogged) {
                sizeMismatchLogged = true
                Log.w(TAG, "Skip frame with size=${nv21.size}, expected=$expectedSize (${width}x$height)")
            }
            return
        }
        lastFrameWallMs = System.currentTimeMillis()
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
                    framesSubmitted++
                }
                drainEncoder(false)
            } catch (e: Exception) {
                Log.e(TAG, "encodeFrame error", e)
            }
        }
    }

    /**
     * 停止录制并返回结果（帧数/文件大小/是否成功）。
     * Stop recording and report the result (frame counts, file size, success).
     *
     * 编码器先收尾（送 EOS + drain + muxer.stop），调用方应在拿到结果后再释放 NV21 导出。
     * The encoder is finalised first (EOS + drain + muxer.stop); callers should release the NV21 export afterwards.
     */
    fun stop(onStopped: ((Result) -> Unit)? = null) {
        if (!isRecording) {
            onStopped?.invoke(buildResult("not recording"))
            return
        }
        isRecording = false
        encodeHandler.removeCallbacks(firstFrameWatchdog)
        encodeHandler.removeCallbacks(frameStallWatchdog)

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
            val result = buildResult(null)
            Log.d(TAG, "Recording stopped: $outputPath frames=${result.framesWritten}/${result.framesSubmitted} skipped=${result.framesSkipped} size=${result.sizeBytes} success=${result.success} reason=${result.reason}")
            onStopped?.invoke(result)
        }
    }

    /**
     * 汇总本次录制结果：没有任何编码帧或文件过小都视为失败，并删除无效文件。
     * Summarise the session: zero encoded frames or a tiny file count as failure and the invalid file is deleted.
     */
    private fun buildResult(reason: String?): Result {
        val file = File(outputPath)
        val sizeBytes = if (file.exists()) file.length() else 0L
        val success = framesWritten > 0L && sizeBytes >= MIN_VALID_FILE_BYTES
        val failureReason = reason ?: if (framesWritten == 0L) "no frame encoded" else "file too small"
        if (!success) {
            val removed = file.exists() && file.delete()
            Log.w(TAG, "Invalid recording removed=$removed path=$outputPath frames=$framesWritten size=$sizeBytes")
        }
        return Result(
            path = outputPath,
            framesSubmitted = framesSubmitted,
            framesWritten = framesWritten,
            framesSkipped = framesSkipped,
            sizeBytes = sizeBytes,
            durationMs = if (startWallMs > 0L) System.currentTimeMillis() - startWallMs else 0L,
            success = success,
            reason = if (success) null else failureReason,
        )
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
                        framesWritten++
                        if (!firstFrameLogged) {
                            firstFrameLogged = true
                            Log.d(TAG, "First frame encoded after ${System.currentTimeMillis() - startWallMs}ms")
                        }
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

    private companion object {
        /**
         * 小于该字节数的文件视为没有录到有效画面。
         * Files smaller than this are treated as containing no usable footage.
         */
        const val MIN_VALID_FILE_BYTES = 16 * 1024L

        /** 画面中断检查间隔。 / Interval of the frame-stall check. */
        const val FRAME_STALL_CHECK_INTERVAL_MS = 500L
    }
}
