package com.rokid.glass.mediastream.capture.internal

import com.rokid.glass.mediastream.capture.*
import com.rokid.glass.mediastream.capture.internal.sdk.SdkConnection
import com.rokid.glass.mediastream.capture.internal.video.*
import org.junit.Assert.*
import org.junit.Test

class AsyncCaptureControllerTest {
    @Test
    fun `completed stop retains the final actual frame metrics`() {
        val fixture = Fixture()
        fixture.capture.start(CaptureOptions(), VideoFrameListener {}, null, null)
        fixture.queue.runReady()
        fixture.sdk.listener.onReady()
        fixture.queue.runReady()
        fixture.camera.callback.onFrame(ByteArray(6), 2, 2, 1L)
        fixture.camera.callback.onFrame(ByteArray(6), 2, 2, 2L)
        fixture.capture.stop()
        fixture.queue.runReady()
        assertEquals(2L, fixture.capture.currentStatus().videoMetrics.frameCount)
        assertEquals(2, fixture.capture.currentStatus().videoMetrics.width)
        fixture.capture.release()
        fixture.queue.runReady()
    }
    @Test
    fun `restart uses one five second recovery window and repeated starts do not queue sessions`() {
        val fixture = Fixture()
        fixture.capture.start(CaptureOptions(), VideoFrameListener {}, null, null)
        fixture.queue.runReady()
        assertEquals(1, fixture.sdk.binds)
        fixture.capture.stop()
        fixture.queue.runReady()
        repeat(1000) { fixture.capture.start(CaptureOptions(), VideoFrameListener {}, null, null) }
        fixture.queue.runReady()
        fixture.queue.now = 4_999
        fixture.queue.runReady()
        assertEquals(1, fixture.sdk.binds)
        fixture.queue.now = 5_000
        fixture.queue.runReady()
        assertEquals(2, fixture.sdk.binds)
        fixture.capture.release()
        fixture.queue.runReady()
        assertTrue(fixture.queue.closed)
    }

    @Test
    fun `stop invalidates a ready callback already queued behind the caller`() {
        val fixture = Fixture()
        fixture.capture.start(CaptureOptions(), VideoFrameListener {}, null, null)
        fixture.queue.runReady()
        fixture.sdk.listener.onReady()
        fixture.capture.stop()
        fixture.queue.runReady()
        assertEquals("obsolete SDK readiness must not open the camera", 0, fixture.camera.starts)
        assertEquals(CaptureState.IDLE, fixture.capture.currentStatus().state)
        fixture.capture.release()
        fixture.queue.runReady()
    }

    @Test
    fun `failure cleanup shares the lifecycle worker without blocking an error callback restart`() {
        val fixture = Fixture()
        var retried = false
        fixture.capture.start(CaptureOptions(), VideoFrameListener {}, null, CaptureStatusListener {
            if (it.state == CaptureState.ERROR && !retried) {
                retried = true
                fixture.capture.start(CaptureOptions(), VideoFrameListener {}, null, null)
            }
        })
        fixture.queue.runReady()
        fixture.sdk.listener.onReady()
        assertEquals(0, fixture.camera.starts)
        fixture.queue.runReady()
        assertEquals(1, fixture.camera.starts)
        fixture.camera.callback.onError(9, "camera failed")
        assertTrue(retried)
        assertEquals(0, fixture.camera.stops)
        assertEquals(CaptureState.PREPARING, fixture.capture.currentStatus().state)
        fixture.queue.runReady()
        assertEquals(1, fixture.camera.stops)
        assertEquals(1, fixture.sdk.unbinds)
        fixture.queue.now = 5_000
        fixture.queue.runReady()
        assertEquals(2, fixture.sdk.binds)
        fixture.capture.release()
        fixture.queue.runReady()
        assertEquals(CaptureState.RELEASED, fixture.capture.currentStatus().state)
    }

    private class Fixture {
        val queue = Queue()
        val sdk = Sdk()
        val camera = Camera()
        private val coordinator = CaptureCoordinator(
            sdk, VideoSourceFactory { GlassNv21VideoSource(camera, FrameSlotPool(2), it, Timer()) },
            AudioSourceFactory { error("no audio") }, Timer(),
            dispatchOperation = { queue.schedule(action = it) }, deferCleanupWait = true,
        )
        val capture = AsyncCaptureController(coordinator, queue, nowMs = { queue.now })
    }
    private class Queue : CaptureLifecycleQueue {
        private data class Task(val due: Long, val action: () -> Unit, var cancelled: Boolean = false)
        private val tasks = mutableListOf<Task>()
        var now = 0L
        var closed = false
        override fun schedule(delayMs: Long, action: () -> Unit): CaptureLifecycleQueue.Cancellation {
            check(!closed)
            val task = Task(now + delayMs, action)
            tasks += task
            return CaptureLifecycleQueue.Cancellation { task.cancelled = true }
        }
        override fun shutdown() { closed = true }
        fun runReady() {
            repeat(100) {
                tasks.removeAll { it.cancelled }
                val task = tasks.firstOrNull { it.due <= now } ?: return
                tasks.remove(task)
                task.action()
            }
            error("lifecycle did not become idle")
        }
    }
    private class Sdk : SdkConnection {
        lateinit var listener: SdkConnection.Listener
        var binds = 0
        var unbinds = 0
        override fun bind(listener: SdkConnection.Listener) { this.listener = listener; binds++ }
        override fun unbind() { unbinds++ }
    }
    private class Camera : CameraShareGateway {
        lateinit var callback: CameraShareGateway.Callback
        var starts = 0
        var stops = 0
        override fun start(options: VideoCaptureOptions, callback: CameraShareGateway.Callback) { this.callback = callback; starts++ }
        override fun stop() { stops++ }
    }
    private class Timer : TimeoutScheduler {
        override fun schedule(delayMs: Long, action: () -> Unit) = TimeoutScheduler.Cancellable {}
    }
}
