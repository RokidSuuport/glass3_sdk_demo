package com.rokid.glass.mediastream.capture

import com.rokid.glass.mediastream.capture.internal.CaptureController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class CaptureLifecycleTest {
    @Test
    fun `public lifecycle returns promptly while vendor startup is blocked and reports release only after cleanup`() {
        val allowStart = CountDownLatch(1)
        val delegate = Controller(startAction = { check(allowStart.await(2, TimeUnit.SECONDS)) })
        val capture = GlassMediaCapture.create(delegate)
        val started = daemon { capture.start(VideoFrameListener {}, null) }
        try {
            assertTrue(delegate.started.await(1, TimeUnit.SECONDS))
            started.get(200, TimeUnit.MILLISECONDS)
            assertEquals(CaptureState.PREPARING, capture.currentStatus().state)
            daemon { capture.stop(); capture.release() }.get(200, TimeUnit.MILLISECONDS)
            assertEquals(CaptureState.STOPPING, capture.currentStatus().state)
            assertEquals(1L, delegate.released.count)
        } finally { allowStart.countDown() }
        assertTrue(delegate.released.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun `a stop during vendor teardown cancels a queued restart`() {
        val allowStop = CountDownLatch(1)
        val delegate = Controller(stopAction = { check(allowStop.await(2, TimeUnit.SECONDS)) })
        val capture = GlassMediaCapture.create(delegate)
        capture.start(VideoFrameListener {}, null)
        assertTrue(delegate.started.await(1, TimeUnit.SECONDS))
        val stopped = daemon { capture.stop() }
        try {
            assertTrue(delegate.stopped.await(1, TimeUnit.SECONDS))
            stopped.get(200, TimeUnit.MILLISECONDS)
            capture.start(VideoFrameListener {}, null)
            capture.stop()
            capture.release()
        } finally { allowStop.countDown() }
        assertTrue(delegate.released.await(1, TimeUnit.SECONDS))
        assertEquals(1, delegate.starts.get())
    }

    @Test
    fun `release cancels a restart in the recovery window without sleeping on the worker`() {
        val delegate = Controller()
        val capture = GlassMediaCapture.create(delegate)
        capture.start(VideoFrameListener {}, null)
        assertTrue(delegate.started.await(1, TimeUnit.SECONDS))
        capture.stop()
        assertTrue(delegate.stopped.await(1, TimeUnit.SECONDS))
        capture.start(VideoFrameListener {}, null)
        capture.release()
        assertTrue("release must not wait five seconds for a stale restart", delegate.released.await(1, TimeUnit.SECONDS))
        assertEquals(1, delegate.starts.get())
        assertThrows(IllegalStateException::class.java) { capture.start(VideoFrameListener {}, null) }
    }

    private class Controller(
        private val startAction: () -> Unit = {},
        private val stopAction: () -> Unit = {},
    ) : CaptureController {
        val starts = AtomicInteger()
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val released = CountDownLatch(1)
        override fun start(options: CaptureOptions, videoListener: VideoFrameListener?, audioListener: AudioFrameListener?, statusListener: CaptureStatusListener?) {
            starts.incrementAndGet()
            started.countDown()
            startAction()
        }
        override fun stop() { stopped.countDown(); stopAction() }
        override fun release() { released.countDown() }
        override fun currentStatus() = CaptureStatus(CaptureState.IDLE)
    }
    private fun daemon(action: () -> Unit): FutureTask<Unit> = FutureTask(action).also {
        Thread(it).apply { isDaemon = true }.start()
    }
}
