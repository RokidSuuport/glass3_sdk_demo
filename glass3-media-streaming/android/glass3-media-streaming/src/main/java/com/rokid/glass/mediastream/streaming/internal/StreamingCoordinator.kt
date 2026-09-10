package com.rokid.glass.mediastream.streaming.internal

import com.rokid.glass.mediastream.capture.CaptureOptions
import com.rokid.glass.mediastream.capture.CaptureState
import com.rokid.glass.mediastream.capture.CaptureStatus
import com.rokid.glass.mediastream.capture.CaptureStatusListener
import com.rokid.glass.mediastream.capture.MediaErrorCode
import com.rokid.glass.mediastream.capture.MediaFailure
import com.rokid.glass.mediastream.capture.MediaFailureCatalog
import com.rokid.glass.mediastream.streaming.StreamingOptions
import com.rokid.glass.mediastream.streaming.StreamingState
import com.rokid.glass.mediastream.streaming.StreamingStats
import com.rokid.glass.mediastream.streaming.StreamingStatus
import com.rokid.glass.mediastream.streaming.StreamingStatusListener
import com.rokid.glass.mediastream.transport.signaling.SignalingMessage
import com.rokid.glass.mediastream.transport.signaling.SignalingType
import com.rokid.glass.mediastream.transport.webrtc.MediaPublisher
import com.rokid.glass.mediastream.transport.webrtc.PublishOptions
import com.rokid.glass.mediastream.transport.webrtc.TransportStats

