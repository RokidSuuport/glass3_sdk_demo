package com.rokid.glass.mediastream.streaming.internal

import com.rokid.glass.mediastream.capture.AudioCaptureMetrics
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
import com.rokid.glass.mediastream.capture.VideoFrameListener
import com.rokid.glass.mediastream.streaming.StreamingOptions
import com.rokid.glass.mediastream.streaming.StreamingState
import com.rokid.glass.mediastream.streaming.StreamingStatus
import com.rokid.glass.mediastream.streaming.StreamingStatusListener
import com.rokid.glass.mediastream.transport.signaling.IceCandidatePayload
import com.rokid.glass.mediastream.transport.signaling.SignalingMessage
import com.rokid.glass.mediastream.transport.signaling.SignalingType
import com.rokid.glass.mediastream.transport.webrtc.MediaPublisher
import com.rokid.glass.mediastream.transport.webrtc.PublishOptions
import com.rokid.glass.mediastream.transport.webrtc.TransportStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingCoordinatorTest {
    @Test
    fun signaling_opens_before_media_and_offer_waits_for_capture_readiness() {
        val fixture = Fixture()

        fixture.coordinator.start(fixture.options, fixture.listener)

        assertEquals(listOf("signaling.create", "signaling.connect"), fixture.events)
        assertEquals(0, fixture.capture.startCount)

        fixture.signaling().open()
        assertEquals(StreamingState.WAITING_RECEIVER, fixture.status().state)
        assertEquals(listOf(30_000L), fixture.scheduler.activeDelays())
        assertEquals(0, fixture.capture.startCount)

        fixture.signaling().message(SignalingMessage(SignalingType.PEER_READY))
        assertEquals(1, fixture.publisherFactory.created.size)
        assertEquals(1, fixture.publisher().prepareCount)
        assertEquals(1, fixture.capture.startCount)
        assertEquals(0, fixture.publisher().offerCount)
        assertTrue(fixture.capture.videoListener != null)
        assertTrue(fixture.capture.audioListener != null)

        fixture.capture.emit(CaptureStatus(CaptureState.PREPARING))
        assertEquals(0, fixture.publisher().offerCount)

        fixture.capture.emit(CaptureStatus(CaptureState.CAPTURING))
        assertEquals(StreamingState.NEGOTIATING, fixture.status().state)
        assertEquals(1, fixture.publisher().offerCount)

        fixture.publisher().connected()
        assertEquals(StreamingState.STREAMING, fixture.status().state)
        assertEquals(
            listOf(
                StreamingState.PREPARING,
                StreamingState.WAITING_RECEIVER,
                StreamingState.NEGOTIATING,
                StreamingState.STREAMING,
            ),
            fixture.statuses.map(StreamingStatus::state),
        )
    }

    @Test
    fun receiver_timeout_is_non_fatal_and_keeps_hardware_closed() {
        val fixture = Fixture()
        fixture.coordinator.start(fixture.options, fixture.listener)
        fixture.signaling().open()

        fixture.scheduler.runNext(30_000L)

        val status = fixture.status()
        assertEquals(StreamingState.WAITING_RECEIVER, status.state)
        assertEquals(MediaErrorCode.RECEIVER_NOT_READY, status.failure?.code)
        assertEquals(0, fixture.capture.startCount)
        assertEquals(0, fixture.publisherFactory.created.size)
    }

    @Test
    fun duplicate_peer_ready_is_ignored() {
        val fixture = Fixture()
        fixture.coordinator.start(fixture.options, fixture.listener)
        fixture.signaling().open()

        repeat(2) {
            fixture.signaling().message(SignalingMessage(SignalingType.PEER_READY))
        }
        fixture.capture.emit(CaptureStatus(CaptureState.CAPTURING))

        assertEquals(1, fixture.publisherFactory.created.size)
        assertEquals(1, fixture.publisher().prepareCount)
        assertEquals(1, fixture.capture.startCount)
        assertEquals(1, fixture.publisher().offerCount)
    }

    @Test
    fun malformed_answer_before_peer_ready_is_ignored_without_restarting_or_opening_media() {
        val fixture = Fixture(signalingCount = 2)
        fixture.coordinator.start(fixture.options, fixture.listener)
        fixture.signaling().open()

        fixture.signaling().message(SignalingMessage(SignalingType.ANSWER))

        assertEquals(StreamingState.WAITING_RECEIVER, fixture.status().state)
        assertEquals(1, fixture.signalingFactory.created.size)
        assertEquals(0, fixture.publisherFactory.created.size)
        assertEquals(0, fixture.capture.startCount)
        assertEquals(emptyList<Long>(), fixture.scheduler.activeDelays().filter { it != 30_000L })
    }

    @Test
    fun selected_media_controls_the_capture_listeners() {
        val combinations = listOf(
            Triple(true, true, true to true),
            Triple(true, false, true to false),
            Triple(false, true, false to true),
        )

        combinations.forEach { (videoEnabled, audioEnabled, expected) ->
            val fixture = Fixture()
            fixture.coordinator.start(
                fixture.options.copy(
                    videoEnabled = videoEnabled,
                    audioEnabled = audioEnabled,
                ),
                fixture.listener,
            )
            fixture.signaling().open()
            fixture.signaling().message(SignalingMessage(SignalingType.PEER_READY))

            assertEquals(expected.first, fixture.capture.videoListener != null)
            assertEquals(expected.second, fixture.capture.audioListener != null)
            assertEquals(
                PublishOptions(videoEnabled, audioEnabled),
                fixture.publisher().options,
            )
        }
    }

    @Test
    fun offer_ice_answer_and_remote_ice_are_forwarded() {
        val fixture = Fixture()
        fixture.startUntilCaptureReady()
        val localCandidate = IceCandidatePayload("video", 0, "candidate:local")
        val remoteCandidate = IceCandidatePayload("audio", 1, "candidate:remote")

        fixture.publisher().localOffer("local-sdp")
        fixture.publisher().localIce(localCandidate)
        fixture.signaling().message(
            SignalingMessage(
                SignalingType.ANSWER,
                sdp = "remote-sdp",
            ),
        )
        fixture.signaling().message(
            SignalingMessage(
                SignalingType.ICE_CANDIDATE,
                candidate = remoteCandidate,
            ),
        )

        assertTrue(
            fixture.signaling().sent.contains(
                SignalingMessage(SignalingType.OFFER, sdp = "local-sdp"),
            ),
        )
        assertTrue(
            fixture.signaling().sent.contains(
                SignalingMessage(SignalingType.ICE_CANDIDATE, candidate = localCandidate),
            ),
        )
        assertEquals(listOf("remote-sdp"), fixture.publisher().answers)
        assertEquals(listOf(remoteCandidate), fixture.publisher().remoteCandidates)
    }

    @Test
    fun transport_stats_merge_actual_capture_dimensions_and_fps() {
        val fixture = Fixture()
        fixture.startUntilCaptureReady()
        fixture.capture.status = CaptureStatus(
            state = CaptureState.CAPTURING,
            videoMetrics = VideoCaptureMetrics(width = 1280, height = 720, fps = 14.5),
            audioMetrics = AudioCaptureMetrics(sampleRateHz = 16_000),
        )

        fixture.publisher().stats(
            TransportStats(
                videoBitrateBps = 1_200_000L,
                audioBitrateBps = 24_000L,
                packetsLost = 3L,
                roundTripTimeMs = 37L,
                pcmUnderrunBytes = 320L,
                pcmDroppedBytes = 640L,
            ),
        )

        assertEquals(1280, fixture.status().stats.videoWidth)
        assertEquals(720, fixture.status().stats.videoHeight)
        assertEquals(14.5, fixture.status().stats.videoFps, 0.0)
        assertEquals(1_200_000L, fixture.status().stats.videoBitrateBps)
        assertEquals(24_000L, fixture.status().stats.audioBitrateBps)
        assertEquals(3L, fixture.status().stats.packetsLost)
        assertEquals(37L, fixture.status().stats.roundTripTimeMs)
        assertEquals(320L, fixture.status().stats.pcmUnderrunBytes)
        assertEquals(640L, fixture.status().stats.pcmDroppedBytes)
    }

    @Test
    fun normal_receiver_leave_closes_only_peer_media_and_waits_for_another_receiver() {
        val fixture = Fixture()
        fixture.startUntilCaptureReady()
        fixture.publisher().connected()
        fixture.events.clear()

        fixture.signaling().message(SignalingMessage(SignalingType.LEAVE))

        assertEquals(listOf("publisher.close", "capture.stop"), fixture.events)
        assertEquals(StreamingState.WAITING_RECEIVER, fixture.status().state)
        assertEquals(0, fixture.signaling().closeCount)
        assertEquals(listOf(30_000L), fixture.scheduler.activeDelays())

        fixture.signaling().message(SignalingMessage(SignalingType.PEER_READY))
        assertEquals(2, fixture.publisherFactory.created.size)
        assertEquals(2, fixture.capture.startCount)
    }

    @Test
    fun network_failures_cleanup_then_retry_after_exact_backoff_and_finish_disconnected() {
        val fixture = Fixture(signalingCount = 4)
        fixture.startUntilCaptureReady()
        fixture.publisher().connected()
        fixture.events.clear()

        fixture.signaling().failure(IllegalStateException("socket one"))

        assertEquals(
            listOf("publisher.close", "signaling.close", "signaling.dispose", "capture.stop"),
            fixture.events.take(4),
        )
        assertEquals(listOf(1_000L), fixture.scheduler.activeDelays())
        assertEquals(MediaErrorCode.NETWORK_DISCONNECTED, fixture.status().failure?.code)

        fixture.scheduler.runNext(1_000L)
        assertEquals(1, fixture.status().retryAttempt)
        fixture.signaling(1).failure(IllegalStateException("socket two"))
        assertEquals(listOf(2_000L), fixture.scheduler.activeDelays())

        fixture.scheduler.runNext(2_000L)
        assertEquals(2, fixture.status().retryAttempt)
        fixture.signaling(2).failure(IllegalStateException("socket three"))
        assertEquals(listOf(4_000L), fixture.scheduler.activeDelays())

        fixture.scheduler.runNext(4_000L)
        assertEquals(3, fixture.status().retryAttempt)
        fixture.signaling(3).failure(IllegalStateException("socket four"))

        assertTrue(fixture.scheduler.activeDelays().isEmpty())
        assertEquals(StreamingState.ERROR, fixture.status().state)
        assertEquals(MediaErrorCode.NETWORK_DISCONNECTED, fixture.status().failure?.code)
        assertTrue(fixture.status().failure?.technicalMessage?.contains("socket four") == true)
    }

    @Test
    fun initial_socket_failure_is_server_unreachable_before_retry() {
        val fixture = Fixture(signalingCount = 4)
        val cause = IllegalArgumentException("connection refused")
        fixture.coordinator.start(fixture.options, fixture.listener)

        fixture.signaling().failure(cause)

        val failure = fixture.status().failure
        assertEquals(MediaErrorCode.SERVER_UNREACHABLE, failure?.code)
        assertSame(cause, failure?.cause)
        assertTrue(failure?.technicalMessage?.contains("connection refused") == true)
        assertEquals(listOf(1_000L), fixture.scheduler.activeDelays())
        assertEquals(1, fixture.logger.failures.size)
    }

    @Test
    fun permission_and_capture_failures_do_not_retry() {
        val permissionFailure = MediaFailureCatalog.forCode(MediaErrorCode.PERMISSION_REQUIRED)
        val denied = Fixture(permissionFailure = permissionFailure)

        denied.coordinator.start(denied.options, denied.listener)

        assertEquals(StreamingState.ERROR, denied.status().state)
        assertEquals(MediaErrorCode.PERMISSION_REQUIRED, denied.status().failure?.code)
        assertEquals(0, denied.signalingFactory.created.size)
        assertTrue(denied.scheduler.activeDelays().isEmpty())

        val cameraFailure = MediaFailure(
            MediaErrorCode.CAMERA_IN_USE,
            "相机被占用",
            "关闭其他相机功能",
            "MAX_CAMERAS_IN_USE",
        )
        val camera = Fixture()
        camera.coordinator.start(camera.options, camera.listener)
        camera.signaling().open()
        camera.signaling().message(SignalingMessage(SignalingType.PEER_READY))
        camera.capture.emit(CaptureStatus(CaptureState.ERROR, failure = cameraFailure))

        assertEquals(StreamingState.ERROR, camera.status().state)
        assertSame(cameraFailure, camera.status().failure)
        assertTrue(camera.scheduler.activeDelays().isEmpty())
    }

    @Test
    fun audio_data_timeout_releases_the_stale_callback_and_retries_the_whole_session() {
        val fixture = Fixture(signalingCount = 2)
        val timeout = MediaFailureCatalog.forCode(MediaErrorCode.AUDIO_DATA_TIMEOUT)
        fixture.coordinator.start(fixture.options, fixture.listener)
        fixture.signaling().open()
        fixture.signaling().message(SignalingMessage(SignalingType.PEER_READY))

        fixture.capture.emit(CaptureStatus(CaptureState.ERROR, failure = timeout))

        assertEquals(StreamingState.ERROR, fixture.status().state)
        assertSame(timeout, fixture.status().failure)
        assertEquals(1, fixture.capture.stopCount)
        assertEquals(listOf(1_000L), fixture.scheduler.activeDelays())

        fixture.scheduler.runNext(1_000L)
        fixture.signaling(1).open()
        fixture.signaling(1).message(SignalingMessage(SignalingType.PEER_READY))

        assertEquals(1, fixture.status().retryAttempt)
        assertEquals(2, fixture.capture.startCount)
    }

    @Test
    fun publisher_failure_before_streaming_maps_to_negotiation_failure() {
        val fixture = Fixture()
        fixture.startUntilCaptureReady()
        val cause = IllegalStateException("set local description failed")

        fixture.publisher().failure("offer failed", cause)

        assertEquals(MediaErrorCode.WEBRTC_NEGOTIATION_FAILED, fixture.status().failure?.code)
        assertSame(cause, fixture.status().failure?.cause)
        assertTrue(fixture.status().failure?.technicalMessage?.contains("offer failed") == true)
        assertEquals(listOf(1_000L), fixture.scheduler.activeDelays())
    }

    @Test
    fun negotiation_timeout_cleans_up_the_stalled_peer_and_retries() {
        val fixture = Fixture(signalingCount = 2)
        fixture.startUntilCaptureReady()

        assertEquals(listOf(15_000L), fixture.scheduler.activeDelays())
        fixture.scheduler.runNext(15_000L)

        assertEquals(StreamingState.ERROR, fixture.status().state)
        assertEquals(MediaErrorCode.WEBRTC_NEGOTIATION_FAILED, fixture.status().failure?.code)
        assertEquals(1, fixture.publisher().closeCount)
        assertEquals(1, fixture.signaling().closeCount)
        assertEquals(1, fixture.capture.stopCount)
        assertEquals(listOf(1_000L), fixture.scheduler.activeDelays())
    }

    @Test
    fun successful_connection_cancels_the_negotiation_timeout() {
        val fixture = Fixture()
        fixture.startUntilCaptureReady()

        fixture.publisher().connected()

        assertEquals(StreamingState.STREAMING, fixture.status().state)
        assertTrue(fixture.scheduler.activeDelays().isEmpty())
    }

    @Test
    fun stop_cancels_wait_and_retry_tasks_and_ignores_obsolete_callbacks() {
        val fixture = Fixture(signalingCount = 4)
        fixture.coordinator.start(fixture.options, fixture.listener)
        val first = fixture.signaling()
        first.open()
        val waitTask = fixture.scheduler.singleActive()

        fixture.coordinator.stop()
        first.message(SignalingMessage(SignalingType.PEER_READY))
        first.failure(IllegalStateException("late"))
        waitTask.runEvenIfCancelled()

        assertTrue(waitTask.cancelled)
        assertEquals(StreamingState.IDLE, fixture.status().state)
        assertEquals(0, fixture.publisherFactory.created.size)
        assertTrue(fixture.scheduler.activeDelays().isEmpty())

        fixture.coordinator.start(fixture.options, fixture.listener)
        fixture.signaling(1).failure(IllegalStateException("retry me"))
        val retryTask = fixture.scheduler.singleActive()
        fixture.coordinator.stop()
        retryTask.runEvenIfCancelled()

        assertTrue(retryTask.cancelled)
        assertEquals(StreamingState.IDLE, fixture.status().state)
        assertEquals(2, fixture.signalingFactory.created.size)
    }

    @Test
    fun reentrant_stop_from_preparing_callback_prevents_signaling_start() {
        val fixture = Fixture()
        val listener = StreamingStatusListener { status ->
            fixture.statuses += status
            if (status.state == StreamingState.PREPARING) fixture.coordinator.stop()
        }

        fixture.coordinator.start(fixture.options, listener)

        assertEquals(
            listOf(StreamingState.PREPARING, StreamingState.STOPPING, StreamingState.IDLE),
            fixture.statuses.map(StreamingStatus::state),
        )
        assertEquals(0, fixture.signalingFactory.created.size)
    }

    @Test
    fun queued_executor_drops_obsolete_callbacks_but_keeps_stop_states() {
        val executor = QueuedCallbackExecutor()
        val fixture = Fixture(callbackExecutor = executor)

        fixture.coordinator.start(fixture.options, fixture.listener)
        fixture.coordinator.stop()
        executor.runAll()

        assertEquals(
            listOf(StreamingState.STOPPING, StreamingState.IDLE),
            fixture.statuses.map(StreamingStatus::state),
        )
    }

    @Test
    fun duplicate_start_stop_and_release_are_idempotent_and_release_is_terminal() {
        val fixture = Fixture()
        val replacementStatuses = mutableListOf<StreamingStatus>()
        fixture.coordinator.start(fixture.options, fixture.listener)
        fixture.coordinator.start(
            fixture.options.copy(serverUrl = "ws://other.test/ws"),
            StreamingStatusListener(replacementStatuses::add),
        )

        assertEquals(1, fixture.signalingFactory.created.size)
        assertTrue(replacementStatuses.isEmpty())

        fixture.coordinator.stop()
        fixture.coordinator.stop()
        fixture.coordinator.release()
        fixture.coordinator.release()

        assertEquals(1, fixture.capture.releaseCount)
        assertEquals(StreamingState.RELEASED, fixture.status().state)
        assertThrowsIllegalState {
            fixture.coordinator.start(fixture.options, fixture.listener)
        }
    }

    @Test
    fun release_requested_reentrantly_from_stopping_callback_still_becomes_terminal() {
        val fixture = Fixture()
        val listener = StreamingStatusListener { status ->
            fixture.statuses += status
            if (status.state == StreamingState.STOPPING) fixture.coordinator.release()
        }
        fixture.coordinator.start(fixture.options, listener)

        fixture.coordinator.stop()

        assertEquals(StreamingState.RELEASED, fixture.status().state)
        assertEquals(1, fixture.capture.releaseCount)
        assertEquals(
            listOf(
                StreamingState.PREPARING,
                StreamingState.STOPPING,
                StreamingState.RELEASED,
            ),
            fixture.statuses.map(StreamingStatus::state),
        )
    }

    @Test
    fun capture_release_failure_does_not_leave_the_streamer_half_released() {
        val fixture = Fixture(captureReleaseFailure = IllegalStateException("release failed"))
        fixture.coordinator.start(fixture.options, fixture.listener)

        fixture.coordinator.release()

        assertEquals(StreamingState.RELEASED, fixture.status().state)
        assertEquals(1, fixture.capture.releaseCount)
        assertThrowsIllegalState {
            fixture.coordinator.start(fixture.options, fixture.listener)
        }
    }

    private class Fixture(
        signalingCount: Int = 1,
        permissionFailure: MediaFailure? = null,
        callbackExecutor: CallbackExecutor = CallbackExecutor { it() },
        captureReleaseFailure: Throwable? = null,
    ) {
        val events = mutableListOf<String>()
        val capture = FakeCaptureSession(events, captureReleaseFailure)
        val signalingFactory = FakeSignalingFactory(events, signalingCount)
        val publisherFactory = FakePublisherFactory(events)
        val scheduler = FakeRetryScheduler()
        val logger = FakeStreamingLogger()
        val statuses = mutableListOf<StreamingStatus>()
        val listener = StreamingStatusListener(statuses::add)
        val options = StreamingOptions("ws://example.test/ws")
        val coordinator = StreamingCoordinator(
            capture = capture,
            signalingFactory = signalingFactory,
            publisherFactory = publisherFactory,
            scheduler = scheduler,
            callbackExecutor = callbackExecutor,
            permissionChecker = PermissionChecker { permissionFailure },
            logger = logger,
        )

        fun signaling(index: Int = 0): FakeSignalingSession = signalingFactory.created[index]

        fun publisher(index: Int = publisherFactory.created.lastIndex): FakePublisher =
            publisherFactory.created[index]

        fun status(): StreamingStatus = coordinator.currentStatus()

        fun startUntilCaptureReady() {
            coordinator.start(options, listener)
            signaling().open()
            signaling().message(SignalingMessage(SignalingType.PEER_READY))
            capture.emit(CaptureStatus(CaptureState.CAPTURING))
        }
    }

    private class FakeCaptureSession(
        private val events: MutableList<String>,
        private val releaseFailure: Throwable?,
    ) : CaptureSession {
        var startCount = 0
        var stopCount = 0
        var releaseCount = 0
        var videoListener: VideoFrameListener? = null
        var audioListener: AudioFrameListener? = null
        var statusListener: CaptureStatusListener? = null
        var status = CaptureStatus(CaptureState.IDLE)

        override fun start(
            options: CaptureOptions,
            videoListener: VideoFrameListener?,
            audioListener: AudioFrameListener?,
            statusListener: CaptureStatusListener?,
        ) {
            events += "capture.start"
            startCount += 1
            this.videoListener = videoListener
            this.audioListener = audioListener
            this.statusListener = statusListener
        }

        override fun stop() {
            events += "capture.stop"
            stopCount += 1
            status = CaptureStatus(CaptureState.IDLE)
        }

        override fun release() {
            events += "capture.release"
            releaseCount += 1
            releaseFailure?.let { throw it }
            status = CaptureStatus(CaptureState.RELEASED)
        }

        override fun currentStatus(): CaptureStatus = status

        fun emit(status: CaptureStatus) {
            this.status = status
            statusListener?.onStatusChanged(status)
        }
    }

    private class FakeSignalingFactory(
        private val events: MutableList<String>,
        count: Int,
    ) : SignalingSessionFactory {
        private val available = ArrayDeque(
            List(count) { FakeSignalingSession(events) },
        )
        val created = mutableListOf<FakeSignalingSession>()

        override fun create(): SignalingSession {
            events += "signaling.create"
            return available.removeFirst().also(created::add)
        }
    }

    private class FakeSignalingSession(
        private val events: MutableList<String>,
    ) : SignalingSession {
        lateinit var listener: SignalingSession.Listener
        val sent = mutableListOf<SignalingMessage>()
        var closeCount = 0
        var disposeCount = 0

        override fun connect(url: String, roomId: String, listener: SignalingSession.Listener) {
            events += "signaling.connect"
            this.listener = listener
        }

        override fun send(message: SignalingMessage): Boolean {
            sent += message
            return true
        }

        override fun close() {
            events += "signaling.close"
            closeCount += 1
        }

        override fun dispose() {
            events += "signaling.dispose"
            disposeCount += 1
        }

        fun open() = listener.onOpen()

        fun message(message: SignalingMessage) = listener.onMessage(message)

        fun failure(error: Throwable) = listener.onFailure(error)
    }

    private class FakePublisherFactory(
        private val events: MutableList<String>,
    ) : PublisherSessionFactory {
        val created = mutableListOf<FakePublisher>()

        override fun create(): MediaPublisher {
            events += "publisher.create"
            return FakePublisher(events).also(created::add)
        }
    }

    private class FakePublisher(
        private val events: MutableList<String>,
    ) : MediaPublisher {
        lateinit var listener: MediaPublisher.Listener
        var options: PublishOptions? = null
        var prepareCount = 0
        var offerCount = 0
        var closeCount = 0
        val answers = mutableListOf<String>()
        val remoteCandidates = mutableListOf<IceCandidatePayload>()

        override fun prepare(options: PublishOptions, listener: MediaPublisher.Listener) {
            events += "publisher.prepare"
            prepareCount += 1
            this.options = options
            this.listener = listener
        }

        override fun createOffer() {
            events += "publisher.offer"
            offerCount += 1
        }

        override fun setRemoteAnswer(sdp: String) {
            answers += sdp
        }

        override fun addRemoteIceCandidate(candidate: IceCandidatePayload) {
            remoteCandidates += candidate
        }

        override fun close() {
            events += "publisher.close"
            closeCount += 1
        }

        override fun onVideoFrame(frame: Nv21Frame) = Unit

        override fun onAudioFrame(frame: PcmFrame) = Unit

        fun localOffer(sdp: String) = listener.onLocalOffer(sdp)

        fun localIce(candidate: IceCandidatePayload) = listener.onLocalIceCandidate(candidate)

        fun connected() = listener.onConnected()

        fun failure(message: String, cause: Throwable?) = listener.onFailure(message, cause)

        fun stats(stats: TransportStats) = listener.onStats(stats)
    }

    private class FakeRetryScheduler : RetryScheduler {
        val tasks = mutableListOf<FakeScheduledTask>()

        override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
            return FakeScheduledTask(delayMs, task).also(tasks::add)
        }

        fun activeDelays(): List<Long> = tasks.filterNot(FakeScheduledTask::cancelled).map { it.delayMs }

        fun singleActive(): FakeScheduledTask = tasks.single { !it.cancelled }

        fun runNext(delayMs: Long) {
            val task = tasks.first { !it.cancelled && it.delayMs == delayMs }
            task.run()
        }
    }

    private class FakeScheduledTask(
        val delayMs: Long,
        private val task: () -> Unit,
    ) : Cancellable {
        var cancelled = false

        override fun cancel() {
            cancelled = true
        }

        fun run() {
            if (cancelled) return
            cancelled = true
            task()
        }

        fun runEvenIfCancelled() = task()
    }

    private class QueuedCallbackExecutor : CallbackExecutor {
        private val tasks = ArrayDeque<() -> Unit>()

        override fun execute(task: () -> Unit) {
            tasks += task
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst()()
        }
    }

    private class FakeStreamingLogger : StreamingLogger {
        val failures = mutableListOf<MediaFailure>()

        override fun error(failure: MediaFailure) {
            failures += failure
        }
    }

    private fun assertThrowsIllegalState(block: () -> Unit) {
        var thrown: Throwable? = null
        try {
            block()
        } catch (error: Throwable) {
            thrown = error
        }
        assertNotNull(thrown)
        assertTrue(thrown is IllegalStateException)
    }
}
