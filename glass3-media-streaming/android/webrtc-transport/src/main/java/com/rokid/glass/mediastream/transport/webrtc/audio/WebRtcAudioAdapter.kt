package com.rokid.glass.mediastream.transport.webrtc.audio

import android.media.AudioFormat
import com.rokid.glass.mediastream.capture.AudioFrameListener
import com.rokid.glass.mediastream.capture.PcmFrame
import java.nio.ByteBuffer
import livekit.org.webrtc.audio.JavaAudioDeviceModule.AudioBufferCallback

internal fun interface NanoClock {
    fun nowNs(): Long
}

internal class WebRtcAudioAdapter(
    private val ringBuffer: PcmRingBuffer,
    private val clock: NanoClock = NanoClock(System::nanoTime),
) : AudioBufferCallback, AudioFrameListener, AutoCloseable {
    override fun onAudioFrame(frame: PcmFrame) {
        require(frame.sampleRateHz == SAMPLE_RATE_HZ) { "Glass PCM must use 16 kHz" }
        require(frame.channelCount == CHANNEL_COUNT) { "Glass PCM must be mono" }
        require(frame.bitsPerSample == BITS_PER_SAMPLE) { "Glass PCM must use two-byte PCM16 samples" }
        ringBuffer.write(frame)
    }

    override fun onBuffer(
        buffer: ByteBuffer,
        audioFormat: Int,
        channelCount: Int,
        sampleRate: Int,
        bytesRead: Int,
        captureTimeNs: Long,
    ): Long {
        require(audioFormat == AudioFormat.ENCODING_PCM_16BIT) { "WebRTC audio must be PCM16" }
        require(channelCount == CHANNEL_COUNT) { "WebRTC audio must be mono" }
        require(sampleRate == SAMPLE_RATE_HZ) { "WebRTC audio must use 16 kHz" }

        buffer.clear()
        val frameDurationNs = buffer.capacity().toLong() * NANOS_PER_SECOND / BYTES_PER_SECOND
        val result = ringBuffer.readInto(
            target = buffer,
            requestedBytes = buffer.capacity(),
            fallbackTimeNs = clock.nowNs(),
            waitForDataNs = frameDurationNs,
        )
        buffer.position(0)
        return result.captureTimeNs
    }

    override fun close() {
        ringBuffer.close()
    }

    internal fun metrics(): PcmBufferMetrics = ringBuffer.metrics()

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val CHANNEL_COUNT = 1
        const val BITS_PER_SAMPLE = 16
        const val BYTES_PER_SECOND = 32_000
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
