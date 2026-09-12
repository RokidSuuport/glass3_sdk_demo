package com.rokid.glass.mediastream.capture

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class VideoCaptureOptions @JvmOverloads constructor(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 15,
    val enableMix: Boolean = false,
    val enableVideoStabilization: Boolean = false,
) {
    init {
        require(width > 0 && height > 0) { "Video dimensions must be positive" }
        require(width % 2 == 0 && height % 2 == 0) { "NV21 dimensions must be even" }
        require(fps in 1..60) { "Video fps must be between 1 and 60" }
    }
}

data class AudioCaptureOptions @JvmOverloads constructor(
    val sampleRateHz: Int = 16_000,
    val channelCount: Int = 1,
    val bitsPerSample: Int = 16,
) {
    init {
        require(sampleRateHz == 16_000) { "Glass3 PCM sample rate must be 16000 Hz" }
        require(channelCount == 1) { "Glass3 PCM must be mono" }
        require(bitsPerSample == 16) { "Audio format must be 16-bit PCM" }
    }
}

data class CaptureOptions @JvmOverloads constructor(
    val video: VideoCaptureOptions = VideoCaptureOptions(),
    val audio: AudioCaptureOptions = AudioCaptureOptions(),
    // Glass3 系统录音器在冷启动自检后可能需要约 8 秒完成一次内部重建。
    // 12 秒既能覆盖该恢复窗口，也能保证硬件异常时有明确的等待上限。
    val startupTimeoutMs: Long = 12_000L,
) {
    init {
        require(startupTimeoutMs > 0L) { "Startup timeout must be positive" }
    }
}

data class VideoCaptureMetrics(
    val width: Int = 0,
    val height: Int = 0,
    val frameCount: Long = 0L,
    val droppedFrames: Long = 0L,
    val bytesReceived: Long = 0L,
    val fps: Double = 0.0,
)

data class AudioCaptureMetrics(
    val sampleRateHz: Int = 0,
    val channelCount: Int = 0,
    val bitsPerSample: Int = 0,
    val frameCount: Long = 0L,
    val bytesReceived: Long = 0L,
)

class Nv21Frame private constructor(
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val timestampNs: Long,
    private val lease: SharedFrameLease,
) : AutoCloseable {
    private val frameByteCount = nv21ByteCount(width, height)
    private val closed = AtomicBoolean(false)

    init {
        require(data === lease.data) { "Frame data must belong to its lease" }
        require(data.size >= frameByteCount) { "NV21 data is shorter than the declared frame size" }
    }

    fun copyData(): ByteArray = data.copyOf(frameByteCount)

    fun retain(): Nv21Frame {
        check(!closed.get()) { "Cannot retain a closed frame" }
        lease.retain()
        if (closed.get()) {
            lease.release()
            error("Cannot retain a closed frame")
        }
        return Nv21Frame(data, width, height, timestampNs, lease)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            lease.release()
        }
    }

    internal companion object {
        @JvmSynthetic
        fun create(
            data: ByteArray,
            width: Int,
            height: Int,
            timestampNs: Long,
            finalRelease: (ByteArray) -> Unit,
        ): Nv21Frame = Nv21Frame(
            data = data,
            width = width,
            height = height,
            timestampNs = timestampNs,
            lease = SharedFrameLease(data, finalRelease),
        )

        private fun nv21ByteCount(width: Int, height: Int): Int {
            require(width > 0 && height > 0) { "NV21 dimensions must be positive" }
            require(width % 2 == 0 && height % 2 == 0) { "NV21 dimensions must be even" }
            val byteCount = try {
                val pixelCount = Math.multiplyExact(width.toLong(), height.toLong())
                Math.multiplyExact(pixelCount, 3L) / 2L
            } catch (error: ArithmeticException) {
                throw IllegalArgumentException("NV21 frame is too large", error)
            }
            require(byteCount <= Int.MAX_VALUE) { "NV21 frame is too large" }
            return byteCount.toInt()
        }
    }

    private class SharedFrameLease(
        val data: ByteArray,
        private val finalRelease: (ByteArray) -> Unit,
    ) {
        private val referenceCount = AtomicInteger(1)

        fun retain() {
            while (true) {
                val current = referenceCount.get()
                check(current > 0) { "Cannot retain a released frame lease" }
                check(current < Int.MAX_VALUE) { "Frame lease reference count overflow" }
                if (referenceCount.compareAndSet(current, current + 1)) return
            }
        }

        fun release() {
            while (true) {
                val current = referenceCount.get()
                check(current > 0) { "Frame lease is already released" }
                val remaining = current - 1
                if (referenceCount.compareAndSet(current, remaining)) {
                    if (remaining == 0) finalRelease(data)
                    return
                }
            }
        }
    }
}

data class PcmFrame(
    val data: ByteArray,
    val sampleRateHz: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val timestampNs: Long,
)

fun interface VideoFrameListener {
    fun onVideoFrame(frame: Nv21Frame)
}

fun interface AudioFrameListener {
    fun onAudioFrame(frame: PcmFrame)
}
