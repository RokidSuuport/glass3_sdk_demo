package com.rokid.glass.mediastream.capture.internal.audio

import com.rokid.glass.mediastream.capture.AudioCaptureOptions
import com.rokid.glass.mediastream.capture.AudioFrameListener
import com.rokid.glass.mediastream.capture.MediaErrorCode
import com.rokid.glass.mediastream.capture.MediaFailure
import com.rokid.glass.mediastream.capture.PcmFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class GlassPcmAudioSourceTest {
    private lateinit var gateway: FakeAudioServiceGateway
    private lateinit var scheduler: ManualAudioTimeoutScheduler
    private lateinit var source: GlassPcmAudioSource
    private lateinit var frames: MutableList<PcmFrame>
    private lateinit var failures: MutableList<MediaFailure>
    private var startedCount = 0

    private val listener = AudioFrameListener { frame -> frames += frame }
    private val events = object : AudioSource.Events {
        override fun onStarted() {
            startedCount += 1
        }

        override fun onFailure(failure: MediaFailure) {
            failures += failure
        }
    }

    @Before
    fun setUp() {
        gateway = FakeAudioServiceGateway()
        scheduler = ManualAudioTimeoutScheduler()
        source = GlassPcmAudioSource(
            audioGateway = gateway,
            startupTimeoutMs = 100L,
            timeoutScheduler = scheduler,
        )
        frames = mutableListOf()
        failures = mutableListOf()
        startedCount = 0
    }

    @Test
    fun `copies only the valid prefix and attaches PCM metadata`() {
        source.start(AudioCaptureOptions(), listener, events)
        val sdkBuffer = byteArrayOf(1, 2, 3, 4, 99, 99)

        gateway.emit(sdkBuffer, bufferLen = 4, timestampNs = 900L)
        sdkBuffer.fill(42)

        val frame = frames.single()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), frame.data)
        assertEquals(16_000, frame.sampleRateHz)
        assertEquals(1, frame.channelCount)
        assertEquals(16, frame.bitsPerSample)
        assertEquals(900L, frame.timestampNs)
    }

    @Test
    fun `oversized lengths are clamped to a copied SDK buffer`() {
        source.start(AudioCaptureOptions(), listener, events)
        val sdkBuffer = byteArrayOf(5, 6, 7, 8)

        gateway.emit(sdkBuffer, bufferLen = 99, timestampNs = 901L)
        sdkBuffer.fill(0)

        assertArrayEquals(byteArrayOf(5, 6, 7, 8), frames.single().data)
        assertEquals(4L, source.metrics().bytesReceived)
    }

    @Test
    fun `nonpositive and empty PCM callbacks are ignored and do not satisfy the watchdog`() {
        source.start(AudioCaptureOptions(), listener, events)

        gateway.emit(byteArrayOf(1, 2), bufferLen = 0, timestampNs = 1L)
        gateway.emit(byteArrayOf(1, 2), bufferLen = -1, timestampNs = 2L)
        gateway.emit(byteArrayOf(), bufferLen = 8, timestampNs = 3L)
        scheduler.runNext()

        assertTrue(frames.isEmpty())
        assertEquals(MediaErrorCode.AUDIO_DATA_TIMEOUT, failures.single().code)
        assertEquals(1, gateway.stopCallbacks.size)
    }

    @Test
    fun `successful start reports started and publishes actual metrics`() {
        source.start(AudioCaptureOptions(), listener, events)
        gateway.emit(byteArrayOf(1, 2, 3, 4), bufferLen = 4, timestampNs = 10L)
        gateway.emit(byteArrayOf(5, 6), bufferLen = 2, timestampNs = 20L)

        val metrics = source.metrics()
        assertEquals(1, startedCount)
        assertEquals(16_000, metrics.sampleRateHz)
        assertEquals(1, metrics.channelCount)
        assertEquals(16, metrics.bitsPerSample)
        assertEquals(2L, metrics.frameCount)
        assertEquals(6L, metrics.bytesReceived)
    }

    @Test
    fun `audio source accepts only the Rokid PCM format`() {
        assertThrows(IllegalArgumentException::class.java) {
            source.start(AudioCaptureOptions(sampleRateHz = 48_000), listener, events)
        }
        assertThrows(IllegalArgumentException::class.java) {
            source.start(AudioCaptureOptions(channelCount = 2), listener, events)
        }

        assertTrue(gateway.callbacks.isEmpty())
    }

    @Test
    fun `a rejected gateway start reports audio start failed and permits restart`() {
        gateway.startResult = false

        source.start(AudioCaptureOptions(), listener, events)

        assertEquals(MediaErrorCode.AUDIO_START_FAILED, failures.single().code)
        assertEquals(0, startedCount)
        assertSame(gateway.callbacks.single(), gateway.stopCallbacks.single())

        gateway.startResult = true
        source.start(AudioCaptureOptions(), listener, events)
        assertEquals(1, startedCount)
    }

    @Test
    fun `a thrown gateway start reports audio start failed and preserves its cause`() {
        val startError = IllegalStateException("microphone service failed")
        gateway.startError = startError

        source.start(AudioCaptureOptions(), listener, events)

        assertEquals(MediaErrorCode.AUDIO_START_FAILED, failures.single().code)
        assertSame(startError, failures.single().cause)
        assertSame(gateway.callbacks.single(), gateway.stopCallbacks.single())
    }

    @Test
    fun `PCM emitted by a rejected synchronous start is never delivered`() {
        gateway.onStart = { callback ->
            callback.onAudioStream(byteArrayOf(1, 2), 2, 1L)
        }
        gateway.startResult = false

        source.start(AudioCaptureOptions(), listener, events)

        assertTrue(frames.isEmpty())
        assertEquals(MediaErrorCode.AUDIO_START_FAILED, failures.single().code)
    }

    @Test
    fun `no PCM before timeout reports audio data timeout and stops the exact callback`() {
        source.start(AudioCaptureOptions(), listener, events)
        val callback = gateway.callbacks.single()

        scheduler.runNext()

        assertEquals(MediaErrorCode.AUDIO_DATA_TIMEOUT, failures.single().code)
        assertSame(callback, gateway.stopCallbacks.single())
    }

    @Test
    fun `stream gap after the first valid PCM frame reports audio data timeout`() {
        source.start(AudioCaptureOptions(), listener, events)
        gateway.emit(byteArrayOf(1, 2), bufferLen = 2, timestampNs = 1L)

        scheduler.runNext()

        assertEquals(MediaErrorCode.AUDIO_DATA_TIMEOUT, failures.single().code)
        assertEquals(1, gateway.stopCallbacks.size)
    }

    @Test
    fun `each valid PCM frame renews the continuous data watchdog`() {
        source.start(AudioCaptureOptions(), listener, events)
        gateway.emit(byteArrayOf(1, 2), bufferLen = 2, timestampNs = 1L)
        val firstWatchdog = scheduler.lastScheduledIndex

        gateway.emit(byteArrayOf(3, 4), bufferLen = 2, timestampNs = 2L)
        scheduler.runEvenIfCancelled(firstWatchdog)

        assertTrue(failures.isEmpty())
        assertTrue(gateway.stopCallbacks.isEmpty())

        scheduler.runNext()
        assertEquals(MediaErrorCode.AUDIO_DATA_TIMEOUT, failures.single().code)
        assertEquals(1, gateway.stopCallbacks.size)
    }

    @Test
    fun `runtime SDK disconnection stops audio and maps to SDK disconnected`() {
        source.start(AudioCaptureOptions(), listener, events)
        val disconnect = IllegalStateException("media binder died")

        gateway.emitDisconnected(disconnect)

        assertEquals(MediaErrorCode.SDK_DISCONNECTED, failures.single().code)
        assertSame(disconnect, failures.single().cause)
        assertEquals(1, gateway.stopCallbacks.size)
    }

    @Test
    fun `stop is idempotent and obsolete callbacks remain ignored after restart`() {
        source.start(AudioCaptureOptions(), listener, events)
        val obsoleteCallbackIndex = gateway.callbacks.lastIndex
        source.stop()
        source.stop()
        source.start(AudioCaptureOptions(), listener, events)

        gateway.emit(byteArrayOf(1, 2), 2, 1L, obsoleteCallbackIndex)
        gateway.emitDisconnected(IllegalStateException("obsolete"), obsoleteCallbackIndex)
        gateway.emit(byteArrayOf(3, 4), 2, 2L)

        assertEquals(1, gateway.stopCallbacks.size)
        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(3, 4), frames.single().data)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `stop invalidates a racing data watchdog`() {
        source.start(AudioCaptureOptions(), listener, events)
        gateway.emit(byteArrayOf(1, 2), 2, 1L)
        val watchdog = scheduler.lastScheduledIndex

        source.stop()
        scheduler.runEvenIfCancelled(watchdog)

        assertTrue(failures.isEmpty())
        assertEquals(1, gateway.stopCallbacks.size)
    }

    @Test
    fun `second active start returns successfully without changing the active session`() {
        val replacementFrames = mutableListOf<PcmFrame>()
        var replacementStartedCount = 0
        val replacementEvents = object : AudioSource.Events {
            override fun onStarted() {
                replacementStartedCount += 1
            }

            override fun onFailure(failure: MediaFailure) = Unit
        }
        source.start(AudioCaptureOptions(), listener, events)

        source.start(
            AudioCaptureOptions(),
            AudioFrameListener { frame -> replacementFrames += frame },
            replacementEvents,
        )
        gateway.emit(byteArrayOf(1, 2), 2, 10L)

        assertEquals(1, gateway.callbacks.size)
        assertEquals(1, startedCount)
        assertEquals(0, replacementStartedCount)
        assertEquals(1, frames.size)
        assertTrue(replacementFrames.isEmpty())
    }

    @Test
    fun `start waits for a racing stop then creates a clean new generation`() {
        val blockingGateway = BlockingStopGateway()
        val concurrentSource = audioSource(blockingGateway)
        val replacementFrames = mutableListOf<PcmFrame>()
        concurrentSource.start(AudioCaptureOptions(), listener, events)
        val obsoleteCallbackIndex = blockingGateway.callbacks.lastIndex
        val stop = future("audio-blocking-stop", concurrentSource::stop)
        await(blockingGateway.stopEntered, "gateway stop was not entered")
        val restart = FutureTask<Boolean> {
            runCatching {
                concurrentSource.start(
                    AudioCaptureOptions(),
                    AudioFrameListener { frame -> replacementFrames += frame },
                    events,
                )
            }.isSuccess
        }.also { task -> Thread(task, "audio-start-during-stop").start() }

        val startReturnedDuringStop = completesWithin(restart, 100L)
        blockingGateway.allowStopReturn.countDown()
        stop.get(2, TimeUnit.SECONDS)
        val restartSucceeded = restart.get(2, TimeUnit.SECONDS)

        assertFalse("start returned before the in-flight SDK stop completed", startReturnedDuringStop)
        assertTrue("start threw instead of creating the next generation", restartSucceeded)
        assertEquals(listOf("start:0", "stop:0", "start:1"), blockingGateway.calls)

        blockingGateway.emit(byteArrayOf(1, 2), 2, 1L, obsoleteCallbackIndex)
        blockingGateway.emit(byteArrayOf(3, 4), 2, 2L)

        assertTrue(frames.isEmpty())
        assertArrayEquals(byteArrayOf(3, 4), replacementFrames.single().data)
        concurrentSource.stop()
    }

    @Test
    fun `stop before gateway activation prevents the stale start from leaving audio active`() {
        val blockingGateway = BlockingStartGateway(activateBeforeBlocking = false)
        val concurrentSource = audioSource(blockingGateway)
        val start = future("audio-start-before-activation") {
            concurrentSource.start(AudioCaptureOptions(), listener, events)
        }
        await(blockingGateway.startEntered, "gateway start was not entered")
        val stop = future("audio-stop-before-activation", concurrentSource::stop)

        val stopReturnedBeforeStart = completesWithin(stop, 100L)
        blockingGateway.allowStartReturn.countDown()
        start.get(2, TimeUnit.SECONDS)
        stop.get(2, TimeUnit.SECONDS)

        assertFalse("stop returned while the stale start could still activate audio", stopReturnedBeforeStart)
        assertFalse(blockingGateway.active)
    }

    @Test
    fun `stop waits for an in flight gateway start before returning`() {
        val blockingGateway = BlockingStartGateway(activateBeforeBlocking = true)
        val concurrentSource = audioSource(blockingGateway)
        val start = future("audio-start-in-flight") {
            concurrentSource.start(AudioCaptureOptions(), listener, events)
        }
        await(blockingGateway.startEntered, "gateway start was not entered")
        val stop = future("audio-stop-during-start", concurrentSource::stop)

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
        val blockingEvents = object : AudioSource.Events {
            override fun onStarted() = notification.deliver()
            override fun onFailure(failure: MediaFailure) = Unit
        }
        val start = future("audio-start-notification") {
            source.start(AudioCaptureOptions(), listener, blockingEvents)
        }
        await(notification.entered, "onStarted was not entered")
        val stop = future("audio-stop-during-started", source::stop)

        val stopReturnedDuringNotification = completesWithin(stop, 100L)
        notification.allowReturn.countDown()
        start.get(2, TimeUnit.SECONDS)
        stop.get(2, TimeUnit.SECONDS)

        assertFalse("stop returned while onStarted was still executing", stopReturnedDuringNotification)
    }

    @Test
    fun `stop waits for an in flight PCM notification`() {
        val notification = BlockingNotification()
        val blockingListener = AudioFrameListener { notification.deliver() }
        source.start(AudioCaptureOptions(), blockingListener, events)
        val callback = future("audio-frame-callback") {
            gateway.emit(byteArrayOf(1, 2), 2, 1L)
        }
        await(notification.entered, "onAudioFrame was not entered")
        val stop = future("audio-stop-during-frame", source::stop)

        val stopReturnedDuringNotification = completesWithin(stop, 100L)
        notification.allowReturn.countDown()
        callback.get(2, TimeUnit.SECONDS)
        stop.get(2, TimeUnit.SECONDS)

        assertFalse("stop returned while onAudioFrame was still executing", stopReturnedDuringNotification)
    }

    @Test
    fun `stop waits for an in flight failure notification`() {
        val notification = BlockingNotification()
        val blockingEvents = object : AudioSource.Events {
            override fun onStarted() = Unit
            override fun onFailure(failure: MediaFailure) = notification.deliver()
        }
        source.start(AudioCaptureOptions(), listener, blockingEvents)
        val timeout = future("audio-timeout-callback", scheduler::runNext)
        await(notification.entered, "onFailure was not entered")
        val stop = future("audio-stop-during-failure", source::stop)

        val stopReturnedDuringNotification = completesWithin(stop, 100L)
        notification.allowReturn.countDown()
        timeout.get(2, TimeUnit.SECONDS)
        stop.get(2, TimeUnit.SECONDS)

        assertFalse("stop returned while onFailure was still executing", stopReturnedDuringNotification)
    }

    @Test
    fun `synchronous gateway PCM callback may stop the source without deadlock`() {
        lateinit var concurrentSource: GlassPcmAudioSource
        val synchronousGateway = object : AudioServiceGateway {
            var stopCount = 0

            override fun start(callback: AudioServiceGateway.Callback): Boolean {
                callback.onAudioStream(byteArrayOf(1, 2), 2, 1L)
                return true
            }

            override fun stop(callback: AudioServiceGateway.Callback) {
                stopCount += 1
            }
        }
        concurrentSource = audioSource(synchronousGateway)
        val stoppingListener = AudioFrameListener { concurrentSource.stop() }

        val start = future("audio-synchronous-callback") {
            concurrentSource.start(AudioCaptureOptions(), stoppingListener, events)
        }

        assertNull(start.get(2, TimeUnit.SECONDS))
        assertEquals(1, synchronousGateway.stopCount)
    }

    private fun audioSource(gateway: AudioServiceGateway): GlassPcmAudioSource =
        GlassPcmAudioSource(
            audioGateway = gateway,
            startupTimeoutMs = 100L,
            timeoutScheduler = ManualAudioTimeoutScheduler(),
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

    private class FakeAudioServiceGateway : AudioServiceGateway {
        val callbacks = mutableListOf<AudioServiceGateway.Callback>()
        val stopCallbacks = mutableListOf<AudioServiceGateway.Callback>()
        var startResult = true
        var startError: Throwable? = null
        var onStart: (AudioServiceGateway.Callback) -> Unit = {}

        override fun start(callback: AudioServiceGateway.Callback): Boolean {
            callbacks += callback
            onStart(callback)
            startError?.let { throw it }
            return startResult
        }

        override fun stop(callback: AudioServiceGateway.Callback) {
            stopCallbacks += callback
        }

        fun emit(
            buffer: ByteArray,
            bufferLen: Int,
            timestampNs: Long,
            callbackIndex: Int = callbacks.lastIndex,
        ) {
            callbacks[callbackIndex].onAudioStream(buffer, bufferLen, timestampNs)
        }

        fun emitDisconnected(
            cause: Throwable?,
            callbackIndex: Int = callbacks.lastIndex,
        ) {
            callbacks[callbackIndex].onDisconnected(cause)
        }
    }

    private class BlockingStartGateway(
        private val activateBeforeBlocking: Boolean,
    ) : AudioServiceGateway {
        val startEntered = CountDownLatch(1)
        val allowStartReturn = CountDownLatch(1)
        @Volatile
        var active = false

        override fun start(callback: AudioServiceGateway.Callback): Boolean {
            if (activateBeforeBlocking) active = true
            startEntered.countDown()
            check(allowStartReturn.await(2, TimeUnit.SECONDS)) { "test did not release gateway.start" }
            if (!activateBeforeBlocking) active = true
            return true
        }

        override fun stop(callback: AudioServiceGateway.Callback) {
            active = false
        }
    }

    private class BlockingStopGateway : AudioServiceGateway {
        val callbacks = mutableListOf<AudioServiceGateway.Callback>()
        val calls = mutableListOf<String>()
        val stopEntered = CountDownLatch(1)
        val allowStopReturn = CountDownLatch(1)
        private var stopCount = 0

        override fun start(callback: AudioServiceGateway.Callback): Boolean {
            callbacks += callback
            calls += "start:${callbacks.lastIndex}"
            return true
        }

        override fun stop(callback: AudioServiceGateway.Callback) {
            val callbackIndex = callbacks.indexOfFirst { candidate -> candidate === callback }
            calls += "stop:$callbackIndex"
            stopCount += 1
            if (stopCount == 1) {
                stopEntered.countDown()
                check(allowStopReturn.await(2, TimeUnit.SECONDS)) { "test did not release gateway.stop" }
            }
        }

        fun emit(
            buffer: ByteArray,
            bufferLen: Int,
            timestampNs: Long,
            callbackIndex: Int = callbacks.lastIndex,
        ) {
            callbacks[callbackIndex].onAudioStream(buffer, bufferLen, timestampNs)
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

    private class ManualAudioTimeoutScheduler : AudioTimeoutScheduler {
        private val tasks = mutableListOf<Task>()

        val lastScheduledIndex: Int
            get() = tasks.lastIndex

        override fun schedule(delayMs: Long, action: () -> Unit): AudioTimeoutScheduler.Cancellable {
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
        ) : AudioTimeoutScheduler.Cancellable {
            var cancelled = false
            var executed = false

            override fun cancel() {
                cancelled = true
            }
        }
    }
}
