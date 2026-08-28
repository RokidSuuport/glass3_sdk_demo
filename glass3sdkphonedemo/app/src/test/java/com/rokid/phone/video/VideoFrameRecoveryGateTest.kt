package com.rokid.phone.video

import java.nio.ByteBuffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFrameRecoveryGateTest {
    private val pFrame = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 0x41))
    private val idrFrame = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 0x65))
    private val sps = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 0x67))

    @Test
    fun `drops every video packet while remote stream is draining`() {
        val gate = VideoFrameRecoveryGate()
        gate.resume(VideoFrameRecoveryGate.Mode.NV21)

        gate.beginDrain()

        assertFalse(gate.shouldAcceptNv21())
        assertFalse(gate.shouldAcceptH264(idrFrame))
    }

    @Test
    fun `h264 recovery accepts codec config but waits for a fresh idr before frame slices`() {
        val gate = VideoFrameRecoveryGate()
        gate.beginDrain()
        gate.resume(VideoFrameRecoveryGate.Mode.H264)

        assertTrue(gate.shouldAcceptH264(sps))
        assertFalse(gate.shouldAcceptH264(pFrame))
        assertTrue(gate.shouldAcceptH264(idrFrame))
        assertTrue(gate.shouldAcceptH264(pFrame))
    }

    @Test
    fun `nv21 recovery opens on the first packet after drain`() {
        val gate = VideoFrameRecoveryGate()
        gate.beginDrain()
        gate.resume(VideoFrameRecoveryGate.Mode.NV21)

        assertTrue(gate.shouldAcceptNv21())
        assertFalse(gate.shouldAcceptH264(idrFrame))
    }
}
