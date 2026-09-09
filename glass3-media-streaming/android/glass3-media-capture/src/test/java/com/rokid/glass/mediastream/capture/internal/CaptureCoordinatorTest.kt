package com.rokid.glass.mediastream.capture.internal

import com.rokid.glass.mediastream.capture.AudioCaptureMetrics
import com.rokid.glass.mediastream.capture.AudioCaptureOptions
import com.rokid.glass.mediastream.capture.AudioFrameListener
import com.rokid.glass.mediastream.capture.CaptureOptions
import com.rokid.glass.mediastream.capture.CaptureState
import com.rokid.glass.mediastream.capture.CaptureStatus
import com.rokid.glass.mediastream.capture.CaptureStatusListener
import com.rokid.glass.mediastream.capture.MediaErrorCode
import com.rokid.glass.mediastream.capture.MediaFailure
import com.rokid.glass.mediastream.capture.MediaFailureCatalog
import com.rokid.glass.mediastream.capture.Nv21Frame
import com.rokid.glass.mediastream.capture.PcmFrame
import com.rokid.glass.mediastream.capture.VideoCaptureMetrics
import com.rokid.glass.mediastream.capture.VideoCaptureOptions
import com.rokid.glass.mediastream.capture.VideoFrameListener
import com.rokid.glass.mediastream.capture.internal.audio.AudioSource
import com.rokid.glass.mediastream.capture.internal.sdk.SdkConnection
import com.rokid.glass.mediastream.capture.internal.video.VideoSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CaptureCoordinatorTest {
    private lateinit var calls: MutableList<String>
    private lateinit var sdkConnection: FakeSdkConnection
    private lateinit var videoSource: FakeVideoSource
    private lateinit var audioSource: FakeAudioSource
    private lateinit var coordinator: CaptureCoordinator
    private lateinit var statuses: MutableList<CaptureStatus>
    private lateinit var videoFrames: MutableList<Nv21Frame>
    private lateinit var audioFrames: MutableList<PcmFrame>

    private val videoListener = VideoFrameListener { frame -> videoFrames += frame }
    private val audioListener = AudioFrameListener { frame -> audioFrames += frame }
    private val statusListener = CaptureStatusListener { status -> statuses += status }

    @Before
    fun setUp() {
        calls = mutableListOf()
        sdkConnection = FakeSdkConnection(calls)
        videoSource = FakeVideoSource(calls)
        audioSource = FakeAudioSource(calls)
        coordinator = CaptureCoordinator(
            sdkConnection = sdkConnection,
            videoSourceFactory = VideoSourceFactory { videoSource },
            audioSourceFactory = AudioSourceFactory { audioSource },
        )
        statuses = mutableListOf()
        videoFrames = mutableListOf()
        audioFrames = mutableListOf()
    }

    @Test
    fun `video only start never opens audio and becomes capturing on its first frame`() {
        coordinator.start(CaptureOptions(), videoListener, null, statusListener)
        sdkConnection.emitReady()

        assertEquals(1, videoSource.startCount)
        assertEquals(0, audioSource.startCount)
        assertEquals(CaptureState.PREPARING, coordinator.currentStatus().state)

        val frame = videoFrame()
        videoSource.emit(frame)

        assertSame(frame, videoFrames.single())
        assertEquals(CaptureState.CAPTURING, coordinator.currentStatus().state)
        assertEquals(listOf(CaptureState.PREPARING, CaptureState.CAPTURING), states())
        frame.close()
    }

    @Test
    fun `audio only start never opens camera and becomes capturing on its first PCM frame`() {
        coordinator.start(CaptureOptions(), null, audioListener, statusListener)
        sdkConnection.emitReady()

        assertEquals(0, videoSource.startCount)
        assertEquals(1, audioSource.startCount)
        assertEquals(CaptureState.PREPARING, coordinator.currentStatus().state)

        val frame = pcmFrame()
        audioSource.emit(frame)

        assertSame(frame, audioFrames.single())
        assertEquals(CaptureState.CAPTURING, coordinator.currentStatus().state)
    }

    @Test
    fun `combined capture waits for the first actual frame from both requested sources`() {
        coordinator.start(CaptureOptions(), videoListener, audioListener, statusListener)
        sdkConnection.emitReady()

        val videoFrame = videoFrame()
        videoSource.emit(videoFrame)
        assertEquals(CaptureState.PREPARING, coordinator.currentStatus().state)

        val pcmFrame = pcmFrame()
        audioSource.emit(pcmFrame)

        assertEquals(CaptureState.CAPTURING, coordinator.currentStatus().state)
        assertEquals(listOf(CaptureState.PREPARING, CaptureState.CAPTURING), states())
        assertSame(videoFrame, videoFrames.single())
        assertSame(pcmFrame, audioFrames.single())
        videoFrame.close()
    }

    @Test
    fun `repeated start is idempotent while preparing and capturing`() {
        val replacementVideoFrames = mutableListOf<Nv21Frame>()
        val replacementAudioFrames = mutableListOf<PcmFrame>()
        coordinator.start(CaptureOptions(), videoListener, audioListener, statusListener)

        coordinator.start(
            CaptureOptions(audio = AudioCaptureOptions(channelCount = 2)),
            VideoFrameListener { frame -> replacementVideoFrames += frame },
            AudioFrameListener { frame -> replacementAudioFrames += frame },
            CaptureStatusListener { error("replacement status listener must stay unused") },
        )
        sdkConnection.emitReady()
        val videoFrame = videoFrame()
        videoSource.emit(videoFrame)
        audioSource.emit(pcmFrame())

        coordinator.start(CaptureOptions(), videoListener, audioListener, statusListener)

        assertEquals(1, sdkConnection.bindCount)
        assertEquals(1, videoSource.startCount)
        assertEquals(1, audioSource.startCount)
        assertSame(videoFrame, videoFrames.single())
        assertEquals(1, audioFrames.size)
        assertTrue(replacementVideoFrames.isEmpty())
        assertTrue(replacementAudioFrames.isEmpty())
        videoFrame.close()
    }

    @Test
    fun `stop and release are idempotent and release audio before video before SDK`() {
        coordinator.start(CaptureOptions(), videoListener, audioListener, statusListener)
        sdkConnection.emitReady()

        coordinator.stop()
        coordinator.stop()
        coordinator.release()
        coordinator.release()

        assertEquals(
            listOf("sdk.bind", "video.start", "audio.start", "audio.stop", "video.stop", "sdk.unbind"),
            calls,
        )
        assertEquals(CaptureState.RELEASED, coordinator.currentStatus().state)
        assertEquals(
            listOf(CaptureState.PREPARING, CaptureState.STOPPING, CaptureState.IDLE),
            states(),
        )
    }

    @Test
    fun `release reentered from stopping status upgrades the final state to released`() {
        var releaseRequested = false
        val reentrantListener = CaptureStatusListener { status ->
            statuses += status
            if (status.state == CaptureState.STOPPING) {
                releaseRequested = true
                coordinator.release()
            }
        }
        coordinator.start(CaptureOptions(), videoListener, audioListener, reentrantListener)
        sdkConnection.emitReady()

        coordinator.stop()

        assertTrue(releaseRequested)
        assertEquals(CaptureState.RELEASED, coordinator.currentStatus().state)
        assertEquals(
            listOf(CaptureState.PREPARING, CaptureState.STOPPING, CaptureState.RELEASED),
            states(),
        )
        assertEquals(
            listOf("sdk.bind", "video.start", "audio.start", "audio.stop", "video.stop", "sdk.unbind"),
            calls,
        )
    }

    @Test
    fun `start reentered from stopping status begins a new generation after cleanup`() {
        var restartRequested = false
        lateinit var reentrantListener: CaptureStatusListener
        reentrantListener = CaptureStatusListener { status ->
            statuses += status
            if (status.state == CaptureState.STOPPING && !restartRequested) {
                restartRequested = true
                coordinator.start(CaptureOptions(), videoListener, null, reentrantListener)
            }
        }
        coordinator.start(CaptureOptions(), videoListener, null, reentrantListener)
        sdkConnection.emitReady()

        coordinator.stop()

        assertTrue(restartRequested)
        assertEquals(CaptureState.PREPARING, coordinator.currentStatus().state)
        assertEquals(2, sdkConnection.bindCount)
        assertEquals(1, videoSource.startCount)
        assertEquals(1, videoSource.stopCount)
        assertEquals(
            listOf(CaptureState.PREPARING, CaptureState.STOPPING, CaptureState.PREPARING),
            states(),
        )
    }

    @Test
    fun `release waits for an in flight preparing notification before returning`() {
        val preparingEntered = CountDownLatch(1)
        val allowPreparingToFinish = CountDownLatch(1)
        val preparingFinished = CountDownLatch(1)
        val releaseReturned = CountDownLatch(1)
        val listener = CaptureStatusListener { status ->
            if (status.state == CaptureState.PREPARING) {
                preparingEntered.countDown()
                allowPreparingToFinish.await(5, TimeUnit.SECONDS)
                preparingFinished.countDown()
            }
        }
        val startTask = FutureTask<Unit> {
            coordinator.start(CaptureOptions(), videoListener, null, listener)
        }
        Thread(startTask, "capture-start-preparing-race").start()
        assertTrue(preparingEntered.await(5, TimeUnit.SECONDS))

        val releaseTask = FutureTask<Unit> {
            coordinator.release()
            releaseReturned.countDown()
        }
        Thread(releaseTask, "capture-release-preparing-race").start()
        val releaseReturnedBeforeCallbackFinished = releaseReturned.await(250, TimeUnit.MILLISECONDS)
        allowPreparingToFinish.countDown()

        startTask.get(5, TimeUnit.SECONDS)
        releaseTask.get(5, TimeUnit.SECONDS)
        assertFalse(releaseReturnedBeforeCallbackFinished)
        assertEquals(0L, preparingFinished.count)
        assertEquals(CaptureState.RELEASED, coordinator.currentStatus().state)
    }

    @Test
    fun `release waits for an in flight capturing notification before returning`() {
        val capturingEntered = CountDownLatch(1)
        val allowCapturingToFinish = CountDownLatch(1)
        val capturingFinished = CountDownLatch(1)
        val releaseReturned = CountDownLatch(1)
        val listener = CaptureStatusListener { status ->
            if (status.state == CaptureState.CAPTURING) {
                capturingEntered.countDown()
                allowCapturingToFinish.await(5, TimeUnit.SECONDS)
                capturingFinished.countDown()
            }
        }
        coordinator.start(CaptureOptions(), videoListener, null, listener)
        sdkConnection.emitReady()
        val frame = videoFrame()
        val frameTask = FutureTask<Unit> { videoSource.emit(frame) }
        Thread(frameTask, "capture-frame-release-race").start()
        assertTrue(capturingEntered.await(5, TimeUnit.SECONDS))

        val releaseTask = FutureTask<Unit> {
            coordinator.release()
            releaseReturned.countDown()
        }
        Thread(releaseTask, "capture-release-capturing-race").start()
        val releaseReturnedBeforeCallbackFinished = releaseReturned.await(250, TimeUnit.MILLISECONDS)
        allowCapturingToFinish.countDown()

        frameTask.get(5, TimeUnit.SECONDS)
        releaseTask.get(5, TimeUnit.SECONDS)
        assertFalse(releaseReturnedBeforeCallbackFinished)
        assertEquals(0L, capturingFinished.count)
        assertEquals(CaptureState.RELEASED, coordinator.currentStatus().state)
        frame.close()
    }

    @Test
    fun `new start waits for an in flight old idle before delivering preparing`() {
        val deliveredStates = CopyOnWriteArrayList<CaptureState>()
        val idleEntered = CountDownLatch(1)
        val allowIdleToFinish = CountDownLatch(1)
        val restartReturned = CountDownLatch(1)
        val listener = CaptureStatusListener { status ->
            if (status.state == CaptureState.IDLE) {
                idleEntered.countDown()
                allowIdleToFinish.await(5, TimeUnit.SECONDS)
            }
            deliveredStates += status.state
        }
        coordinator.start(CaptureOptions(), videoListener, null, listener)
        sdkConnection.emitReady()
        val stopTask = FutureTask<Unit> { coordinator.stop() }
        Thread(stopTask, "capture-stop-idle-race").start()
        assertTrue(idleEntered.await(5, TimeUnit.SECONDS))

        val restartTask = FutureTask<Unit> {
            coordinator.start(CaptureOptions(), videoListener, null, listener)
            restartReturned.countDown()
        }
        Thread(restartTask, "capture-restart-idle-race").start()
        val restartReturnedBeforeOldIdleFinished = restartReturned.await(250, TimeUnit.MILLISECONDS)
        allowIdleToFinish.countDown()

        stopTask.get(5, TimeUnit.SECONDS)
        restartTask.get(5, TimeUnit.SECONDS)
        assertFalse(restartReturnedBeforeOldIdleFinished)
        assertEquals(
            listOf(
                CaptureState.PREPARING,
                CaptureState.STOPPING,
                CaptureState.IDLE,
                CaptureState.PREPARING,
            ),
            deliveredStates,
        )
        assertEquals(CaptureState.PREPARING, coordinator.currentStatus().state)
    }

    @Test
    fun `audio start failure rolls back the attempted audio then started video and SDK`() {
        val startError = IllegalStateException("microphone unavailable")
        audioSource.startError = startError
        coordinator.start(CaptureOptions(), videoListener, audioListener, statusListener)

        sdkConnection.emitReady()

        assertEquals(
            listOf("sdk.bind", "video.start", "audio.start", "audio.stop", "video.stop", "sdk.unbind"),
            calls,
        )
        val failure = coordinator.currentStatus().failure
        assertEquals(CaptureState.ERROR, coordinator.currentStatus().state)
        assertEquals(MediaErrorCode.AUDIO_START_FAILED, failure?.code)
        assertSame(startError, failure?.cause)

        audioSource.startError = null
        coordinator.start(CaptureOptions(), videoListener, audioListener, statusListener)
        sdkConnection.emitReady()
        assertEquals(2, sdkConnection.bindCount)
        assertEquals(2, videoSource.startCount)
        assertEquals(2, audioSource.startCount)
    }

    @Test
    fun `source failure is preserved after every started resource is rolled back`() {
        val rootCause = IllegalStateException("camera binder died")
        val failure = MediaFailureCatalog.forCode(MediaErrorCode.SDK_DISCONNECTED).copy(
            technicalMessage = "camera callback disconnected",
            cause = rootCause,
        )
        coordinator.start(CaptureOptions(), videoListener, audioListener, statusListener)
        sdkConnection.emitReady()

        videoSource.fail(failure)

        assertSame(failure, coordinator.currentStatus().failure)
        assertSame(failure, statuses.last().failure)
        assertEquals(CaptureState.ERROR, statuses.last().state)
        assertEquals(
            listOf("sdk.bind", "video.start", "audio.start", "audio.stop", "video.stop", "sdk.unbind"),
            calls,
        )
    }

    @Test
    fun `source failure is reported before slow platform cleanup finishes`() {
        val cleanupEntered = CountDownLatch(1)
        val allowCleanup = CountDownLatch(1)
        val failureReported = CountDownLatch(1)
        videoSource.stopAction = {
            cleanupEntered.countDown()
            allowCleanup.await(5, TimeUnit.SECONDS)
        }
        coordinator.start(
            CaptureOptions(),
            videoListener,
            audioListener,
            CaptureStatusListener { status ->
                if (status.state == CaptureState.ERROR) failureReported.countDown()
            },
        )
        sdkConnection.emitReady()

        val failingThread = Thread {
            videoSource.fail(MediaFailureCatalog.forCode(MediaErrorCode.AUDIO_DATA_TIMEOUT))
        }.apply { start() }

        assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS))
        assertTrue("failure must be visible while platform cleanup is still blocked", failureReported.await(250, TimeUnit.MILLISECONDS))
        allowCleanup.countDown()
        failingThread.join(5_000L)
        assertFalse(failingThread.isAlive)
    }

    @Test
    fun `cleanup failures are aggregated without hiding a media failure`() {
        val rootCause = IllegalStateException("original failure")
        val failure = MediaFailureCatalog.forCode(MediaErrorCode.CAMERA_IN_USE).copy(
            technicalMessage = "camera code=2",
            cause = rootCause,
        )
        val audioStopError = IllegalStateException("audio stop failed")
        val videoStopError = IllegalStateException("video stop failed")
        val unbindError = IllegalStateException("unbind failed")
        audioSource.stopError = audioStopError
        videoSource.stopError = videoStopError
        sdkConnection.unbindError = unbindError
        coordinator.start(CaptureOptions(), videoListener, audioListener, statusListener)
        sdkConnection.emitReady()

        videoSource.fail(failure)

        assertSame(failure, coordinator.currentStatus().failure)
        assertEquals(listOf(audioStopError, videoStopError, unbindError), rootCause.suppressed.toList())
        assertEquals(MediaErrorCode.CAMERA_IN_USE, coordinator.currentStatus().failure?.code)
        assertEquals("camera code=2", coordinator.currentStatus().failure?.technicalMessage)
    }

    @Test
    fun `explicit stop attempts every cleanup step and throws one aggregated error`() {
        val audioStopError = IllegalStateException("audio stop failed")
        val videoStopError = IllegalStateException("video stop failed")
        val unbindError = IllegalStateException("unbind failed")
        audioSource.stopError = audioStopError
        videoSource.stopError = videoStopError
        sdkConnection.unbindError = unbindError
        coordinator.start(CaptureOptions(), videoListener, audioListener, statusListener)
        sdkConnection.emitReady()

        val thrown = assertThrows(IllegalStateException::class.java, coordinator::stop)

        assertSame(audioStopError, thrown)
        assertEquals(listOf(videoStopError, unbindError), thrown.suppressed.toList())
        assertEquals(
            listOf("sdk.bind", "video.start", "audio.start", "audio.stop", "video.stop", "sdk.unbind"),
            calls,
        )
        assertEquals(CaptureState.IDLE, coordinator.currentStatus().state)
        coordinator.stop()
    }

    @Test
    fun `listener validation happens before SDK binding`() {
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.start(CaptureOptions(), null, null, statusListener)
        }

        assertEquals(0, sdkConnection.bindCount)
        assertEquals(CaptureState.IDLE, coordinator.currentStatus().state)
        assertTrue(statuses.isEmpty())
    }

    @Test
    fun `callbacks from a stopped SDK generation cannot start or fail a new capture`() {
        coordinator.start(CaptureOptions(), videoListener, null, statusListener)
        val obsoleteListener = sdkConnection.listeners.single()
        coordinator.stop()
        coordinator.start(CaptureOptions(), videoListener, null, statusListener)

        obsoleteListener.onReady()
        obsoleteListener.onFailure(MediaFailureCatalog.forCode(MediaErrorCode.SDK_DISCONNECTED))

        assertEquals(0, videoSource.startCount)
        assertEquals(CaptureState.PREPARING, coordinator.currentStatus().state)

        sdkConnection.emitReady()
        assertEquals(1, videoSource.startCount)
    }

    @Test
    fun `current status exposes live source metrics and keeps the final snapshot after stop`() {
        coordinator.start(CaptureOptions(), videoListener, audioListener, statusListener)
        sdkConnection.emitReady()
        videoSource.currentMetrics = VideoCaptureMetrics(
            width = 640,
            height = 480,
            frameCount = 3,
            bytesReceived = 1_382_400,
        )
        audioSource.currentMetrics = AudioCaptureMetrics(
            sampleRateHz = 16_000,
            channelCount = 1,
            bitsPerSample = 16,
            frameCount = 4,
            bytesReceived = 1_280,
        )

        assertEquals(videoSource.currentMetrics, coordinator.currentStatus().videoMetrics)
        assertEquals(audioSource.currentMetrics, coordinator.currentStatus().audioMetrics)

        coordinator.stop()

        assertEquals(videoSource.currentMetrics, coordinator.currentStatus().videoMetrics)
        assertEquals(audioSource.currentMetrics, coordinator.currentStatus().audioMetrics)
    }

    @Test
    fun `release cleans an active capture and permanently rejects later starts`() {
        coordinator.start(CaptureOptions(), videoListener, audioListener, statusListener)
        sdkConnection.emitReady()

        coordinator.release()

        assertEquals(CaptureState.RELEASED, coordinator.currentStatus().state)
        assertThrows(IllegalStateException::class.java) {
            coordinator.start(CaptureOptions(), videoListener, audioListener, statusListener)
        }
        assertEquals(1, sdkConnection.bindCount)
    }

    private fun states(): List<CaptureState> = statuses.map(CaptureStatus::state)

    private fun videoFrame(): Nv21Frame = Nv21Frame.create(
        data = byteArrayOf(1, 2, 3, 4, 5, 6),
        width = 2,
        height = 2,
        timestampNs = 10L,
        finalRelease = {},
    )

    private fun pcmFrame(): PcmFrame = PcmFrame(
        data = byteArrayOf(7, 8, 9, 10),
        sampleRateHz = 16_000,
        channelCount = 1,
        bitsPerSample = 16,
        timestampNs = 20L,
    )

    private class FakeSdkConnection(
        private val calls: MutableList<String>,
    ) : SdkConnection {
        val listeners = mutableListOf<SdkConnection.Listener>()
        var bindCount = 0
        var unbindCount = 0
        var unbindError: Throwable? = null

        override fun bind(listener: SdkConnection.Listener) {
            calls += "sdk.bind"
            bindCount += 1
            listeners += listener
        }

        override fun unbind() {
            calls += "sdk.unbind"
            unbindCount += 1
            unbindError?.let { throw it }
        }

        fun emitReady(index: Int = listeners.lastIndex) {
            listeners[index].onReady()
        }
    }

    private class FakeVideoSource(
        private val calls: MutableList<String>,
    ) : VideoSource {
        var startCount = 0
        var stopCount = 0
        var startError: Throwable? = null
        var stopError: Throwable? = null
        var stopAction: (() -> Unit)? = null
        var currentMetrics = VideoCaptureMetrics()
        private var listener: VideoFrameListener? = null
        private var events: VideoSource.Events? = null

        override fun start(
            options: VideoCaptureOptions,
            listener: VideoFrameListener,
            events: VideoSource.Events,
        ) {
            calls += "video.start"
            startCount += 1
            this.listener = listener
            this.events = events
            startError?.let { throw it }
        }

        override fun stop() {
            calls += "video.stop"
            stopCount += 1
            stopAction?.invoke()
            stopError?.let { throw it }
        }

        override fun metrics(): VideoCaptureMetrics = currentMetrics

        fun emit(frame: Nv21Frame) {
            listener?.onVideoFrame(frame)
        }

        fun fail(failure: MediaFailure) {
            events?.onFailure(failure)
        }
    }

    private class FakeAudioSource(
        private val calls: MutableList<String>,
    ) : AudioSource {
        var startCount = 0
        var stopCount = 0
        var startError: Throwable? = null
        var stopError: Throwable? = null
        var currentMetrics = AudioCaptureMetrics()
        private var listener: AudioFrameListener? = null
        private var events: AudioSource.Events? = null

        override fun start(
            options: AudioCaptureOptions,
            listener: AudioFrameListener,
            events: AudioSource.Events,
        ) {
            calls += "audio.start"
            startCount += 1
            this.listener = listener
            this.events = events
            startError?.let { throw it }
        }

        override fun stop() {
            calls += "audio.stop"
            stopCount += 1
            stopError?.let { throw it }
        }

        override fun metrics(): AudioCaptureMetrics = currentMetrics

        fun emit(frame: PcmFrame) {
            listener?.onAudioFrame(frame)
        }
    }
}
