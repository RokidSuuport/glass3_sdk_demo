package com.rokid.glass.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log

/**
 * 使用 MediaCodec + MediaMuxer 将 PCM 编码为 AAC (M4A/MP4 容器)
 * 即使文件后缀是 .mp3，内部实际是 AAC 编码
 */
class WorkAudioEncoder {
    private val TAG = "AiWorkAudioEncoder"
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var isEncoding = false
    private val bufferInfo = MediaCodec.BufferInfo()
    private var presentationTimeUs = 0L

    // 假设输入是 16kHz 单声道 16bit PCM (Rokid Glass 常用格式)
    private val SAMPLE_RATE = 16000
    private val CHANNEL_COUNT = 1
    private val BIT_RATE = 64000

    fun start(path: String) {
        if (isEncoding) stop()

        try {
            // 1. Init MediaCodec for AAC
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNEL_COUNT)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec?.start()

            // 2. Init MediaMuxer
            mediaMuxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            isEncoding = true
            presentationTimeUs = 0
            muxerStarted = false
            trackIndex = -1

            Log.d(TAG, "Encoder started: $path")
        } catch (e: Exception) {
            Log.e(TAG, "Start failed", e)
            stop()
        }
    }

    fun encode(data: ByteArray, length: Int) {
        if (!isEncoding || mediaCodec == null) return

        try {
            val codec = mediaCodec!!
            // 1. Input PCM data
            val inputBufferIndex = codec.dequeueInputBuffer(10000)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                inputBuffer?.clear()
                inputBuffer?.put(data, 0, length)

                codec.queueInputBuffer(inputBufferIndex, 0, length, presentationTimeUs, 0)

                // Update timestamp
                val samples = length / (CHANNEL_COUNT * 2) // 16bit = 2 bytes
                presentationTimeUs += (samples * 1000000L) / SAMPLE_RATE
            }

            // 2. Drain Output
            drain()
        } catch (e: Exception) {
            Log.e(TAG, "Encode error", e)
        }
    }

    private fun drain() {
        val codec = mediaCodec ?: return
        var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)

        while (outputBufferIndex >= 0 || outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    Log.w(TAG, "Format changed twice!")
                } else {
                    val newFormat = codec.outputFormat
                    trackIndex = mediaMuxer?.addTrack(newFormat) ?: -1
                    mediaMuxer?.start()
                    muxerStarted = true
                    Log.d(TAG, "Muxer started")
                }
            } else {
                val encodedData = codec.getOutputBuffer(outputBufferIndex)
                if (encodedData != null) {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size != 0) {
                        if (muxerStarted) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            mediaMuxer?.writeSampleData(trackIndex, encodedData, bufferInfo)
                        }
                    }

                    codec.releaseOutputBuffer(outputBufferIndex, false)
                }
            }
            outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
        }
    }

    fun stop() {
        if (!isEncoding) return
        isEncoding = false
        try {
            mediaCodec?.stop()
            mediaCodec?.release()

            if (muxerStarted) {
                mediaMuxer?.stop()
                mediaMuxer?.release()
            } else {
                // If muxer never started, we should still release it
                mediaMuxer?.release()
            }

            Log.d(TAG, "Encoder stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Stop failed", e)
        } finally {
            mediaCodec = null
            mediaMuxer = null
        }
    }
}