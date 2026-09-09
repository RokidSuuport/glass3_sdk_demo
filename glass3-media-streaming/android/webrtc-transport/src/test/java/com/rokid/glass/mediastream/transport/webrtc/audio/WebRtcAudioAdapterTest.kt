package com.rokid.glass.mediastream.transport.webrtc.audio

import android.media.AudioFormat
import com.rokid.glass.mediastream.capture.PcmFrame
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WebRtcAudioAdapterTest {
    @Test
    fun `a 320 byte empty callback requests exactly one ten millisecond wait`() {
        val waitClock = MutableNanoClock()
        val waitCondition = ControlledPcmWaitCondition(waitClock)
        val ringBuffer = PcmRingBuffer(
            capacityBytes = 16_000,
            bytesPerSecond = 32_000,
            clock = waitClock,
            waitConditionFactory = { waitCondition },
        )
        val adapter = WebRtcAudioAdapter(ringBuffer, NanoClock { 123_456L })

        val timestamp = adapter.onBuffer(
            ByteBuffer.allocateDirect(320),
            AudioFormat.ENCODING_PCM_16BIT,
            1,
            16_000,
            0,
            0L,
        )

        assertEquals(123_456L, timestamp)
        assertEquals(listOf(TimeUnit.MILLISECONDS.toNanos(10)), waitCondition.awaitRequestsNs)
    }

    @Test
    fun `glass pcm is returned through the exact webrtc callback with capture timestamp and silence`() {
        val ringBuffer = PcmRingBuffer(capacityBytes = 16_000, bytesPerSecond = 32_000)
        val adapter = WebRtcAudioAdapter(ringBuffer, NanoClock { 9_999L })
        adapter.onAudioFrame(pcmFrame(byteArrayOf(1, 2, 3, 4), timestampNs = 700L))
        val target = ByteBuffer.allocateDirect(320)

        val timestamp = adapter.onBuffer(
            target,
            AudioFormat.ENCODING_PCM_16BIT,
            1,
            16_000,
            0,
            0L,
        )

        assertEquals(700L, timestamp)
        assertEquals(0, target.position())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), target.bytes(4))
        assertArrayEquals(ByteArray(316), target.bytes(320).copyOfRange(4, 320))
        assertEquals(316L, ringBuffer.metrics().silenceBytes)
    }

    @Test
    fun `empty webrtc callback uses the adapter clock and returns one silent frame`() {
        val ringBuffer = PcmRingBuffer(capacityBytes = 16_000, bytesPerSecond = 32_000)
        val adapter = WebRtcAudioAdapter(ringBuffer, NanoClock { 123_456L })
        val target = ByteBuffer.allocateDirect(320)

        val timestamp = adapter.onBuffer(
            target,
            AudioFormat.ENCODING_PCM_16BIT,
            1,
            16_000,
            19,
            88L,
        )

        assertEquals(123_456L, timestamp)
        assertEquals(0, target.position())
        assertArrayEquals(ByteArray(320), target.bytes(320))
    }

    @Test
    fun `webrtc callback rejects every format except 16 khz mono pcm16`() {
        val adapter = WebRtcAudioAdapter(
            PcmRingBuffer(capacityBytes = 320, bytesPerSecond = 32_000),
            NanoClock { 1L },
        )

        assertThrows(IllegalArgumentException::class.java) {
            adapter.onBuffer(ByteBuffer.allocateDirect(320), 99, 1, 16_000, 0, 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            adapter.onBuffer(
                ByteBuffer.allocateDirect(320),
                AudioFormat.ENCODING_PCM_16BIT,
                2,
                16_000,
                0,
                0L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            adapter.onBuffer(
                ByteBuffer.allocateDirect(320),
                AudioFormat.ENCODING_PCM_16BIT,
                1,
                48_000,
                0,
                0L,
            )
        }
    }

    @Test
    fun `glass callback rejects 8 khz stereo even though its byte rate matches 16 khz mono`() {
        val ringBuffer = PcmRingBuffer(capacityBytes = 320, bytesPerSecond = 32_000)
        val adapter = WebRtcAudioAdapter(ringBuffer, NanoClock { 1L })

        assertThrows(IllegalArgumentException::class.java) {
            adapter.onAudioFrame(
                pcmFrame(
                    data = byteArrayOf(1, 2, 3, 4),
                    timestampNs = 700L,
                    sampleRateHz = 8_000,
                    channelCount = 2,
                ),
            )
        }
        assertEquals(0, ringBuffer.metrics().bufferedBytes)
    }

    @Test
    fun `close prevents a late glass pcm callback from refilling the bridge`() {
        val ringBuffer = PcmRingBuffer(capacityBytes = 320, bytesPerSecond = 32_000)
        val adapter = WebRtcAudioAdapter(ringBuffer, NanoClock { 1L })

        adapter.close()
        adapter.close()
        adapter.onAudioFrame(pcmFrame(byteArrayOf(1, 2, 3, 4), timestampNs = 700L))

        assertEquals(0, ringBuffer.metrics().bufferedBytes)
        assertEquals(4L, ringBuffer.metrics().droppedBytes)
    }

    private fun pcmFrame(
        data: ByteArray,
        timestampNs: Long,
        sampleRateHz: Int = 16_000,
        channelCount: Int = 1,
        bitsPerSample: Int = 16,
    ): PcmFrame = PcmFrame(
        data = data,
        sampleRateHz = sampleRateHz,
        channelCount = channelCount,
        bitsPerSample = bitsPerSample,
        timestampNs = timestampNs,
    )

    private fun ByteBuffer.bytes(byteCount: Int): ByteArray = ByteArray(byteCount).also { output ->
        duplicate().apply {
            position(0)
            get(output)
        }
    }
}
