package com.rokid.glass.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import androidx.annotation.WorkerThread
import java.nio.ByteBuffer
import kotlin.concurrent.withLock

/**
 * 音频编码器
 * @param audioData 音频数据
 * @param callback 回调
 */
class AudioEncoder(
    private val audioData: AudioData,
    bufferHelper: ByteBufferHelper,
    callback: Callback
) : MediaEncoder(callback, bufferHelper) {

    companion object {
        // ENQUEUE超时时间 [us]
        private const val CODEC_ENQUEUE_TIMEOUT_US = 10 * 1000L

        // 编码尝试次数
        private const val ENCODE_TRY_TIMES = 10
    }

    override val mediaType: MediaType = MediaType.AUDIO

    override val mediaCodec = run {
        val format = MediaFormat.createAudioFormat(
            mediaType.mimeType, audioData.samplingRate, 1
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_CHANNEL_MASK, AudioFormat.CHANNEL_IN_MONO)
            setInteger(MediaFormat.KEY_BIT_RATE, audioData.bitRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)
        }
        val codec = MediaCodec.createEncoderByType(mediaType.mimeType).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        codec
    }

    // 请求时间戳 [us]
    private var reqTimeStampUs = 0L

    override fun release(t: Throwable?) {
        reqTimeStampUs = 0L
        super.release(t)
    }

    override fun enqueueEndStream() {
        lockEnqueue.withLock {
            logI("Enqueue end stream $this")
            val index = dequeueInputBuffer(true)
            mediaCodec.queueInputBuffer(
                index!!, 0, 0, reqTimeStampUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
            isCalledEndStream = true
        }
    }

    /**
     * 将音频缓冲区排入队列.
     * @param bytes 包含音频缓冲区信息的字节数组
     */
    @WorkerThread
    fun enqueueAudioBytes(bytes: ByteArray) {
        lockEnqueue.withLock {
            // 除非调用结束流或正在进行编码，否则不执行任何操作
            if (isCalledEndStream || !isEncoding) return

            // 将从缓冲区计算的时间添加到时间戳
            val sample = bytes.size / audioData.bytesPerSample
            val timeIntervalMicros = 1000L * 1000L * sample / audioData.samplingRate
            reqTimeStampUs += timeIntervalMicros

            // 将字节数组转换为缓冲区并将其放入队列中
            val buffer = ByteBuffer.wrap(bytes)
            val index = dequeueInputBuffer() ?: return
            val inputBuffer = mediaCodec.getInputBuffer(index) ?: return
            inputBuffer.put(buffer)
            mediaCodec.queueInputBuffer(index, 0, buffer.capacity(), reqTimeStampUs, 0)
        }
    }

    /**
     * 获取缓冲区索引的内部处理
     */
    @WorkerThread
    private fun dequeueInputBuffer(neverGiveUp: Boolean = false): Int? {
        var retryCount = 0
        while (retryCount < ENCODE_TRY_TIMES) {
            val index = mediaCodec.dequeueInputBuffer(CODEC_ENQUEUE_TIMEOUT_US)
            if (index < 0) {
                when (index) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!neverGiveUp) retryCount++
                    }
                    else -> {}
                }
            } else {
                return index
            }
        }
        return null
    }
}