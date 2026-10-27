package com.rokid.glass.mediastream.transport.webrtc.audio

import com.rokid.glass.mediastream.capture.PcmFrame
import java.nio.ByteBuffer
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal data class PcmReadResult(
    val copiedBytes: Int,
    val silenceBytes: Int,
    val captureTimeNs: Long,
)

internal data class PcmBufferMetrics(
    val bufferedBytes: Int = 0,
    val droppedBytes: Long = 0L,
    val silenceBytes: Long = 0L,
)

internal interface PcmWaitCondition {
    @Throws(InterruptedException::class)
    fun awaitNanos(waitForDataNs: Long): Long

    fun signalAll()
}

private class LockPcmWaitCondition(
    lock: ReentrantLock,
) : PcmWaitCondition {
    private val condition: Condition = lock.newCondition()

    override fun awaitNanos(waitForDataNs: Long): Long = condition.awaitNanos(waitForDataNs)

    override fun signalAll() = condition.signalAll()
}

internal class PcmRingBuffer(
    capacityBytes: Int = DEFAULT_CAPACITY_BYTES,
    private val bytesPerSecond: Int,
    private val clock: NanoClock = NanoClock(System::nanoTime),
    waitConditionFactory: (ReentrantLock) -> PcmWaitCondition = ::LockPcmWaitCondition,
) {
    private val lock = ReentrantLock()
    private val dataAvailable = waitConditionFactory(lock)
    private val storage: ByteArray

    private var head = 0
    private var size = 0
    private var headTimestampNs = 0L
    private var droppedBytes = 0L
    private var silenceBytes = 0L
    private var closed = false

    init {
        require(capacityBytes > 0) { "capacityBytes must be positive" }
        require(capacityBytes % PCM16_SAMPLE_BYTES == 0) {
            "capacityBytes must contain complete PCM16 samples"
        }
        require(bytesPerSecond > 0) { "bytesPerSecond must be positive" }
        storage = ByteArray(capacityBytes)
    }

    fun write(frame: PcmFrame): Int = lock.withLock {
        validateFrame(frame)
        val byteCount = frame.data.size
        if (closed) {
            droppedBytes += byteCount
            return byteCount
        }
        if (byteCount == 0) return 0

        val overflowBytes = (size.toLong() + byteCount - storage.size)
            .coerceAtLeast(0L)
            .toInt()
        var sourceOffset = 0
        if (overflowBytes > 0) {
            val bufferedDrop = minOf(overflowBytes, size)
            if (bufferedDrop > 0) {
                discardBufferedBytes(bufferedDrop)
            }
            sourceOffset = overflowBytes - bufferedDrop
            droppedBytes += overflowBytes
        }

        if (size == 0) {
            headTimestampNs = frame.timestampNs + durationNs(sourceOffset)
        }
        append(frame.data, sourceOffset, byteCount - sourceOffset)
        dataAvailable.signalAll()
        overflowBytes
    }

    fun readInto(
        target: ByteBuffer,
        requestedBytes: Int,
        fallbackTimeNs: Long,
        waitForDataNs: Long = 0L,
    ): PcmReadResult = lock.withLock {
        require(requestedBytes >= 0) { "requestedBytes must not be negative" }
        require(requestedBytes % PCM16_SAMPLE_BYTES == 0) {
            "requestedBytes must contain complete PCM16 samples"
        }
        require(requestedBytes <= target.remaining()) {
            "target does not have enough remaining space"
        }
        require(waitForDataNs >= 0L) { "waitForDataNs must not be negative" }

        awaitFirstSample(waitForDataNs)

        val copiedBytes = minOf(requestedBytes, size)
        val captureTimeNs = if (copiedBytes > 0) headTimestampNs else fallbackTimeNs
        copyTo(target, copiedBytes)
        discardBufferedBytes(copiedBytes)

        val missingBytes = requestedBytes - copiedBytes
        repeat(missingBytes) { target.put(0) }
        silenceBytes += missingBytes

        PcmReadResult(
            copiedBytes = copiedBytes,
            silenceBytes = missingBytes,
            captureTimeNs = captureTimeNs,
        )
    }

    fun clear() = lock.withLock {
        head = 0
        size = 0
        headTimestampNs = 0L
    }

    fun close() = lock.withLock {
        if (!closed) {
            closed = true
            dataAvailable.signalAll()
        }
    }

    fun metrics(): PcmBufferMetrics = lock.withLock {
        PcmBufferMetrics(
            bufferedBytes = size,
            droppedBytes = droppedBytes,
            silenceBytes = silenceBytes,
        )
    }

    private fun validateFrame(frame: PcmFrame) {
        require(frame.bitsPerSample == 16) { "Only PCM16 frames are supported" }
        require(frame.data.size % PCM16_SAMPLE_BYTES == 0) {
            "PCM frame must contain complete PCM16 samples"
        }
        val frameBytesPerSecond = try {
            Math.multiplyExact(
                Math.multiplyExact(frame.sampleRateHz, frame.channelCount),
                frame.bitsPerSample / BITS_PER_BYTE,
            )
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("PCM format byte rate is too large", error)
        }
        require(frameBytesPerSecond == bytesPerSecond) {
            "PCM frame byte rate does not match this buffer"
        }
    }

    private fun awaitFirstSample(waitForDataNs: Long) {
        val startedAtNs = clock.nowNs()
        var remainingNs = waitForDataNs
        while (size == 0 && !closed && remainingNs > 0L) {
            val conditionRemainingNs = try {
                dataAvailable.awaitNanos(remainingNs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            val elapsedNs = clock.nowNs() - startedAtNs
            val clockRemainingNs = waitForDataNs - elapsedNs
            remainingNs = minOf(conditionRemainingNs, clockRemainingNs)
        }
    }

    private fun append(source: ByteArray, sourceOffset: Int, length: Int) {
        if (length == 0) return
        val tail = (head + size) % storage.size
        val firstLength = minOf(length, storage.size - tail)
        System.arraycopy(source, sourceOffset, storage, tail, firstLength)
        val remainingLength = length - firstLength
        if (remainingLength > 0) {
            System.arraycopy(source, sourceOffset + firstLength, storage, 0, remainingLength)
        }
        size += length
    }

    private fun copyTo(target: ByteBuffer, length: Int) {
        if (length == 0) return
        val firstLength = minOf(length, storage.size - head)
        target.put(storage, head, firstLength)
        val remainingLength = length - firstLength
        if (remainingLength > 0) {
            target.put(storage, 0, remainingLength)
        }
    }

    private fun discardBufferedBytes(byteCount: Int) {
        if (byteCount == 0) return
        require(byteCount <= size) { "Cannot discard more bytes than are buffered" }
        head = (head + byteCount) % storage.size
        size -= byteCount
        if (size == 0) {
            head = 0
            headTimestampNs = 0L
        } else {
            headTimestampNs += durationNs(byteCount)
        }
    }

    private fun durationNs(byteCount: Int): Long =
        byteCount.toLong() * NANOS_PER_SECOND / bytesPerSecond

    private companion object {
        const val PCM16_SAMPLE_BYTES = 2
        const val BITS_PER_BYTE = 8
        const val DEFAULT_CAPACITY_BYTES = 16_000
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
