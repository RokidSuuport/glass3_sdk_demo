package com.rokid.glass.mediastream.capture

import com.rokid.glass.mediastream.capture.internal.CaptureController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class GlassMediaCaptureTest {
    @Test
    fun `simple start applies default options and forwards the requested media`() {
        val controller = FakeCaptureController()
        val capture = GlassMediaCapture.create(controller)
        var videos = 0
        var audios = 0
        val videoListener = VideoFrameListener { videos++ }
        val audioListener = AudioFrameListener { audios++ }

        capture.start(videoListener, audioListener)
        assertTrue(controller.started.await(1, TimeUnit.SECONDS))

        val request = controller.starts.single()
        assertEquals(CaptureOptions(), request.options)
        val frame = Nv21Frame.create(ByteArray(6), 2, 2, 1L) {}
        request.videoListener!!.onVideoFrame(frame)
        request.audioListener!!.onAudioFrame(PcmFrame(ByteArray(2), 16000, 1, 16, 1L))
        assertEquals(1, videos)
        assertEquals(1, audios)
        frame.close()
        capture.release()
    }

    @Test
    fun `advanced options survive worker dispatch and release suppresses old callbacks`() {
        val controller = FakeCaptureController()
        val capture = GlassMediaCapture.create(controller)
        val options = CaptureOptions(
            video = VideoCaptureOptions(640, 480, 24),
            startupTimeoutMs = 800L,
        )
        var videos = 0
        val videoListener = VideoFrameListener { videos++ }
        val released = CountDownLatch(1)
        val statusListener = CaptureStatusListener { if (it.state == CaptureState.RELEASED) released.countDown() }

        capture.start(options, videoListener, null, statusListener)
        assertTrue(controller.started.await(1, TimeUnit.SECONDS))
        capture.stop()
        capture.release()
        assertTrue(released.await(1, TimeUnit.SECONDS))

        val request = controller.starts.single()
        assertSame(options, request.options)
        assertNull(request.audioListener)
        assertEquals(1, controller.stopCount)
        assertEquals(1, controller.releaseCount)
        assertEquals(CaptureState.RELEASED, capture.currentStatus().state)
        val frame = Nv21Frame.create(ByteArray(6), 2, 2, 1L) {}
        request.videoListener!!.onVideoFrame(frame)
        assertEquals(0, videos)
        frame.close()
    }

    private data class StartRequest(
        val options: CaptureOptions,
        val videoListener: VideoFrameListener?,
        val audioListener: AudioFrameListener?,
        val statusListener: CaptureStatusListener?,
    )

    private class FakeCaptureController : CaptureController {
        val starts = CopyOnWriteArrayList<StartRequest>()
        val started = CountDownLatch(1)
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
            started.countDown()
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
