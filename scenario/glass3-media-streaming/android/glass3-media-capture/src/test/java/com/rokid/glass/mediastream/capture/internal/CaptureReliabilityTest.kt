package com.rokid.glass.mediastream.capture.internal

import com.rokid.glass.mediastream.capture.*
import com.rokid.glass.mediastream.capture.internal.sdk.SdkConnection
import com.rokid.glass.mediastream.capture.internal.video.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CaptureReliabilityTest {
    @Test
    fun `SDK readiness invalidates a timeout callback that was already dispatched`() {
        val sdk = Sdk()
        val timer = Timer()
        val camera = Camera()
        val source = GlassNv21VideoSource(camera, FrameSlotPool(2), 100L, Timer())
        val capture = CaptureCoordinator(sdk, VideoSourceFactory { source }, AudioSourceFactory { error("no audio") }, timer)
        capture.start(CaptureOptions(), VideoFrameListener {}, null, null)
        sdk.listener.onReady()
        camera.callback.onFrame(ByteArray(6), 2, 2, 1L)
        timer.action()
        assertEquals(CaptureState.CAPTURING, capture.currentStatus().state)
        capture.release()
    }
    @Test
    fun `media callback stop does not wait for external cleanup that is draining that callback`() {
        val camera = Camera()
        val sdk = Sdk()
        val source = GlassNv21VideoSource(camera, FrameSlotPool(2), 100L, Timer())
        val capture = CaptureCoordinator(sdk, VideoSourceFactory { source }, AudioSourceFactory { error("no audio") })
        val entered = CountDownLatch(1)
        val stopping = CountDownLatch(1)
        capture.start(CaptureOptions(), VideoFrameListener {
            entered.countDown()
            check(stopping.await(1, TimeUnit.SECONDS))
            capture.stop()
            capture.release()
        }, null, CaptureStatusListener { if (it.state == CaptureState.STOPPING) stopping.countDown() })
        sdk.listener.onReady()
        val frame = daemon { camera.callback.onFrame(ByteArray(6), 2, 2, 1L) }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        val stop = daemon { capture.stop() }
        frame.get(1, TimeUnit.SECONDS)
        stop.get(1, TimeUnit.SECONDS)
        assertEquals(CaptureState.RELEASED, capture.currentStatus().state)
    }

    @Test
    fun `silent SDK binding reaches an error within the requested startup deadline`() {
        val sdk = Sdk()
        val failed = CountDownLatch(1)
        // Sources are created eagerly but must never start before readiness.
        val source = GlassNv21VideoSource(Camera(), FrameSlotPool(2), 100L, Timer())
        val controller = CaptureCoordinator(sdk, VideoSourceFactory { source }, AudioSourceFactory { error("no audio") })
        controller.start(CaptureOptions(startupTimeoutMs = 20), VideoFrameListener {}, null,
            CaptureStatusListener { if (it.state == CaptureState.ERROR) failed.countDown() })
        try {
            assertTrue("SDK wait must also be bounded", failed.await(500, TimeUnit.MILLISECONDS))
            assertEquals(MediaErrorCode.SDK_NOT_READY, controller.currentStatus().failure?.code)
            sdk.listener.onReady()
            assertEquals(CaptureState.ERROR, controller.currentStatus().state)
        } finally { controller.release() }
    }

    @Test
    fun `camera timeout is visible before a blocked vendor stop returns`() {
        val allowStop = CountDownLatch(1)
        val camera = Camera { check(allowStop.await(2, TimeUnit.SECONDS)) }
        val timer = Timer()
        val source = GlassNv21VideoSource(camera, FrameSlotPool(2), 100L, timer)
        val failed = CountDownLatch(1)
        source.start(VideoCaptureOptions(), VideoFrameListener {}, object : VideoSource.Events {
            override fun onStarted() = Unit
            override fun onFailure(failure: MediaFailure) { failed.countDown() }
        })
        val timeout = daemon { timer.action() }
        try { assertTrue("failure must not await vendor teardown", failed.await(200, TimeUnit.MILLISECONDS)) }
        finally { allowStop.countDown(); timeout.get(2, TimeUnit.SECONDS) }
    }

    @Test
    fun `restart requested from first error notification starts after cleanup`() {
        val sdk = Sdk()
        val source = GlassNv21VideoSource(Camera(), FrameSlotPool(2), 100L, Timer())
        val capture = CaptureCoordinator(sdk, VideoSourceFactory { source }, AudioSourceFactory { error("no audio") })
        var retried = false
        capture.start(CaptureOptions(), VideoFrameListener {}, null, CaptureStatusListener {
            if (it.state == CaptureState.ERROR && !retried) {
                retried = true
                capture.start(CaptureOptions(), VideoFrameListener {}, null, null)
            }
        })
        sdk.listener.onFailure(MediaFailureCatalog.forCode(MediaErrorCode.SDK_NOT_READY))
        assertEquals(2, sdk.binds)
        assertEquals(CaptureState.PREPARING, capture.currentStatus().state)
        capture.release()
    }

    private class Sdk : SdkConnection {
        lateinit var listener: SdkConnection.Listener
        var binds = 0
        override fun bind(listener: SdkConnection.Listener) { this.listener = listener; binds++ }
        override fun unbind() = Unit
    }
    private class Camera(private val stopAction: () -> Unit = {}) : CameraShareGateway {
        lateinit var callback: CameraShareGateway.Callback
        override fun start(options: VideoCaptureOptions, callback: CameraShareGateway.Callback) { this.callback = callback }
        override fun stop() = stopAction()
    }
    private class Timer : TimeoutScheduler {
        lateinit var action: () -> Unit
        override fun schedule(delayMs: Long, action: () -> Unit): TimeoutScheduler.Cancellable {
            this.action = action
            return TimeoutScheduler.Cancellable {}
        }
    }
    private fun daemon(action: () -> Unit): FutureTask<Unit> = FutureTask(action).also {
        Thread(it).apply { isDaemon = true }.start()
    }
}
