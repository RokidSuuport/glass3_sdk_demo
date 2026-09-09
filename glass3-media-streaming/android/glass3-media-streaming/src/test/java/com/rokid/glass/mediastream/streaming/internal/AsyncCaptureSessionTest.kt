package com.rokid.glass.mediastream.streaming.internal

import com.rokid.glass.mediastream.capture.AudioFrameListener
import com.rokid.glass.mediastream.capture.CaptureOptions
import com.rokid.glass.mediastream.capture.CaptureState
import com.rokid.glass.mediastream.capture.CaptureStatus
import com.rokid.glass.mediastream.capture.CaptureStatusListener
import com.rokid.glass.mediastream.capture.VideoFrameListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsyncCaptureSessionTest {
    @Test
    fun `slow capture operations are queued and never run on the caller`() {
        val delegate = RecordingCaptureSession()
        val queue = ManualCaptureOperationQueue()
        val session = AsyncCaptureSession(delegate, queue)

        session.start(CaptureOptions(), null, null, null)
        session.stop()
        session.release()

        assertEquals(emptyList<String>(), delegate.calls)
        assertFalse(queue.shutdown)

        queue.runNext()
        assertEquals(listOf("start"), delegate.calls)
        queue.runNext()
        assertEquals(listOf("start", "stop"), delegate.calls)
        queue.runNext()
        assertEquals(listOf("start", "stop", "release"), delegate.calls)
        assertTrue(queue.shutdown)
    }

    @Test
    fun `restart waits for the Glass media service to settle after a completed stop`() {
        val delegate = RecordingCaptureSession()
        val queue = ManualCaptureOperationQueue()
        var nowMs = 10_000L
        val slept = mutableListOf<Long>()
        val session = AsyncCaptureSession(
            delegate = delegate,
            operations = queue,
            minimumRestartDelayMs = 5_000L,
            monotonicTimeMs = { nowMs },
            sleep = { delay ->
                slept += delay
                nowMs += delay
            },
        )

        session.stop()
        queue.runNext()
        nowMs += 1_250L
        session.start(CaptureOptions(), null, null, null)
        queue.runNext()

        assertEquals(listOf(3_750L), slept)
        assertEquals(listOf("stop", "start"), delegate.calls)
    }

    private class ManualCaptureOperationQueue : CaptureOperationQueue {
        private val pending = ArrayDeque<() -> Unit>()
        var shutdown = false

        override fun execute(operation: () -> Unit) {
            pending.addLast(operation)
        }

        override fun shutdown() {
            shutdown = true
        }

        fun runNext() = pending.removeFirst().invoke()
    }

    private class RecordingCaptureSession : CaptureSession {
        val calls = mutableListOf<String>()

        override fun start(
            options: CaptureOptions,
            videoListener: VideoFrameListener?,
            audioListener: AudioFrameListener?,
            statusListener: CaptureStatusListener?,
        ) {
            calls += "start"
        }

        override fun stop() {
            calls += "stop"
        }

        override fun release() {
            calls += "release"
        }

        override fun currentStatus() = CaptureStatus(CaptureState.IDLE)
    }
}
