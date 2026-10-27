package com.rokid.glass.mediastream.capture.internal.video

import com.rokid.glass.mediastream.capture.MediaErrorCode
import com.rokid.glass.mediastream.capture.MediaFailure
import com.rokid.glass.mediastream.capture.Nv21Frame
import com.rokid.glass.mediastream.capture.VideoCaptureOptions
import com.rokid.glass.mediastream.capture.VideoFrameListener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class GlassNv21VideoSourceTest {
    private lateinit var gateway: FakeCameraShareGateway
    private lateinit var pool: FrameSlotPool
    private lateinit var scheduler: ManualTimeoutScheduler
    private lateinit var source: GlassNv21VideoSource
    private lateinit var received: MutableList<Nv21Frame>
    private lateinit var failures: MutableList<MediaFailure>
    private var startedCount = 0

    private val listener = VideoFrameListener { frame -> received += frame }
    private val events = object : VideoSource.Events {
        override fun onStarted() {
            startedCount += 1
        }

        override fun onFailure(failure: MediaFailure) {
            failures += failure
        }
    }

    @Before
    fun setUp() {
        gateway = FakeCameraShareGateway()
        pool = FrameSlotPool(capacity = 2)
        scheduler = ManualTimeoutScheduler()
        source = GlassNv21VideoSource(
            cameraGateway = gateway,
            pool = pool,
            startupTimeoutMs = 100L,
            timeoutScheduler = scheduler,
        )
        received = mutableListOf()
        failures = mutableListOf()
        startedCount = 0
    }

    @Test
    fun `actual callback dimensions override requested dimensions`() {
        source.start(VideoCaptureOptions(1280, 720, 15), listener, events)
        gateway.emitFrame(ByteArray(640 * 480 * 3 / 2), 640, 480, 123L)

        assertEquals(640, received.single().width)
        assertEquals(480, received.single().height)
        assertEquals(123L, received.single().timestampNs)
        assertEquals(640, source.metrics().width)
        assertEquals(480, source.metrics().height)
    }

    @Test
    fun `invalid NV21 length is reported and not delivered`() {
        source.start(VideoCaptureOptions(), listener, events)
        gateway.emitFrame(ByteArray(10), 1280, 720, 123L)

        assertTrue(received.isEmpty())
        assertEquals(MediaErrorCode.VIDEO_FRAME_TIMEOUT, failures.single().code)
        assertEquals(
            "NV21 length=10, expected=1382400, size=1280 x 720",
            failures.single().technicalMessage,
        )
    }

    @Test
    fun `invalid dimensions are reported instead of overflowing frame size arithmetic`() {
        source.start(VideoCaptureOptions(), listener, events)
        gateway.emitFrame(ByteArray(6), Int.MAX_VALUE - 1, Int.MAX_VALUE - 1, 123L)

        assertTrue(received.isEmpty())
        assertEquals(MediaErrorCode.VIDEO_FRAME_TIMEOUT, failures.single().code)
        assertTrue(failures.single().technicalMessage.contains("expected=invalid"))
    }

    @Test
    fun `exhausted pool drops a new frame without allocating another slot`() {
        val heldFrames = holdAllLeases()
        source.start(VideoCaptureOptions(), listener, events)

        gateway.emitFrame(validNv21(), 1280, 720, 456L)

        assertEquals(1L, source.metrics().droppedFrames)
        assertEquals(2, pool.allocatedSlotCount)
        assertTrue(received.isEmpty())
        heldFrames.forEach(Nv21Frame::close)
    }

    @Test
    fun `source closes its handle after delivery while a retained handle keeps the slot alive`() {
        var retained: Nv21Frame? = null
        source.start(
            VideoCaptureOptions(),
            VideoFrameListener { frame -> retained = frame.retain() },
            events,
        )

        val sdkData = validNv21(extraBytes = 7)
        gateway.emitFrame(sdkData, 1280, 720, 789L)
        sdkData.fill(42)

        assertEquals(1, pool.inUseSlots)
        assertEquals(1280 * 720 * 3 / 2, requireNotNull(retained).data.size)
        assertFalse(requireNotNull(retained).data.all { it == 42.toByte() })
        assertArrayEquals(ByteArray(16) { it.toByte() }, requireNotNull(retained).data.copyOf(16))

        requireNotNull(retained).close()
        assertEquals(0, pool.inUseSlots)
    }

    @Test
    fun `start forwards requested options and rejects a second active start`() {
        val options = VideoCaptureOptions(640, 480, 24, true, true)
        source.start(options, listener, events)

        assertEquals(options, gateway.startOptions.single())
        assertThrows(IllegalStateException::class.java) {
            source.start(VideoCaptureOptions(), listener, events)
        }
        assertEquals(1, gateway.startOptions.size)
    }

    @Test
    fun `stop is idempotent and obsolete callbacks remain ignored after restart`() {
        source.start(VideoCaptureOptions(), listener, events)
        val obsoleteCallbackIndex = gateway.callbacks.lastIndex
        source.stop()
        source.stop()
        source.start(VideoCaptureOptions(), listener, events)

        gateway.emitOpened(obsoleteCallbackIndex)
        gateway.emitFrame(validNv21(), 1280, 720, 1L, obsoleteCallbackIndex)
        gateway.emitError(1, "MAX_CAMERAS_IN_USE", obsoleteCallbackIndex)
        gateway.emitFrame(validNv21(), 640, 480, 2L)

        assertEquals(1, gateway.stopCount)
        assertEquals(1, received.size)
        assertEquals(640, received.single().width)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `opened callback reports started once and first valid frame invalidates startup timeouts`() {
        source.start(VideoCaptureOptions(), listener, events)

        gateway.emitOpened()
        gateway.emitOpened()
        gateway.emitFrame(validNv21(), 1280, 720, 1L)
        scheduler.runEvenIfCancelled(0)
        scheduler.runEvenIfCancelled(1)

        assertEquals(1, startedCount)
        assertEquals(1, received.size)
        assertTrue(failures.isEmpty())
        assertEquals(0, gateway.stopCount)
    }

    @Test
    fun `a valid frame can establish startup before an opened callback arrives`() {
        source.start(VideoCaptureOptions(), listener, events)

        gateway.emitFrame(validNv21(), 1280, 720, 1L)
        gateway.emitOpened()
        scheduler.runEvenIfCancelled(0)

        assertEquals(1, startedCount)
        assertEquals(1, received.size)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `open timeout stops capture and maps to camera start timeout`() {
        source.start(VideoCaptureOptions(), listener, events)

        scheduler.runNext()

        assertEquals(MediaErrorCode.CAMERA_START_TIMEOUT, failures.single().code)
        assertEquals(1, gateway.stopCount)
    }

    @Test
    fun `first frame timeout after open stops capture and maps to video frame timeout`() {
        source.start(VideoCaptureOptions(), listener, events)
        gateway.emitOpened()

        scheduler.runNext()

        assertEquals(MediaErrorCode.VIDEO_FRAME_TIMEOUT, failures.single().code)
        assertEquals(1, gateway.stopCount)
    }

    @Test
    fun `stream gap after the first valid frame triggers video frame timeout`() {
        source.start(VideoCaptureOptions(), listener, events)
        gateway.emitFrame(validNv21(), 1280, 720, 1L)

        scheduler.runNext()

        assertEquals(MediaErrorCode.VIDEO_FRAME_TIMEOUT, failures.single().code)
        assertEquals(1, gateway.stopCount)
    }

    @Test
    fun `each valid frame renews the continuous frame watchdog`() {
        source.start(VideoCaptureOptions(), listener, events)
        gateway.emitFrame(validNv21(), 1280, 720, 1L)
        val firstWatchdog = scheduler.lastScheduledIndex

        gateway.emitFrame(validNv21(), 1280, 720, 2L)
        scheduler.runEvenIfCancelled(firstWatchdog)

        assertTrue(failures.isEmpty())
        assertEquals(0, gateway.stopCount)

        scheduler.runNext()
        assertEquals(MediaErrorCode.VIDEO_FRAME_TIMEOUT, failures.single().code)
        assertEquals(1, gateway.stopCount)
    }

    @Test
    fun `stop invalidates a racing continuous frame watchdog`() {
        source.start(VideoCaptureOptions(), listener, events)
        gateway.emitFrame(validNv21(), 1280, 720, 1L)
        val watchdog = scheduler.lastScheduledIndex

        source.stop()
        scheduler.runEvenIfCancelled(watchdog)

        assertTrue(failures.isEmpty())
        assertEquals(1, gateway.stopCount)
    }

    @Test
    fun `stop before gateway activation prevents the stale start from activating CameraShare`() {
        val blockingGateway = BlockingStartGateway(activateBeforeBlocking = false)
        val concurrentSource = videoSource(blockingGateway)
        val start = future("video-start-before-activation") {
            concurrentSource.start(VideoCaptureOptions(), listener, events)
        }
        await(blockingGateway.startEntered, "gateway start was not entered")
        val stop = future("video-stop-before-activation", concurrentSource::stop)

        val stopReturnedBeforeStart = completesWithin(stop, 100L)
        blockingGateway.allowStartReturn.countDown()
        start.get(2, TimeUnit.SECONDS)
        stop.get(2, TimeUnit.SECONDS)

        assertFalse("stop returned while the stale start could still activate CameraShare", stopReturnedBeforeStart)
        assertFalse(blockingGateway.active)
    }

    @Test
    fun `stop waits for an in flight gateway start before returning`() {
        val blockingGateway = BlockingStartGateway(activateBeforeBlocking = true)
        val concurrentSource = videoSource(blockingGateway)
        val start = future("video-start-in-flight") {
            concurrentSource.start(VideoCaptureOptions(), listener, events)
        }
        await(blockingGateway.startEntered, "gateway start was not entered")
        val stop = future("video-stop-during-start", concurrentSource::stop)

        val stopReturnedBeforeStart = completesWithin(stop, 100L)
        blockingGateway.allowStartReturn.countDown()
        start.get(2, TimeUnit.SECONDS)
        stop.get(2, TimeUnit.SECONDS)

        assertFalse("stop returned before gateway.start completed", stopReturnedBeforeStart)
        assertFalse(blockingGateway.active)
    }

    @Test
    fun `stop waits for an in flight started notification`() {
        val notification = BlockingNotification()
        val blockingEvents = object : VideoSource.Events {
            override fun onStarted() = notification.deliver()
            override fun onFailure(failure: MediaFailure) = Unit
        }
        source.start(VideoCaptureOptions(), listener, blockingEvents)
        val callback = future("video-open-callback", gateway::emitOpened)
        await(notification.entered, "onStarted was not entered")
        val stop = future("video-stop-during-started", source::stop)

        val stopReturnedDuringNotification = completesWithin(stop, 100L)
        notification.allowReturn.countDown()
        callback.get(2, TimeUnit.SECONDS)
        stop.get(2, TimeUnit.SECONDS)

        assertFalse("stop returned while onStarted was still executing", stopReturnedDuringNotification)
    }

    @Test
    fun `stop waits for an in flight video frame notification`() {
        val notification = BlockingNotification()
        val blockingListener = VideoFrameListener { notification.deliver() }
        source.start(VideoCaptureOptions(), blockingListener, events)
        gateway.emitOpened()
        val callback = future("video-frame-callback") {
            gateway.emitFrame(validNv21(), 1280, 720, 1L)
        }
        await(notification.entered, "onVideoFrame was not entered")
        val stop = future("video-stop-during-frame", source::stop)

        val stopReturnedDuringNotification = completesWithin(stop, 100L)
        notification.allowReturn.countDown()
        callback.get(2, TimeUnit.SECONDS)
        stop.get(2, TimeUnit.SECONDS)

        assertFalse("stop returned while onVideoFrame was still executing", stopReturnedDuringNotification)
        assertEquals(0, pool.inUseSlots)
    }

    @Test
    fun `stop waits for an in flight failure notification`() {
        val notification = BlockingNotification()
        val blockingEvents = object : VideoSource.Events {
            override fun onStarted() = Unit
            override fun onFailure(failure: MediaFailure) = notification.deliver()
        }
        source.start(VideoCaptureOptions(), listener, blockingEvents)
        val callback = future("video-error-callback") {
            gateway.emitError(-1, "MediaService disconnected")
        }
        await(notification.entered, "onFailure was not entered")
        val stop = future("video-stop-during-failure", source::stop)

        val stopReturnedDuringNotification = completesWithin(stop, 100L)
        notification.allowReturn.countDown()
        callback.get(2, TimeUnit.SECONDS)
        stop.get(2, TimeUnit.SECONDS)

        assertFalse("stop returned while onFailure was still executing", stopReturnedDuringNotification)
    }

    @Test
    fun `synchronous gateway callback may stop the source without deadlock`() {
        lateinit var concurrentSource: GlassNv21VideoSource
        val synchronousGateway = object : CameraShareGateway {
            var stopCount = 0

            override fun start(options: VideoCaptureOptions, callback: CameraShareGateway.Callback) {
                callback.onOpened()
            }

            override fun stop() {
                stopCount += 1
            }
        }
        val stoppingEvents = object : VideoSource.Events {
            override fun onStarted() = concurrentSource.stop()
            override fun onFailure(failure: MediaFailure) = Unit
        }
        concurrentSource = videoSource(synchronousGateway)

        val start = future("video-synchronous-callback") {
            concurrentSource.start(VideoCaptureOptions(), listener, stoppingEvents)
        }

        assertNull(start.get(2, TimeUnit.SECONDS))
        assertEquals(1, synchronousGateway.stopCount)
    }

    @Test
    fun `camera in use and SDK disconnect errors preserve raw SDK details`() {
        source.start(VideoCaptureOptions(), listener, events)
        gateway.emitError(1, "MAX_CAMERAS_IN_USE")

        assertEquals(MediaErrorCode.CAMERA_IN_USE, failures.single().code)
        assertEquals("CameraShare code=1, message=MAX_CAMERAS_IN_USE", failures.single().technicalMessage)
        assertEquals(1, gateway.stopCount)

        source.start(VideoCaptureOptions(), listener, events)
        gateway.emitError(-1, "MediaService not available")

        assertEquals(MediaErrorCode.SDK_DISCONNECTED, failures.last().code)
        assertEquals(
            "CameraShare code=-1, message=MediaService not available",
            failures.last().technicalMessage,
        )
        assertEquals(2, gateway.stopCount)
    }

    @Test
    fun `unexpected camera closure maps to SDK disconnected and permits restart`() {
        source.start(VideoCaptureOptions(), listener, events)
        gateway.emitClosed()

        assertEquals(MediaErrorCode.SDK_DISCONNECTED, failures.single().code)
        assertEquals(1, gateway.stopCount)

        source.start(VideoCaptureOptions(), listener, events)
        assertEquals(2, gateway.startOptions.size)
    }

    private fun holdAllLeases(): List<Nv21Frame> = listOf(
        requireNotNull(pool.acquireFrame(validNv21(), 1280 * 720 * 3 / 2, 1280, 720, 1L)),
        requireNotNull(pool.acquireFrame(validNv21(), 1280 * 720 * 3 / 2, 1280, 720, 2L)),
    )

    private fun validNv21(extraBytes: Int = 0): ByteArray =
        ByteArray(1280 * 720 * 3 / 2 + extraBytes) { (it and 0xff).toByte() }

    private fun videoSource(gateway: CameraShareGateway): GlassNv21VideoSource =
        GlassNv21VideoSource(
            cameraGateway = gateway,
            pool = FrameSlotPool(capacity = 2),
            startupTimeoutMs = 100L,
            timeoutScheduler = ManualTimeoutScheduler(),
        )

    private fun future(name: String, action: () -> Unit): FutureTask<Void?> =
        FutureTask<Void?> {
            action()
            null
        }.also { task -> Thread(task, name).start() }

    private fun completesWithin(task: FutureTask<*>, timeoutMs: Long): Boolean = try {
        task.get(timeoutMs, TimeUnit.MILLISECONDS)
        true
    } catch (_: TimeoutException) {
        false
    }

    private fun await(latch: CountDownLatch, failureMessage: String) {
        assertTrue(failureMessage, latch.await(2, TimeUnit.SECONDS))
    }

    private class FakeCameraShareGateway : CameraShareGateway {
        val startOptions = mutableListOf<VideoCaptureOptions>()
        val callbacks = mutableListOf<CameraShareGateway.Callback>()
        var stopCount = 0

        override fun start(options: VideoCaptureOptions, callback: CameraShareGateway.Callback) {
            startOptions += options
            callbacks += callback
        }

        override fun stop() {
            stopCount += 1
        }

        fun emitOpened(callbackIndex: Int = callbacks.lastIndex) {
            callbacks[callbackIndex].onOpened()
        }

        fun emitFrame(
            data: ByteArray,
            width: Int,
            height: Int,
            timestampNs: Long,
            callbackIndex: Int = callbacks.lastIndex,
        ) {
            callbacks[callbackIndex].onFrame(data, width, height, timestampNs)
        }

        fun emitClosed(callbackIndex: Int = callbacks.lastIndex) {
            callbacks[callbackIndex].onClosed()
        }

        fun emitError(code: Int, message: String, callbackIndex: Int = callbacks.lastIndex) {
            callbacks[callbackIndex].onError(code, message)
        }
    }

    private class BlockingStartGateway(
        private val activateBeforeBlocking: Boolean,
    ) : CameraShareGateway {
        val startEntered = CountDownLatch(1)
        val allowStartReturn = CountDownLatch(1)
        @Volatile
        var active = false

        override fun start(options: VideoCaptureOptions, callback: CameraShareGateway.Callback) {
            if (activateBeforeBlocking) active = true
            startEntered.countDown()
            check(allowStartReturn.await(2, TimeUnit.SECONDS)) { "test did not release gateway.start" }
            if (!activateBeforeBlocking) active = true
        }

        override fun stop() {
            active = false
        }
    }

    private class BlockingNotification {
        val entered = CountDownLatch(1)
        val allowReturn = CountDownLatch(1)

        fun deliver() {
            entered.countDown()
            check(allowReturn.await(2, TimeUnit.SECONDS)) { "test did not release notification" }
        }
    }

    private class ManualTimeoutScheduler : TimeoutScheduler {
        private val tasks = mutableListOf<Task>()

        val lastScheduledIndex: Int
            get() = tasks.lastIndex

        override fun schedule(delayMs: Long, action: () -> Unit): TimeoutScheduler.Cancellable {
            require(delayMs > 0L)
            val task = Task(action)
            tasks += task
            return task
        }

        fun runNext() {
            val task = tasks.firstOrNull { !it.cancelled && !it.executed } ?: return
            task.executed = true
            task.action()
        }

        fun runEvenIfCancelled(index: Int) {
            val task = tasks[index]
            if (task.executed) return
            task.executed = true
            task.action()
        }

        private class Task(
            val action: () -> Unit,
        ) : TimeoutScheduler.Cancellable {
            var cancelled = false
            var executed = false

            override fun cancel() {
                cancelled = true
            }
        }
    }
}
