package com.rokid.glass.mediastream.sender.capture

import com.rokid.glass.mediastream.capture.PcmFrame
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** 实际 PCM 帧的格式；开始录音后，该格式在整段 WAV 中必须保持不变。 */
internal data class PcmFormat(
    val sampleRateHz: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
) {
    init {
        require(sampleRateHz > 0) { "PCM sample rate must be positive" }
        require(channelCount > 0) { "PCM channel count must be positive" }
        require(bitsPerSample == PCM16_BITS) { "Only 16-bit PCM can be recorded" }

        val blockAlign = channelCount.toLong() * bitsPerSample / BITS_PER_BYTE
        val byteRate = sampleRateHz.toLong() * blockAlign
        require(blockAlign in 1..USHORT_MAX) { "PCM block alignment is too large" }
        require(byteRate in 1..UINT32_MAX) { "PCM byte rate is too large" }
    }
}

/**
 * 将 PCM 回调帧按顺序写成标准 44-byte-header WAV 文件。
 *
 * [append] 只复制当前小帧并尝试放入有界队列，不等待磁盘；真正的文件写入始终由唯一的
 * writer 线程执行。格式变化、帧未对齐、WAV 容量超限、队列已满或磁盘异常都会把本次
 * 录音标记为失败，最终 [stop] 返回 `null` 并删除不能可靠播放的文件。
 *
 * 一个实例只负责一段录音。再次录音请创建新实例，避免复用已经关闭的执行器。
 */