internal class StreamingCoordinator(
    private val capture: CaptureSession,
    private val signalingFactory: SignalingSessionFactory,
    private val publisherFactory: PublisherSessionFactory,
    private val scheduler: RetryScheduler,
    private val callbackExecutor: CallbackExecutor,
    private val permissionChecker: PermissionChecker,
    private val logger: StreamingLogger,
) : StreamingController {
    private data class StatusNotification(
        val deliveryGeneration: Long,
        val listener: StreamingStatusListener,
        val status: StreamingStatus,
    )

    private data class AttemptCleanup(
        val publisher: MediaPublisher?,
        val signaling: SignalingSession?,
        val stopCapture: Boolean,
    )

    private data class FailurePlan(
        val cleanup: AttemptCleanup,
        val notification: StatusNotification?,
        val retryNumber: Int?,
        val retryDelayMs: Long?,
        val failure: MediaFailure,
    )

    private val lock = Any()
    private val stateMachine = StreamingStateMachine()

    private var deliveryGeneration = 0L
    private var attemptGeneration = 0L
    private var peerGeneration = 0L
    private var options: StreamingOptions? = null
    private var statusListener: StreamingStatusListener? = null
    private var status = StreamingStatus(StreamingState.IDLE)
    private var retryAttempt = 0
    private var signaling: SignalingSession? = null
    private var publisher: MediaPublisher? = null
    private var peerPending = false
    private var captureStarted = false
    private var offerCreated = false
    private var hasStreamed = false
    private var receiverTimeout: Cancellable? = null
    private var negotiationTimeout: Cancellable? = null
    private var retryHandle: Cancellable? = null
    private var releaseRequested = false

    override fun start(options: StreamingOptions, listener: StreamingStatusListener?) {
        val startPlan = synchronized(lock) {
            check(stateMachine.current() != StreamingState.RELEASED) {
                "GlassMediaStreamer has been released"
            }
            if (stateMachine.current() in ACTIVE_STATES) return

            val validated = StreamingOptionsValidator.validate(options)
            receiverTimeout?.cancel()
            receiverTimeout = null
            negotiationTimeout?.cancel()
            negotiationTimeout = null
            retryHandle?.cancel()
            retryHandle = null

            deliveryGeneration += 1
            attemptGeneration += 1
            peerGeneration += 1
            this.options = validated
            statusListener = listener
            retryAttempt = 0
            signaling = null
            publisher = null
            peerPending = false
            captureStarted = false
            offerCreated = false
            hasStreamed = false
            releaseRequested = false
            stateMachine.moveTo(StreamingState.PREPARING)
            status = StreamingStatus(StreamingState.PREPARING)
            Triple(
                deliveryGeneration,
                permissionChecker.check(validated),
                notificationLocked(),
            )
        }

        dispatch(startPlan.third)
        if (!isRunInState(startPlan.first, StreamingState.PREPARING)) return
        val permissionFailure = startPlan.second
        if (permissionFailure != null) {
            failWithoutRetry(startPlan.first, permissionFailure)
        } else {
            connectAttempt(startPlan.first)
        }
    }

    override fun stop() {
        val stopPlan = synchronized(lock) {
            val current = stateMachine.current()
            if (current == StreamingState.IDLE || current == StreamingState.RELEASED || current == StreamingState.STOPPING) {
                return
            }

            deliveryGeneration += 1
            attemptGeneration += 1
            peerGeneration += 1
            receiverTimeout?.cancel()
            receiverTimeout = null
            negotiationTimeout?.cancel()
            negotiationTimeout = null
            retryHandle?.cancel()
            retryHandle = null
            stateMachine.moveTo(StreamingState.STOPPING)
            status = StreamingStatus(
                state = StreamingState.STOPPING,
                stats = status.stats,
                retryAttempt = retryAttempt,
            )
            val token = deliveryGeneration
            Triple(token, detachAttemptLocked(stopCapture = true), notificationLocked())
        }

        dispatch(stopPlan.third)
        cleanup(stopPlan.second)

        val stopCompletion = synchronized(lock) {
            if (
                deliveryGeneration != stopPlan.first ||
                stateMachine.current() != StreamingState.STOPPING
            ) {
                null
            } else {
                val terminalState = if (releaseRequested) {
                    StreamingState.RELEASED
                } else {
                    StreamingState.IDLE
                }
                stateMachine.moveTo(terminalState)
                status = StreamingStatus(terminalState)
                options = null
                retryAttempt = 0
                terminalState to notificationLocked()
            }
        }
        if (stopCompletion?.first == StreamingState.RELEASED) releaseCaptureSafely()
        dispatch(stopCompletion?.second)
    }

    override fun release() {
        val releasePlan = synchronized(lock) {
            val current = stateMachine.current()
            if (current == StreamingState.RELEASED) return
            if (current == StreamingState.STOPPING) {
                releaseRequested = true
                return
            }

            releaseRequested = true
            deliveryGeneration += 1
            attemptGeneration += 1
            peerGeneration += 1
            receiverTimeout?.cancel()
            receiverTimeout = null
            negotiationTimeout?.cancel()
            negotiationTimeout = null
            retryHandle?.cancel()
            retryHandle = null
            val token = deliveryGeneration
            val stoppingNotification = if (current == StreamingState.IDLE) {
                null
            } else {
                stateMachine.moveTo(StreamingState.STOPPING)
                status = StreamingStatus(
                    state = StreamingState.STOPPING,
                    stats = status.stats,
                    retryAttempt = retryAttempt,
                )
                notificationLocked()
            }
            Triple(token, detachAttemptLocked(stopCapture = true), stoppingNotification)
        }

        dispatch(releasePlan.third)
        cleanup(releasePlan.second)
        releaseCaptureSafely()

        val releasedNotification = synchronized(lock) {
            if (deliveryGeneration != releasePlan.first) {
                null
            } else {
                stateMachine.moveTo(StreamingState.RELEASED)
                status = StreamingStatus(StreamingState.RELEASED)
                options = null
                retryAttempt = 0
                notificationLocked()
            }
        }
        dispatch(releasedNotification)
    }

    override fun currentStatus(): StreamingStatus = synchronized(lock) { status }

    private fun connectAttempt(runGeneration: Long) {
        val attempt = synchronized(lock) {
            if (!isRunInStateLocked(runGeneration, StreamingState.PREPARING)) return
            attemptGeneration += 1
            attemptGeneration
        }

        val session = try {
            signalingFactory.create()
        } catch (error: Throwable) {
            failNetwork(
                runGeneration,
                attempt,
                failure(MediaErrorCode.SERVER_UNREACHABLE, "Failed to create signaling client", error),
            )
            return
        }

        val accepted = synchronized(lock) {
            if (isAttemptActiveLocked(runGeneration, attempt) && signaling == null) {
                signaling = session
                true
            } else {
                false
            }
        }
        if (!accepted) {
            session.dispose()
            return
        }

        val activeOptions = synchronized(lock) { options } ?: return
        try {
            session.connect(
                activeOptions.serverUrl,
                activeOptions.roomId,
                signalingListener(runGeneration, attempt, session),
            )
        } catch (error: Throwable) {
            failNetwork(
                runGeneration,
                attempt,
                failure(MediaErrorCode.SERVER_UNREACHABLE, "Signaling connect threw", error),
            )
        }
    }

    private fun signalingListener(
        runGeneration: Long,
        attempt: Long,
        session: SignalingSession,
    ) = object : SignalingSession.Listener {
        override fun onOpen() {
            onSignalingOpen(runGeneration, attempt, session)
        }

        override fun onMessage(message: SignalingMessage) {
            handleSignalingMessage(runGeneration, attempt, session, message)
        }

        override fun onClosed(reason: String) {
            failSignaling(runGeneration, attempt, session, "Signaling closed: $reason", null)
        }

        override fun onFailure(error: Throwable) {
            failSignaling(runGeneration, attempt, session, "Signaling failed", error)
        }
    }

    private fun onSignalingOpen(
        runGeneration: Long,
        attempt: Long,
        session: SignalingSession,
    ) {
        val notification = synchronized(lock) {
            if (!isAttemptActiveLocked(runGeneration, attempt, session)) return
            stateMachine.moveTo(StreamingState.WAITING_RECEIVER)
            status = StreamingStatus(
                state = StreamingState.WAITING_RECEIVER,
                retryAttempt = retryAttempt,
            )
            notificationLocked()
        }
        dispatch(notification)
        scheduleReceiverTimeout(runGeneration, attempt, session)
    }

    private fun scheduleReceiverTimeout(
        runGeneration: Long,
        attempt: Long,
        session: SignalingSession,
    ) {
        val handle = scheduler.schedule(RECEIVER_TIMEOUT_MS) {
            onReceiverTimeout(runGeneration, attempt, session)
        }
        val accepted = synchronized(lock) {
            if (
                isAttemptActiveLocked(runGeneration, attempt, session) &&
                stateMachine.current() == StreamingState.WAITING_RECEIVER &&
                !peerPending && publisher == null
            ) {
                receiverTimeout?.cancel()
                receiverTimeout = handle
                true
            } else {
                false
            }
        }
        if (!accepted) handle.cancel()
    }

    private fun onReceiverTimeout(
        runGeneration: Long,
        attempt: Long,
        session: SignalingSession,
    ) {
        val notification = synchronized(lock) {
            if (
                !isAttemptActiveLocked(runGeneration, attempt, session) ||
                stateMachine.current() != StreamingState.WAITING_RECEIVER ||
                peerPending || publisher != null
            ) {
                return
            }
            receiverTimeout = null
            status = status.copy(
                state = StreamingState.WAITING_RECEIVER,
                failure = MediaFailureCatalog.forCode(MediaErrorCode.RECEIVER_NOT_READY),
            )
            notificationLocked()
        }
        dispatch(notification)
    }

    private fun handleSignalingMessage(
        runGeneration: Long,
        attempt: Long,
        session: SignalingSession,
        message: SignalingMessage,
    ) {
        if (!isAttemptActive(runGeneration, attempt, session)) return
        when (message.type) {
            SignalingType.PEER_READY -> startPeer(runGeneration, attempt, session)
            SignalingType.ANSWER -> {
                val sdp = message.sdp
                if (sdp == null) {
                    failPublisher(runGeneration, attempt, peerGenerationSnapshot(), "Answer is missing SDP", null)
                } else {
                    publisherCall(runGeneration, attempt, "Failed to apply remote answer") {
                        it.setRemoteAnswer(sdp)
                    }
                }
            }

            SignalingType.ICE_CANDIDATE -> {
                val candidate = message.candidate
                if (candidate == null) {
                    failPublisher(runGeneration, attempt, peerGenerationSnapshot(), "ICE message is missing a candidate", null)
                } else {
                    publisherCall(runGeneration, attempt, "Failed to apply remote ICE candidate") {
                        it.addRemoteIceCandidate(candidate)
                    }
                }
            }

            SignalingType.LEAVE -> receiverLeft(runGeneration, attempt, session)
            SignalingType.ERROR -> failSignaling(
                runGeneration,
                attempt,
                session,
                "Signaling server error: ${message.message ?: "unknown"}",
                null,
            )

            SignalingType.JOIN,
            SignalingType.OFFER,
            -> Unit
        }
    }

    private fun startPeer(
        runGeneration: Long,
        attempt: Long,
        session: SignalingSession,
    ) {
        val peerToken = synchronized(lock) {
            if (
                !isAttemptActiveLocked(runGeneration, attempt, session) ||
                stateMachine.current() != StreamingState.WAITING_RECEIVER ||
                peerPending || publisher != null
            ) {
                return
            }
            receiverTimeout?.cancel()
            receiverTimeout = null
            peerPending = true
            peerGeneration += 1
            peerGeneration
        }

        val created = try {
            publisherFactory.create()
        } catch (error: Throwable) {
            failPublisher(runGeneration, attempt, peerToken, "Failed to create WebRTC publisher", error)
            return
        }

        val activeOptions = synchronized(lock) {
            if (
                isPeerActiveLocked(runGeneration, attempt, peerToken) &&
                publisher == null
            ) {
                publisher = created
                options
            } else {
                null
            }
        }
        if (activeOptions == null) {
            created.close()
            return
        }

        try {
            created.prepare(
                PublishOptions(activeOptions.videoEnabled, activeOptions.audioEnabled),
                publisherListener(runGeneration, attempt, peerToken, created),
            )
        } catch (error: Throwable) {
            failPublisher(runGeneration, attempt, peerToken, "WebRTC publisher preparation threw", error)
            return
        }

        val shouldStartCapture = synchronized(lock) {
            if (isPeerActiveLocked(runGeneration, attempt, peerToken, created) && !captureStarted) {
                captureStarted = true
                true
            } else {
                false
            }
        }
        if (!shouldStartCapture) return

        try {
            capture.start(
                CaptureOptions(),
                created.takeIf { activeOptions.videoEnabled },
                created.takeIf { activeOptions.audioEnabled },
                CaptureStatusListener { captureStatus ->
                    onCaptureStatus(runGeneration, attempt, peerToken, created, captureStatus)
                },
            )
        } catch (error: Throwable) {
            failCapture(
                runGeneration,
                attempt,
                peerToken,
                failure(MediaErrorCode.SDK_NOT_READY, "Media capture start threw", error),
            )
        }
    }

    private fun onCaptureStatus(
        runGeneration: Long,
        attempt: Long,
        peerToken: Long,
        activePublisher: MediaPublisher,
        captureStatus: CaptureStatus,
    ) {
        when (captureStatus.state) {
            CaptureState.CAPTURING -> {
                val notification = synchronized(lock) {
                    if (
                        !isPeerActiveLocked(runGeneration, attempt, peerToken, activePublisher) ||
                        offerCreated
                    ) {
                        return
                    }
                    offerCreated = true
                    peerPending = false
                    stateMachine.moveTo(StreamingState.NEGOTIATING)
                    status = status.copy(
                        state = StreamingState.NEGOTIATING,
                        failure = null,
                        retryAttempt = retryAttempt,
                    )
                    notificationLocked()
                }
                dispatch(notification)
                if (!isPeerActive(runGeneration, attempt, peerToken, activePublisher)) return
                scheduleNegotiationTimeout(runGeneration, attempt, peerToken, activePublisher)
                try {
                    activePublisher.createOffer()
                } catch (error: Throwable) {
                    failPublisher(runGeneration, attempt, peerToken, "WebRTC offer creation threw", error)
                }
            }

            CaptureState.ERROR -> failCapture(
                runGeneration,
                attempt,
                peerToken,
                captureStatus.failure ?: failure(
                    MediaErrorCode.SDK_DISCONNECTED,
                    "Capture entered ERROR without a MediaFailure",
                    null,
                ),
            )

            CaptureState.RELEASED -> failCapture(
                runGeneration,
                attempt,
                peerToken,
                failure(MediaErrorCode.SDK_DISCONNECTED, "Capture was released while streaming", null),
            )

            CaptureState.IDLE,
            CaptureState.PREPARING,
            CaptureState.STOPPING,
            -> Unit
        }
    }

    private fun scheduleNegotiationTimeout(
        runGeneration: Long,
        attempt: Long,
        peerToken: Long,
        activePublisher: MediaPublisher,
    ) {
        val handle = scheduler.schedule(NEGOTIATION_TIMEOUT_MS) {
            failPublisher(
                runGeneration,
                attempt,
                peerToken,
                "WebRTC negotiation timed out after ${NEGOTIATION_TIMEOUT_MS}ms",
                null,
            )
        }
        val accepted = synchronized(lock) {
            if (
                isPeerActiveLocked(runGeneration, attempt, peerToken, activePublisher) &&
                stateMachine.current() == StreamingState.NEGOTIATING
            ) {
                negotiationTimeout?.cancel()
                negotiationTimeout = handle
                true
            } else {
                false
            }
        }
        if (!accepted) handle.cancel()
    }

    private fun publisherListener(
        runGeneration: Long,
        attempt: Long,
        peerToken: Long,
        activePublisher: MediaPublisher,
    ) = object : MediaPublisher.Listener {
        override fun onLocalOffer(sdp: String) {
            sendSignaling(
                runGeneration,
                attempt,
                peerToken,
                activePublisher,
                SignalingMessage(
                    type = SignalingType.OFFER,
                    roomId = roomIdSnapshot(),
                    sdp = sdp,
                ),
                "Failed to send local WebRTC offer",
            )
        }

        override fun onLocalIceCandidate(candidate: com.rokid.glass.mediastream.transport.signaling.IceCandidatePayload) {
            sendSignaling(
                runGeneration,
                attempt,
                peerToken,
                activePublisher,
                SignalingMessage(
                    type = SignalingType.ICE_CANDIDATE,
                    roomId = roomIdSnapshot(),
                    candidate = candidate,
                ),
                "Failed to send local ICE candidate",
            )
        }

        override fun onConnected() {
            val notification = synchronized(lock) {
                if (!isPeerActiveLocked(runGeneration, attempt, peerToken, activePublisher)) return
                if (stateMachine.current() != StreamingState.NEGOTIATING) return
                negotiationTimeout?.cancel()
                negotiationTimeout = null
                stateMachine.moveTo(StreamingState.STREAMING)
                hasStreamed = true
                status = status.copy(
                    state = StreamingState.STREAMING,
                    failure = null,
                    retryAttempt = retryAttempt,
                )
                notificationLocked()
            }
            dispatch(notification)
        }

        override fun onDisconnected(reason: String) {
            failPublisher(runGeneration, attempt, peerToken, "WebRTC disconnected: $reason", null)
        }

        override fun onFailure(message: String, cause: Throwable?) {
            failPublisher(runGeneration, attempt, peerToken, message, cause)
        }

        override fun onStats(stats: TransportStats) {
            mergeStats(runGeneration, attempt, peerToken, activePublisher, stats)
        }
    }

    private fun sendSignaling(
        runGeneration: Long,
        attempt: Long,
        peerToken: Long,
        activePublisher: MediaPublisher,
        message: SignalingMessage,
        technicalMessage: String,
    ) {
        val activeSignaling = synchronized(lock) {
            if (isPeerActiveLocked(runGeneration, attempt, peerToken, activePublisher)) signaling else null
        } ?: return
        val sent = try {
            activeSignaling.send(message)
        } catch (error: Throwable) {
            failSignaling(runGeneration, attempt, activeSignaling, technicalMessage, error)
            return
        }
        if (!sent) failSignaling(runGeneration, attempt, activeSignaling, technicalMessage, null)
    }

    private fun publisherCall(
        runGeneration: Long,
        attempt: Long,
        technicalMessage: String,
        operation: (MediaPublisher) -> Unit,
    ) {
        val snapshot = synchronized(lock) {
            val active = publisher ?: return
            if (!isAttemptActiveLocked(runGeneration, attempt)) return
            peerGeneration to active
        }
        try {
            operation(snapshot.second)
        } catch (error: Throwable) {
            failPublisher(runGeneration, attempt, snapshot.first, technicalMessage, error)
        }
    }

    private fun mergeStats(
        runGeneration: Long,
        attempt: Long,
        peerToken: Long,
        activePublisher: MediaPublisher,
        transport: TransportStats,
    ) {
        val captureStatus = try {
            capture.currentStatus()
        } catch (error: Throwable) {
            failCapture(
                runGeneration,
                attempt,
                peerToken,
                failure(MediaErrorCode.SDK_DISCONNECTED, "Failed to read capture status", error),
            )
            return
        }
        val notification = synchronized(lock) {
            if (!isPeerActiveLocked(runGeneration, attempt, peerToken, activePublisher)) return
            status = status.copy(
                stats = StreamingStats(
                    videoWidth = captureStatus.videoMetrics.width,
                    videoHeight = captureStatus.videoMetrics.height,
                    videoFps = captureStatus.videoMetrics.fps,
                    videoBitrateBps = transport.videoBitrateBps,
                    audioBitrateBps = transport.audioBitrateBps,
                    packetsLost = transport.packetsLost,
                    roundTripTimeMs = transport.roundTripTimeMs,
                    pcmUnderrunBytes = transport.pcmUnderrunBytes,
                    pcmDroppedBytes = transport.pcmDroppedBytes,
                ),
            )
            notificationLocked()
        }
        dispatch(notification)
    }

    private fun receiverLeft(
        runGeneration: Long,
        attempt: Long,
        session: SignalingSession,
    ) {
        val leavePlan = synchronized(lock) {
            if (!isAttemptActiveLocked(runGeneration, attempt, session)) return
            if (publisher == null && !captureStarted && !peerPending) return
            receiverTimeout?.cancel()
            receiverTimeout = null
            negotiationTimeout?.cancel()
            negotiationTimeout = null
            peerGeneration += 1
            val closingPublisher = publisher
            publisher = null
            peerPending = false
            val shouldStopCapture = captureStarted
            captureStarted = false
            offerCreated = false
            if (stateMachine.current() != StreamingState.WAITING_RECEIVER) {
                stateMachine.moveTo(StreamingState.WAITING_RECEIVER)
            }
            status = StreamingStatus(
                state = StreamingState.WAITING_RECEIVER,
                stats = status.stats,
                retryAttempt = retryAttempt,
            )
            Pair(AttemptCleanup(closingPublisher, null, shouldStopCapture), notificationLocked())
        }

        cleanup(leavePlan.first)
        dispatch(leavePlan.second)
        scheduleReceiverTimeout(runGeneration, attempt, session)
    }

    private fun failSignaling(
        runGeneration: Long,
        attempt: Long,
        session: SignalingSession,
        technicalMessage: String,
        cause: Throwable?,
    ) {
        val activeAndCode = synchronized(lock) {
            if (!isAttemptActiveLocked(runGeneration, attempt, session)) return
            if (hasStreamed) MediaErrorCode.NETWORK_DISCONNECTED else MediaErrorCode.SERVER_UNREACHABLE
        }
        failNetwork(runGeneration, attempt, failure(activeAndCode, technicalMessage, cause))
    }

    private fun failPublisher(
        runGeneration: Long,
        attempt: Long,
        peerToken: Long,
        technicalMessage: String,
        cause: Throwable?,
    ) {
        val code = synchronized(lock) {
            if (!isPeerActiveLocked(runGeneration, attempt, peerToken)) return
            if (hasStreamed) MediaErrorCode.NETWORK_DISCONNECTED else MediaErrorCode.WEBRTC_NEGOTIATION_FAILED
        }
        failNetwork(runGeneration, attempt, failure(code, technicalMessage, cause))
    }

    private fun failCapture(
        runGeneration: Long,
        attempt: Long,
        peerToken: Long,
        failure: MediaFailure,
    ) {
        if (failure.code == MediaErrorCode.AUDIO_DATA_TIMEOUT) {
            retryCapture(runGeneration, attempt, peerToken, failure)
            return
        }
        val plan = synchronized(lock) {
            if (!isPeerActiveLocked(runGeneration, attempt, peerToken)) return
            receiverTimeout?.cancel()
            receiverTimeout = null
            retryHandle?.cancel()
            retryHandle = null
            attemptGeneration += 1
            peerGeneration += 1
            stateMachine.moveTo(StreamingState.ERROR)
            status = StreamingStatus(
                state = StreamingState.ERROR,
                stats = status.stats,
                retryAttempt = retryAttempt,
                failure = failure,
            )
            Pair(detachAttemptLocked(stopCapture = true), notificationLocked())
        }
        logger.error(failure)
        cleanup(plan.first)
        dispatch(plan.second)
    }

    /**
     * Glass3 may rebuild its system AudioRecord after detecting an unhealthy recorder. The old
     * Binder callback is not reattached to that new recorder, so waiting longer cannot recover the
     * stream. Tear down the complete attempt and use the same bounded 1/2/4-second retry policy as
     * network recovery. Deterministic capture failures (permission, camera in use, unsupported
     * format) still go through [failCapture] without an automatic retry.
     */
    private fun retryCapture(
        runGeneration: Long,
        attempt: Long,
        peerToken: Long,
        failure: MediaFailure,
    ) {
        val plan = synchronized(lock) {
            if (!isPeerActiveLocked(runGeneration, attempt, peerToken)) return
            receiverTimeout?.cancel()
            receiverTimeout = null
            retryHandle?.cancel()
            retryHandle = null
            attemptGeneration += 1
            peerGeneration += 1

            stateMachine.moveTo(StreamingState.ERROR)
            status = StreamingStatus(
                state = StreamingState.ERROR,
                stats = status.stats,
                retryAttempt = retryAttempt,
                failure = failure,
            )
            val nextRetry = if (retryAttempt >= MAX_RETRY_COUNT) null else retryAttempt + 1
            FailurePlan(
                cleanup = detachAttemptLocked(stopCapture = true),
                notification = notificationLocked(),
                retryNumber = nextRetry,
                retryDelayMs = nextRetry?.let { RETRY_DELAYS_MS[it - 1] },
                failure = failure,
            )
        }

        logger.error(plan.failure)
        cleanup(plan.cleanup)
        dispatch(plan.notification)
        if (plan.retryNumber != null && plan.retryDelayMs != null) {
            scheduleRetry(runGeneration, plan.retryNumber, plan.retryDelayMs)
        }
    }

    private fun failWithoutRetry(runGeneration: Long, failure: MediaFailure) {
        val notification = synchronized(lock) {
            if (!isRunInStateLocked(runGeneration, StreamingState.PREPARING)) return
            stateMachine.moveTo(StreamingState.ERROR)
            status = StreamingStatus(
                state = StreamingState.ERROR,
                retryAttempt = retryAttempt,
                failure = failure,
            )
            notificationLocked()
        }
        logger.error(failure)
        dispatch(notification)
    }

    private fun failNetwork(
        runGeneration: Long,
        attempt: Long,
        reportedFailure: MediaFailure,
    ) {
        val plan = synchronized(lock) {
            if (!isAttemptActiveLocked(runGeneration, attempt)) return
            receiverTimeout?.cancel()
            receiverTimeout = null
            retryHandle?.cancel()
            retryHandle = null
            attemptGeneration += 1
            peerGeneration += 1

            val exhausted = retryAttempt >= MAX_RETRY_COUNT
            val finalFailure = if (exhausted && reportedFailure.code != MediaErrorCode.NETWORK_DISCONNECTED) {
                failure(
                    MediaErrorCode.NETWORK_DISCONNECTED,
                    reportedFailure.technicalMessage,
                    reportedFailure.cause,
                )
            } else {
                reportedFailure
            }
            stateMachine.moveTo(StreamingState.ERROR)
            status = StreamingStatus(
                state = StreamingState.ERROR,
                stats = status.stats,
                retryAttempt = retryAttempt,
                failure = finalFailure,
            )
            val nextRetry = if (exhausted) null else retryAttempt + 1
            FailurePlan(
                cleanup = detachAttemptLocked(stopCapture = true),
                notification = notificationLocked(),
                retryNumber = nextRetry,
                retryDelayMs = nextRetry?.let { RETRY_DELAYS_MS[it - 1] },
                failure = finalFailure,
            )
        }

        logger.error(plan.failure)
        cleanup(plan.cleanup)
        dispatch(plan.notification)
        if (plan.retryNumber != null && plan.retryDelayMs != null) {
            scheduleRetry(runGeneration, plan.retryNumber, plan.retryDelayMs)
        }
    }

    private fun scheduleRetry(runGeneration: Long, retryNumber: Int, delayMs: Long) {
        val handle = scheduler.schedule(delayMs) {
            beginRetry(runGeneration, retryNumber)
        }
        val accepted = synchronized(lock) {
            if (
                deliveryGeneration == runGeneration &&
                stateMachine.current() == StreamingState.ERROR &&
                retryAttempt == retryNumber - 1
            ) {
                retryHandle = handle
                true
            } else {
                false
            }
        }
        if (!accepted) handle.cancel()
    }

    private fun beginRetry(runGeneration: Long, retryNumber: Int) {
        val notification = synchronized(lock) {
            if (
                deliveryGeneration != runGeneration ||
                stateMachine.current() != StreamingState.ERROR ||
                retryAttempt != retryNumber - 1
            ) {
                return
            }
            retryHandle = null
            retryAttempt = retryNumber
            stateMachine.moveTo(StreamingState.PREPARING)
            status = StreamingStatus(
                state = StreamingState.PREPARING,
                stats = status.stats,
                retryAttempt = retryAttempt,
            )
            notificationLocked()
        }
        dispatch(notification)
        if (isRunInState(runGeneration, StreamingState.PREPARING)) connectAttempt(runGeneration)
    }

    private fun detachAttemptLocked(stopCapture: Boolean): AttemptCleanup {
        negotiationTimeout?.cancel()
        negotiationTimeout = null
        val detached = AttemptCleanup(
            publisher = publisher,
            signaling = signaling,
            stopCapture = stopCapture,
        )
        publisher = null
        signaling = null
        peerPending = false
        captureStarted = false
        offerCreated = false
        return detached
    }

    private fun cleanup(cleanup: AttemptCleanup) {
        runCatching { cleanup.publisher?.close() }
        runCatching { cleanup.signaling?.close() }
        runCatching { cleanup.signaling?.dispose() }
        if (cleanup.stopCapture) runCatching { capture.stop() }
    }

    private fun releaseCaptureSafely() {
        try {
            capture.release()
        } catch (error: Throwable) {
            logger.error(
                failure(
                    MediaErrorCode.SDK_DISCONNECTED,
                    "Media capture release failed",
                    error,
                ),
            )
        }
    }

    private fun notificationLocked(): StatusNotification? {
        val listener = statusListener ?: return null
        return StatusNotification(deliveryGeneration, listener, status)
    }

    private fun dispatch(notification: StatusNotification?) {
        if (notification == null) return
        callbackExecutor.execute {
            val deliver = synchronized(lock) {
                deliveryGeneration == notification.deliveryGeneration &&
                    statusListener === notification.listener
            }
            if (deliver) notification.listener.onStatusChanged(notification.status)
        }
    }

    private fun isRunInState(runGeneration: Long, expected: StreamingState): Boolean =
        synchronized(lock) { isRunInStateLocked(runGeneration, expected) }

    private fun isRunInStateLocked(runGeneration: Long, expected: StreamingState): Boolean =
        deliveryGeneration == runGeneration && stateMachine.current() == expected

    private fun isAttemptActive(
        runGeneration: Long,
        attempt: Long,
        session: SignalingSession,
    ): Boolean = synchronized(lock) { isAttemptActiveLocked(runGeneration, attempt, session) }

    private fun isAttemptActiveLocked(
        runGeneration: Long,
        attempt: Long,
        session: SignalingSession? = null,
    ): Boolean =
        deliveryGeneration == runGeneration &&
            attemptGeneration == attempt &&
            stateMachine.current() in ATTEMPT_STATES &&
            (session == null || signaling === session)

    private fun isPeerActive(
        runGeneration: Long,
        attempt: Long,
        peerToken: Long,
        activePublisher: MediaPublisher,
    ): Boolean = synchronized(lock) {
        isPeerActiveLocked(runGeneration, attempt, peerToken, activePublisher)
    }

    private fun isPeerActiveLocked(
        runGeneration: Long,
        attempt: Long,
        peerToken: Long,
        activePublisher: MediaPublisher? = null,
    ): Boolean =
        isAttemptActiveLocked(runGeneration, attempt) &&
            peerGeneration == peerToken &&
            (peerPending || publisher != null) &&
            (activePublisher == null || publisher === activePublisher)

    private fun roomIdSnapshot(): String = synchronized(lock) {
        options?.roomId ?: SignalingMessage.DEFAULT_ROOM_ID
    }

    private fun peerGenerationSnapshot(): Long = synchronized(lock) { peerGeneration }

    private fun failure(
        code: MediaErrorCode,
        technicalMessage: String,
        cause: Throwable?,
    ): MediaFailure = MediaFailureCatalog.forCode(code).copy(
        technicalMessage = buildString {
            append(technicalMessage)
            cause?.message?.takeIf(String::isNotBlank)?.let {
                append(": ")
                append(it)
            }
        },
        cause = cause,
    )

    private companion object {
        const val RECEIVER_TIMEOUT_MS = 30_000L
        const val NEGOTIATION_TIMEOUT_MS = 15_000L
        const val MAX_RETRY_COUNT = 3
        val RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 4_000L)
        val ACTIVE_STATES = setOf(
            StreamingState.PREPARING,
            StreamingState.WAITING_RECEIVER,
            StreamingState.NEGOTIATING,
            StreamingState.STREAMING,
            StreamingState.STOPPING,
        )
        val ATTEMPT_STATES = setOf(
            StreamingState.PREPARING,
            StreamingState.WAITING_RECEIVER,
            StreamingState.NEGOTIATING,
            StreamingState.STREAMING,
        )
    }
}
