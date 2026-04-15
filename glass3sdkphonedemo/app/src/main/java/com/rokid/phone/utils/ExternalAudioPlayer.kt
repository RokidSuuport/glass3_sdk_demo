package com.rokid.phone.utils

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.rokid.security.phone.sdk.base.utils.other.workScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors


class ExternalAudioPlayer(
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val channelConfig: Int = DEFAULT_CHANNEL_CONFIG,
    private val audioFormat: Int = DEFAULT_AUDIO_FORMAT
) {
    private var audioTrack: AudioTrack? = null
    var isPlaying = false
    private var mPlaybackJob: Job? = null
    private val dataChannel = Channel<ByteArray>(Channel.UNLIMITED)

    private var listener: AudioStreamListener? = null

    // 创建专用的单线程调度器，设置为音频优先级
    private val audioDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AudioPlaybackThread").apply {
            priority = Thread.MAX_PRIORITY // 设置为最高优先级
        }
    }.asCoroutineDispatcher()

    private var sendScope: CoroutineScope? = null

    // 音频缓冲区大小，设置为200ms音频数据
    private val BUFFER_DURATION_MS = 200

    // 每帧字节数（16位单声道：2字节/帧）
    private val BYTES_PER_FRAME = 2

    // 计算缓冲区帧数
    private val BUFFER_FRAMES = (sampleRate * BUFFER_DURATION_MS / 1000).toInt()

    // 计算缓冲区字节数，确保是帧大小的整数倍
    private val BUFFER_BYTES = BUFFER_FRAMES * BYTES_PER_FRAME

    // 循环缓冲区和指针
    private val circularBuffer = ByteArray(BUFFER_BYTES * 2) // 双倍缓冲区减少溢出风险
    private var writePosition = 0
    private var readPosition = 0
    private var bufferSize = 0

    companion object {
        const val DEFAULT_SAMPLE_RATE = 16000
        const val DEFAULT_CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        const val DEFAULT_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        const val PLAY_OUT_TIME = 1000L
        private const val QUEUE_CAPACITY = 5
        val TAG = "ExternalAudioPlayer"
    }

    fun setAudioStreamListener(listener: AudioStreamListener) {
        Log.d(TAG, "setAudioStreamListener->")
        this.listener = listener
    }

    fun start() {
        Log.d(TAG, "start-->")
        if (isPlaying) return
        sendScope = CoroutineScope(audioDispatcher)
        // 重置缓冲区状态
        resetBuffer()

        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            channelConfig,
            audioFormat
        ).coerceAtLeast(BUFFER_BYTES)

        Log.d(TAG, "AudioTrack buffer size: $bufferSize")

        audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize,
            AudioTrack.MODE_STREAM
        ).apply {
            initAudioTraceListener()
            if (state != AudioTrack.STATE_INITIALIZED) {
                notifyError("AudioTrack initialization failed")
                return
            }
        }

        isPlaying = true

        mPlaybackJob?.cancel()
        mPlaybackJob = CoroutineScope(audioDispatcher).launch {
            playbackRunnable()
        }

        notifyStart()
    }


    private fun initAudioTraceListener() {
        audioTrack?.apply {
            setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack) {
                    // 不适用于流模式
                }

                override fun onPeriodicNotification(track: AudioTrack) {
                    Log.d(TAG, "onPeriodicNotification bufferSize:" + bufferSize + " " + dataChannel.isEmpty)
                    if (bufferSize == 0 && dataChannel.isEmpty) {
                        // 数据已全部播放
                        listener?.onComplete()
                    }
                }
            })
            // 每 100ms 检查一次
            positionNotificationPeriod = (sampleRate * 0.1).toInt()
        }
    }


    fun writeAudioData(data: ByteArray) {
        if (isPlaying) {
            sendScope?.launch {
                try {
                    // 确保数据长度是帧大小的整数倍
                    val alignedData = ensureFrameAlignment(data)
                    dataChannel.send(alignedData)
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending audio data to channel: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        Log.d(TAG, "stop-->")
        isPlaying = false
        mPlaybackJob?.cancel()
        sendScope?.cancel()
        notifyStop()
    }

    fun release() {
        stop()
        workScope.launch {
            while (!dataChannel.isEmpty) {
                dataChannel.receiveCatching().getOrNull() ?: break
            }
        }
        audioTrack?.release()
        audioTrack = null
    }

    private suspend fun playbackRunnable() {
        Log.d(TAG, "playbackRunnable started")

        try {
            // 等待缓冲区积累足够数据再开始播放，避免播放不完整
            delay(100) // 等待100ms

            synchronized(this) {
                audioTrack?.play()
                Log.d(TAG, "AudioTrack started playing")
            }
            Log.d(TAG, "dataChannel size:" + dataChannel.isEmpty)
            // 主播放循环
            for (buffer in dataChannel) {
                if (!isPlaying) break

                // 将数据写入循环缓冲区
                writeToBuffer(buffer)

                // 从缓冲区读取并写入AudioTrack
                playFromBuffer()
            }

            // 播放缓冲区中剩余的数据
            playRemainingBuffer()

        } catch (e: Exception) {
            Log.e(TAG, "Error in playbackRunnable: ${e.message}")
            notifyError("Playback error: ${e.message}")
        } finally {
            Log.d(TAG, "playbackRunnable finished listener:" + listener)
            listener?.onComplete() // 新增：调用onComplete
            synchronized(this) {
                try {
                    audioTrack?.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "playbackRunnable e:" + e.message)
                }

            }
        }
    }

    private fun resetBuffer() {
        writePosition = 0
        readPosition = 0
        bufferSize = 0
    }

    private fun writeToBuffer(data: ByteArray) {
        checkCompleteJob?.cancel()
        val bytesToWrite = data.size
        // 处理缓冲区溢出
        if (bufferSize + bytesToWrite > circularBuffer.size) {
            Log.w(TAG, "Buffer overflow, dropping ${bytesToWrite - (circularBuffer.size - bufferSize)} bytes")
        }

        // 将数据写入循环缓冲区
        val bytesToEnd = circularBuffer.size - writePosition
        if (bytesToWrite <= bytesToEnd) {
            System.arraycopy(data, 0, circularBuffer, writePosition, bytesToWrite)
            writePosition += bytesToWrite
            if (writePosition >= circularBuffer.size) {
                writePosition = 0
            }
        } else {
            // 数据需要分两部分写入
            System.arraycopy(data, 0, circularBuffer, writePosition, bytesToEnd)
            System.arraycopy(data, bytesToEnd, circularBuffer, 0, bytesToWrite - bytesToEnd)
            writePosition = bytesToWrite - bytesToEnd
        }

        bufferSize = minOf(bufferSize + bytesToWrite, circularBuffer.size)
    }

    private fun playFromBuffer() {
        // 确保有足够数据可播放
        checkCompleteJob?.cancel()
        if (bufferSize < BUFFER_BYTES / 2) {
            checkCompleteJob = workScope.launch {
                delay(PLAY_OUT_TIME)
                Log.d(TAG, "playFromBuffer->onComplete")
                listener?.onComplete()
            }
            return
        }

        val bytesToPlay = minOf(bufferSize, BUFFER_BYTES)
        val tempBuffer = ByteArray(bytesToPlay)

        // 从循环缓冲区读取数据
        val bytesToEnd = circularBuffer.size - readPosition
        if (bytesToPlay <= bytesToEnd) {
            System.arraycopy(circularBuffer, readPosition, tempBuffer, 0, bytesToPlay)
            readPosition += bytesToPlay
            if (readPosition >= circularBuffer.size) {
                readPosition = 0
            }
        } else {
            System.arraycopy(circularBuffer, readPosition, tempBuffer, 0, bytesToEnd)
            System.arraycopy(circularBuffer, 0, tempBuffer, bytesToEnd, bytesToPlay - bytesToEnd)
            readPosition = bytesToPlay - bytesToEnd
        }

        bufferSize -= bytesToPlay

        // 将数据写入AudioTrack
        audioTrack?.write(tempBuffer, 0, tempBuffer.size)

        checkCompleteJob = workScope.launch {
            delay(PLAY_OUT_TIME)
            Log.d(TAG, "playFromBuffer->onComplete")
            listener?.onComplete()
        }

    }


    private var checkCompleteJob: Job? = null


    private fun playRemainingBuffer() {
        Log.d(TAG, "playRemainingBuffer->bufferSize:" + bufferSize)
        if (bufferSize <= 0) return

        val tempBuffer = ByteArray(bufferSize)

        // 从循环缓冲区读取剩余数据
        val bytesToEnd = circularBuffer.size - readPosition
        if (bufferSize <= bytesToEnd) {
            System.arraycopy(circularBuffer, readPosition, tempBuffer, 0, bufferSize)
        } else {
            System.arraycopy(circularBuffer, readPosition, tempBuffer, 0, bytesToEnd)
            System.arraycopy(circularBuffer, 0, tempBuffer, bytesToEnd, bufferSize - bytesToEnd)
        }

        // 将剩余数据写入AudioTrack
        audioTrack?.write(tempBuffer, 0, tempBuffer.size)
    }

    private fun ensureFrameAlignment(data: ByteArray): ByteArray {
        // 确保数据长度是帧大小的整数倍
        val remainder = data.size % BYTES_PER_FRAME
        return if (remainder == 0) {
            data
        } else {
            val alignedData = ByteArray(data.size - remainder)
            System.arraycopy(data, 0, alignedData, 0, alignedData.size)
            alignedData
        }
    }

    private fun notifyStart() = listener?.onStart()
    private fun notifyStop() = listener?.onStop()
    private fun notifyError(msg: String) = listener?.onError(msg)

    interface AudioStreamListener {
        fun onStart()
        fun onStop()
        fun onComplete()
        fun onError(message: String)
    }
}