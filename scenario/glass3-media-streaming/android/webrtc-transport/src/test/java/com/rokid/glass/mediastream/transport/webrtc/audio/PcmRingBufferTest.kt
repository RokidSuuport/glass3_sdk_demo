package com.rokid.glass.mediastream.transport.webrtc.audio

import com.rokid.glass.mediastream.capture.PcmFrame
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PcmRingBufferTest {
    @Test
    fun `producer and consumer chunk sizes can differ without reordering samples`() {
        val buffer = PcmRingBuffer(capacityBytes = 16, bytesPerSecond = BYTES_PER_SECOND)
        buffer.write(pcmFrame(byteArrayOf(1, 2, 3, 4), timestampNs = 1_000_000L))
        buffer.write(pcmFrame(byteArrayOf(5, 6, 7, 8, 9, 10), timestampNs = 2_000_000L))

        val firstTarget = ByteBuffer.allocateDirect(6)
        val first = buffer.readInto(firstTarget, requestedBytes = 6, fallbackTimeNs = 99L)
        val secondTarget = ByteBuffer.allocateDirect(4)
        val second = buffer.readInto(secondTarget, requestedBytes = 4, fallbackTimeNs = 99L)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), firstTarget.bytes(6))
        assertArrayEquals(byteArrayOf(7, 8, 9, 10), secondTarget.bytes(4))
        assertEquals(6, first.copiedBytes)
        assertEquals(4, second.copiedBytes)
        assertEquals(1_000_000L, first.captureTimeNs)
        assertEquals(1_000_000L + nanosForBytes(6), second.captureTimeNs)
    }

    @Test
    fun `overflow drops oldest aligned samples to keep latency bounded`() {
        val buffer = PcmRingBuffer(capacityBytes = 8, bytesPerSecond = BYTES_PER_SECOND)
        buffer.write(pcmFrame(byteArrayOf(1, 2, 3, 4, 5, 6), 100L))

        val dropped = buffer.write(pcmFrame(byteArrayOf(7, 8, 9, 10, 11, 12), 200L))
        val target = ByteBuffer.allocateDirect(8)
        val result = buffer.readInto(target, 8, 999L)

        assertEquals(4, dropped)
        assertArrayEquals(byteArrayOf(5, 6, 7, 8, 9, 10, 11, 12), target.bytes(8))
        assertEquals(0, result.silenceBytes)
        assertEquals(100L + nanosForBytes(4), result.captureTimeNs)
        assertEquals(4L, buffer.metrics().droppedBytes)
    }

    @Test
    fun `frame larger than capacity keeps its newest aligned samples and timestamp`() {
        val buffer = PcmRingBuffer(capacityBytes = 6, bytesPerSecond = BYTES_PER_SECOND)

        val dropped = buffer.write(
            pcmFrame(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), timestampNs = 2_000_000L),
        )
        val target = ByteBuffer.allocateDirect(6)
        val result = buffer.readInto(target, 6, fallbackTimeNs = 99L)

        assertEquals(4, dropped)
        assertArrayEquals(byteArrayOf(5, 6, 7, 8, 9, 10), target.bytes(6))
        assertEquals(2_000_000L + nanosForBytes(4), result.captureTimeNs)
    }

    @Test
    fun `underrun fills remaining bytes with silence`() {
        val buffer = PcmRingBuffer(320, BYTES_PER_SECOND)
        buffer.write(pcmFrame(byteArrayOf(1, 2), 500L))
        val target = ByteBuffer.allocateDirect(6)

        val result = buffer.readInto(target, 6, 999L)

        assertArrayEquals(byteArrayOf(1, 2, 0, 0, 0, 0), target.bytes(6))
        assertEquals(2, result.copiedBytes)
        assertEquals(4, result.silenceBytes)
        assertEquals(500L, result.captureTimeNs)
        assertEquals(4L, buffer.metrics().silenceBytes)
    }

    @Test
    fun `empty read uses fallback timestamp and fills the entire frame with silence`() {
        val buffer = PcmRingBuffer(320, BYTES_PER_SECOND)
        val target = ByteBuffer.allocateDirect(4)

        val result = buffer.readInto(target, 4, fallbackTimeNs = 123_456L)

        assertArrayEquals(byteArrayOf(0, 0, 0, 0), target.bytes(4))
        assertEquals(0, result.copiedBytes)
        assertEquals(4, result.silenceBytes)
        assertEquals(123_456L, result.captureTimeNs)
    }

    @Test
    fun `clear discards buffered samples and resets the next timestamp`() {
        val buffer = PcmRingBuffer(8, BYTES_PER_SECOND)
        buffer.write(pcmFrame(byteArrayOf(1, 2, 3, 4), timestampNs = 100L))

        buffer.clear()
        buffer.write(pcmFrame(byteArrayOf(7, 8), timestampNs = 900L))
        val target = ByteBuffer.allocateDirect(2)
        val result = buffer.readInto(target, 2, fallbackTimeNs = 999L)

        assertArrayEquals(byteArrayOf(7, 8), target.bytes(2))
        assertEquals(900L, result.captureTimeNs)
        assertEquals(0, buffer.metrics().bufferedBytes)
    }

    @Test
    fun `reads and writes reject half pcm16 samples`() {
        val buffer = PcmRingBuffer(8, BYTES_PER_SECOND)

        assertThrows(IllegalArgumentException::class.java) {
            buffer.write(pcmFrame(byteArrayOf(1), timestampNs = 10L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            buffer.readInto(ByteBuffer.allocateDirect(3), 3, fallbackTimeNs = 10L)
        }
    }

    @Test
    fun `empty read performs exactly one controlled ten millisecond wait before producing silence`() {
        val clock = MutableNanoClock()
        val waitCondition = ControlledPcmWaitCondition(clock)
        val buffer = PcmRingBuffer(
            capacityBytes = 320,
            bytesPerSecond = BYTES_PER_SECOND,
            clock = clock,
            waitConditionFactory = { waitCondition },
        )

        val result = buffer.readInto(
            target = ByteBuffer.allocateDirect(320),
            requestedBytes = 320,
            fallbackTimeNs = 77L,
            waitForDataNs = TimeUnit.MILLISECONDS.toNanos(10),
        )

        assertEquals(320, result.silenceBytes)
        assertEquals(listOf(TimeUnit.MILLISECONDS.toNanos(10)), waitCondition.awaitRequestsNs)
        assertEquals(TimeUnit.MILLISECONDS.toNanos(10), clock.nowNs())
    }

    @Test
    fun `producer signal ends the controlled wait before its deadline`() {
        val clock = MutableNanoClock()
        val waitCondition = ControlledPcmWaitCondition(clock)
        lateinit var buffer: PcmRingBuffer
        buffer = PcmRingBuffer(
            capacityBytes = 320,
            bytesPerSecond = BYTES_PER_SECOND,
            clock = clock,
            waitConditionFactory = { waitCondition },
        )
        waitCondition.onAwait = { requestedNs ->
            buffer.write(pcmFrame(byteArrayOf(1, 2, 3, 4), timestampNs = 700L))
            requestedNs
        }

        val result = buffer.readInto(
            target = ByteBuffer.allocateDirect(4),
            requestedBytes = 4,
            fallbackTimeNs = 99L,
            waitForDataNs = TimeUnit.MILLISECONDS.toNanos(10),
        )

        assertEquals(4, result.copiedBytes)
        assertEquals(0, result.silenceBytes)
        assertEquals(700L, result.captureTimeNs)
        assertEquals(listOf(TimeUnit.MILLISECONDS.toNanos(10)), waitCondition.awaitRequestsNs)
        assertEquals(1, waitCondition.signalCount)
        assertEquals(0L, clock.nowNs())
    }

    @Test
    fun `close signals the controlled wait immediately and prevents reopening`() {
        val clock = MutableNanoClock()
        val waitCondition = ControlledPcmWaitCondition(clock)
        lateinit var buffer: PcmRingBuffer
        buffer = PcmRingBuffer(
            capacityBytes = 320,
            bytesPerSecond = BYTES_PER_SECOND,
            clock = clock,
            waitConditionFactory = { waitCondition },
        )
        waitCondition.onAwait = { requestedNs ->
            buffer.close()
            requestedNs
        }

        val result = buffer.readInto(
            target = ByteBuffer.allocateDirect(4),
            requestedBytes = 4,
            fallbackTimeNs = 555L,
            waitForDataNs = TimeUnit.MILLISECONDS.toNanos(10),
        )

        assertEquals(4, result.silenceBytes)
        assertEquals(555L, result.captureTimeNs)
        assertEquals(listOf(TimeUnit.MILLISECONDS.toNanos(10)), waitCondition.awaitRequestsNs)
        assertEquals(1, waitCondition.signalCount)
        assertEquals(0L, clock.nowNs())
        assertEquals(4, buffer.write(pcmFrame(byteArrayOf(1, 2, 3, 4), timestampNs = 900L)))
        assertEquals(0, buffer.metrics().bufferedBytes)
    }

    @Test
    fun `default capacity holds five hundred milliseconds of mono pcm16`() {
        val buffer = PcmRingBuffer(bytesPerSecond = BYTES_PER_SECOND)
        val samples = ByteArray(16_002) { (it and 0x7f).toByte() }

        val dropped = buffer.write(pcmFrame(samples, timestampNs = 1_000L))

        assertEquals(2, dropped)
        assertEquals(16_000, buffer.metrics().bufferedBytes)
    }

    private fun pcmFrame(data: ByteArray, timestampNs: Long): PcmFrame = PcmFrame(
        data = data,
        sampleRateHz = 16_000,
        channelCount = 1,
        bitsPerSample = 16,
        timestampNs = timestampNs,
    )

    private fun nanosForBytes(byteCount: Int): Long =
        byteCount.toLong() * NANOS_PER_SECOND / BYTES_PER_SECOND

    private fun ByteBuffer.bytes(byteCount: Int): ByteArray = ByteArray(byteCount).also { output ->
        duplicate().apply {
            position(0)
            get(output)
        }
    }

    private companion object {
        const val BYTES_PER_SECOND = 32_000
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