internal class WavRecorder(
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val beforeWrite: (ByteArray) -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private val finalizationFinished = CountDownLatch(1)
    private val writtenDataBytes = AtomicLong(0L)
    private val writer = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(queueCapacity.also {
            require(it > 0) { "WAV writer queue capacity must be positive" }
        }),
        { task -> Thread(task, WRITER_THREAD_NAME) },
        ThreadPoolExecutor.AbortPolicy(),
    )

    private var state = State.NEW
    private var closed = false
    private var outputFile: File? = null
    private var outputHandle: RandomAccessFile? = null
    private var format: PcmFormat? = null
    private var acceptedDataBytes = 0L
    private var recordingFailed = false
    private var finalizedResult: File? = null

    /**
     * 开始一段录音并立即写入可被最终修补的 44-byte WAV 头。
     *
     * 同一个实例只能调用成功一次；[stop] 或 [close] 之后应创建新实例。
     */
    fun start(output: File, format: PcmFormat) {
        synchronized(lock) {
            check(!closed) { "WAV recorder is closed" }
            check(state == State.NEW) { "WAV recorder can only be started once" }

            val absoluteOutput = output.absoluteFile
            var openedHandle: RandomAccessFile? = null
            val handle = try {
                RandomAccessFile(absoluteOutput, "rw").also { file ->
                    openedHandle = file
                    file.setLength(0L)
                    file.write(wavHeader(format = format, dataSize = 0L))
                }
            } catch (error: Throwable) {
                runCatching { openedHandle?.close() }
                state = State.STOPPED
                recordingFailed = true
                writer.shutdown()
                absoluteOutput.delete()
                finalizationFinished.countDown()
                throw error
            }

            outputFile = absoluteOutput
            outputHandle = handle
            this.format = format
            state = State.RECORDING
        }
    }

    /**
     * 非阻塞地接收一帧 PCM。返回 `true` 只表示该帧已进入有界写盘流程。
     *
     * 为避免 SDK 回调结束后调用方复用或修改数组，这里会先快速复制数据。队列已满时不会
     * 等待，而是立即返回 `false` 并使整段 WAV 失败。
     */
    fun append(frame: PcmFrame): Boolean = synchronized(lock) {
        if (closed || state != State.RECORDING || recordingFailed) return false

        val recordingFormat = requireNotNull(format)
        if (!frame.matches(recordingFormat)) return failRecording()

        val blockAlign = recordingFormat.blockAlign()
        if (frame.data.size % blockAlign != 0) return failRecording()

        val ownedData = frame.data.copyOf()
        if (ownedData.size.toLong() > MAX_WAV_DATA_BYTES - acceptedDataBytes) {
            return failRecording()
        }

        return try {
            writer.execute {
                try {
                    if (isRecordingFailed()) return@execute
                    beforeWrite(ownedData)
                    if (isRecordingFailed()) return@execute
                    requireNotNull(outputHandle).write(ownedData)
                    writtenDataBytes.addAndGet(ownedData.size.toLong())
                } catch (_: Throwable) {
                    markRecordingFailed()
                }
            }
            acceptedDataBytes += ownedData.size.toLong()
            true
        } catch (_: RejectedExecutionException) {
            failRecording()
        } catch (_: Throwable) {
            failRecording()
        }
    }

    /**
     * 停止接收新帧，等待已接收的数据按顺序写完，再修补 WAV 长度并关闭文件。
     *
     * 该调用可能等待磁盘线程，Activity 必须把它放到后台 I/O 路径。重复调用是幂等的。
     */
    fun stop(): File? {
        val ownsFinalization = synchronized(lock) {
            when (state) {
                State.NEW -> {
                    state = State.STOPPED
                    writer.shutdown()
                    finalizationFinished.countDown()
                    return null
                }

                State.RECORDING -> {
                    state = State.STOPPING
                    writer.shutdown()
                    true
                }

                State.STOPPING -> false
                State.STOPPED -> return finalizedResult
            }
        }

        if (!ownsFinalization) {
            awaitFinalization()
            return synchronized(lock) { finalizedResult }
        }

        val result = finalizeFile()
        synchronized(lock) {
            finalizedResult = result
            state = State.STOPPED
        }
        finalizationFinished.countDown()
        return result
    }

    /** 完成仍在进行的录音，并保证唯一写盘线程退出。 */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        stop()
        awaitWriterTermination()
    }

    private fun finalizeFile(): File? {
        awaitWriterTermination()

        val handle = synchronized(lock) { outputHandle }
        val output = synchronized(lock) { outputFile }
        val recordingFormat = synchronized(lock) { format }

        var successful = !isRecordingFailed()
        try {
            if (successful) {
                val dataSize = writtenDataBytes.get()
                if (dataSize != acceptedDataBytes || dataSize > MAX_WAV_DATA_BYTES) {
                    successful = false
                } else {
                    requireNotNull(handle)
                    requireNotNull(recordingFormat)
                    handle.seek(0L)
                    handle.write(wavHeader(recordingFormat, dataSize))
                    handle.setLength(WAV_HEADER_SIZE + dataSize)
                    handle.fd.sync()
                }
            }
        } catch (_: Throwable) {
            successful = false
        } finally {
            try {
                handle?.close()
            } catch (_: Throwable) {
                successful = false
            }
            synchronized(lock) { outputHandle = null }
        }

        if (!successful) {
            output?.delete()
            return null
        }
        return output
    }

    private fun awaitWriterTermination() {
        var interrupted = false
        while (!writer.isTerminated) {
            try {
                writer.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
                markRecordingFailed()
                writer.shutdownNow()
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun awaitFinalization() {
        var interrupted = false
        while (true) {
            try {
                finalizationFinished.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun failRecording(): Boolean {
        recordingFailed = true
        return false
    }

    private fun markRecordingFailed() {
        synchronized(lock) { recordingFailed = true }
    }

    private fun isRecordingFailed(): Boolean = synchronized(lock) { recordingFailed }

    private enum class State {
        NEW,
        RECORDING,
        STOPPING,
        STOPPED,
    }

    private companion object {
        const val DEFAULT_QUEUE_CAPACITY = 32
        const val WRITER_THREAD_NAME = "Glass3-WavWriter"
        const val WAV_HEADER_SIZE = 44L
        const val MAX_WAV_DATA_BYTES = UINT32_MAX - 36L
    }
}

private fun PcmFrame.matches(format: PcmFormat): Boolean =
    sampleRateHz == format.sampleRateHz &&
        channelCount == format.channelCount &&
        bitsPerSample == format.bitsPerSample

private fun PcmFormat.blockAlign(): Int = channelCount * (bitsPerSample / BITS_PER_BYTE)

private fun wavHeader(format: PcmFormat, dataSize: Long): ByteArray {
    require(dataSize in 0..MAX_WAV_DATA_BYTES_FOR_HEADER) { "WAV data is too large" }
    val blockAlign = format.blockAlign()
    val byteRate = format.sampleRateHz.toLong() * blockAlign

    return ByteBuffer.allocate(WAV_HEADER_BYTE_COUNT)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray(StandardCharsets.US_ASCII))
        .putInt((36L + dataSize).toInt())
        .put("WAVE".toByteArray(StandardCharsets.US_ASCII))
        .put("fmt ".toByteArray(StandardCharsets.US_ASCII))
        .putInt(16)
        .putShort(1)
        .putShort(format.channelCount.toShort())
        .putInt(format.sampleRateHz)
        .putInt(byteRate.toInt())
        .putShort(blockAlign.toShort())
        .putShort(format.bitsPerSample.toShort())
        .put("data".toByteArray(StandardCharsets.US_ASCII))
        .putInt(dataSize.toInt())
        .array()
}

private const val PCM16_BITS = 16
private const val BITS_PER_BYTE = 8
private const val WAV_HEADER_BYTE_COUNT = 44
private const val UINT32_MAX = 0xffff_ffffL
private const val USHORT_MAX = 0xffffL
private const val MAX_WAV_DATA_BYTES_FOR_HEADER = UINT32_MAX - 36L
