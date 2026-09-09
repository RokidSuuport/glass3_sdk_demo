package com.rokid.glass.mediastream.capture

import com.rokid.glass.mediastream.capture.internal.CaptureController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class GlassMediaCaptureTest {
    @Test
    fun `simple start delegates with default options and no status listener`() {
        val controller = FakeCaptureController()
        val capture = GlassMediaCapture.create(controller)
        val videoListener = VideoFrameListener { }
        val audioListener = AudioFrameListener { }

        capture.start(videoListener, audioListener)

        val request = controller.starts.single()
        assertEquals(CaptureOptions(), request.options)
        assertSame(videoListener, request.videoListener)
        assertSame(audioListener, request.audioListener)
        assertNull(request.statusListener)
    }

    @Test
    fun `advanced start and lifecycle methods delegate without changing callbacks`() {
        val controller = FakeCaptureController()
        val capture = GlassMediaCapture.create(controller)
        val options = CaptureOptions(
            video = VideoCaptureOptions(640, 480, 24),
            startupTimeoutMs = 800L,
        )
        val videoListener = VideoFrameListener { }
        val statusListener = CaptureStatusListener { }

        capture.start(options, videoListener, null, statusListener)
        capture.stop()
        capture.release()

        val request = controller.starts.single()
        assertSame(options, request.options)
        assertSame(videoListener, request.videoListener)
        assertNull(request.audioListener)
        assertSame(statusListener, request.statusListener)
        assertEquals(1, controller.stopCount)
        assertEquals(1, controller.releaseCount)
        assertSame(controller.status, capture.currentStatus())
    }

    private data class StartRequest(
        val options: CaptureOptions,
        val videoListener: VideoFrameListener?,
        val audioListener: AudioFrameListener?,
        val statusListener: CaptureStatusListener?,
    )

    private class FakeCaptureController : CaptureController {
        val starts = mutableListOf<StartRequest>()
        var stopCount = 0
        var releaseCount = 0
        val status = CaptureStatus(CaptureState.IDLE)

        override fun start(
            options: CaptureOptions,
            videoListener: VideoFrameListener?,
            audioListener: AudioFrameListener?,
            statusListener: CaptureStatusListener?,
        ) {
            starts += StartRequest(options, videoListener, audioListener, statusListener)
        }

        override fun stop() {
            stopCount += 1
        }

        override fun release() {
            releaseCount += 1
        }

        override fun currentStatus(): CaptureStatus = status
    }
}
