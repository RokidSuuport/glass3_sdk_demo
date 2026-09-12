package com.rokid.glass.mediastream.transport.webrtc

import android.content.Context
import com.rokid.glass.mediastream.capture.AudioFrameListener
import com.rokid.glass.mediastream.capture.Nv21Frame
import com.rokid.glass.mediastream.capture.PcmFrame
import com.rokid.glass.mediastream.capture.VideoFrameListener
import com.rokid.glass.mediastream.transport.signaling.IceCandidatePayload
import com.rokid.glass.mediastream.transport.webrtc.audio.AudioDeviceModuleConfig
import com.rokid.glass.mediastream.transport.webrtc.audio.PcmRingBuffer
import com.rokid.glass.mediastream.transport.webrtc.audio.WebRtcAudioAdapter
import com.rokid.glass.mediastream.transport.webrtc.video.VideoFrameTarget
import com.rokid.glass.mediastream.transport.webrtc.video.WebRtcVideoAdapter

data class PublishOptions @JvmOverloads constructor(
    val videoEnabled: Boolean,
    val audioEnabled: Boolean,
    val maxVideoBitrateBps: Int? = null,
)

interface MediaPublisher : VideoFrameListener, AudioFrameListener {
    fun prepare(options: PublishOptions, listener: Listener)
    fun createOffer()
    fun setRemoteAnswer(sdp: String)
    fun addRemoteIceCandidate(candidate: IceCandidatePayload)
    fun close()

    interface Listener {
        fun onLocalOffer(sdp: String)
        fun onLocalIceCandidate(candidate: IceCandidatePayload)
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onFailure(message: String, cause: Throwable? = null)
        fun onStats(stats: TransportStats)
    }
}

internal enum class PublisherConnectionState {
    CONNECTED,
    DISCONNECTED,
    FAILED,
    CLOSED,
}

internal interface PublisherSessionEvents {
    fun onLocalIceCandidate(candidate: IceCandidatePayload)
    fun onConnectionStateChanged(state: PublisherConnectionState)
    fun onStats(stats: TransportStats)
    fun onFailure(message: String, cause: Throwable? = null)
}

internal interface PublisherSessionFactory {
    fun create(
        options: PublishOptions,
        audioAdapter: WebRtcAudioAdapter,
        audioConfig: AudioDeviceModuleConfig,
        events: PublisherSessionEvents,
    ): PublisherSession
}

internal interface PublisherSession {
    val videoFrameTarget: VideoFrameTarget?

    fun createOffer(onResult: (Result<String>) -> Unit)
    fun setRemoteAnswer(sdp: String, onResult: (Result<Unit>) -> Unit)
    fun addRemoteIceCandidate(candidate: IceCandidatePayload): Boolean

    /**
     * Releases native media in dependency order. The supplied callback must be invoked after
     * native tracks/sources stop reading external PCM and before the audio module is released.
     */
    fun close(releaseAudioAdapter: () -> Unit)
}

class WebRtcPublisher internal constructor(
    private val sessionFactory: PublisherSessionFactory,
    private val ringBufferFactory: () -> PcmRingBuffer = {
        PcmRingBuffer(bytesPerSecond = EXTERNAL_PCM_BYTES_PER_SECOND)
    },
) : MediaPublisher {
    constructor(context: Context) : this(NativePublisherSessionFactory(context.applicationContext))

    private enum class Phase {
        IDLE,
        PREPARING,
        PREPARED,
        CREATING_OFFER,
        LOCAL_OFFER_SET,
        SETTING_ANSWER,
        REMOTE_ANSWER_SET,
        CONNECTED,
    }

    private data class CloseSnapshot(
        val session: PublisherSession?,
        val audioAdapter: WebRtcAudioAdapter?,
    )

    private data class CandidateTarget(
        val generation: Long,
        val session: PublisherSession,
    )

    private data class Notification(
        val generation: Long,
        val listener: MediaPublisher.Listener,
        val deliver: (MediaPublisher.Listener) -> Unit,
    )

    private val lock = Any()
    private val notifications = ArrayDeque<Notification>()
    private var notificationDrainActive = false
    // A close invalidates callbacks immediately, but does not dispose a source while a frame or
    // synchronous native operation still uses it. Each generation releases its own final lease.
    private val activeOperations = mutableMapOf<Long, Int>()
    private val deferredCloses = mutableMapOf<Long, CloseSnapshot>()
    private var generation = 0L
    private var phase = Phase.IDLE
    private var options: PublishOptions? = null
    private var listener: MediaPublisher.Listener? = null
    private var session: PublisherSession? = null
    private var audioAdapter: WebRtcAudioAdapter? = null
    private var videoAdapter: WebRtcVideoAdapter? = null
    private val pendingLocalCandidates = ArrayList<IceCandidatePayload>()
    private val pendingRemoteCandidates = ArrayList<IceCandidatePayload>()
    private var remoteIceDrainActive = false
    private var connected = false
    private var terminalConnectionReported = false

    override fun prepare(options: PublishOptions, listener: MediaPublisher.Listener) {
        require(options.videoEnabled || options.audioEnabled) {
            "At least one media source must be enabled"
        }
        require(options.maxVideoBitrateBps == null || options.maxVideoBitrateBps > 0) {
            "maxVideoBitrateBps must be positive"
        }
        val adapter = WebRtcAudioAdapter(ringBufferFactory())
        val callbackGeneration = withState {
            check(phase == Phase.IDLE) { "WebRtcPublisher is already prepared" }
            generation += 1
            phase = Phase.PREPARING
            this.options = options
            this.listener = listener
            audioAdapter = adapter
            connected = false
            terminalConnectionReported = false
            pendingLocalCandidates.clear()
            pendingRemoteCandidates.clear()
            remoteIceDrainActive = false
            generation
        }

        val created = try {
            sessionFactory.create(
                options = options,
                audioAdapter = adapter,
                audioConfig = AudioDeviceModuleConfig(),
                events = sessionEvents(callbackGeneration),
            )
        } catch (error: Throwable) {
            failPrepare(callbackGeneration, adapter, "WebRTC initialization failed", error)
            return
        }

        val adapterResult = runCatching {
            val frameTarget = created.videoFrameTarget
            if (options.videoEnabled) {
                requireNotNull(frameTarget) {
                    "Video-enabled publisher did not create a video source"
                }
            } else {
                check(frameTarget == null) {
                    "Video-disabled publisher unexpectedly created a video source"
                }
            }
            frameTarget?.let(::WebRtcVideoAdapter)
        }
        val setupError = adapterResult.exceptionOrNull()
        if (setupError != null) {
            runCatching { created.close(adapter::close) }
            failPrepare(callbackGeneration, adapter, "WebRTC media setup failed", setupError)
            return
        }

        val accepted = withState {
            if (generation == callbackGeneration && phase == Phase.PREPARING) {
                session = created
                videoAdapter = adapterResult.getOrNull()
                phase = Phase.PREPARED
                true
            } else {
                false
            }
        }
        if (!accepted) {
            var adapterReleased = false
            try {
                created.close {
                    adapterReleased = true
                    adapter.close()
                }
            } finally {
                if (!adapterReleased) adapter.close()
            }
        }
    }

    override fun createOffer() {
        val current = withState {
            val activeSession = session
            if (phase != Phase.PREPARED || activeSession == null) {
                notifyNegotiationFailureLocked("Cannot create a duplicate or out-of-order offer")
                null
            } else {
                phase = Phase.CREATING_OFFER
                beginOperationLocked()
                generation to activeSession
            }
        } ?: return

        try {
            current.second.createOffer { result ->
                handleOfferResult(current.first, result)
            }
        } catch (error: Throwable) {
            handleOfferResult(current.first, Result.failure(error))
        } finally {
            finishOperation(current.first)
        }
    }

    override fun setRemoteAnswer(sdp: String) {
        val current = withState {
            if (sdp.isBlank()) {
                notifyNegotiationFailureLocked("Remote answer SDP must not be blank")
                return@withState null
            }
            val activeSession = session
            if (phase != Phase.LOCAL_OFFER_SET || activeSession == null) {
                notifyNegotiationFailureLocked("Cannot set a duplicate or out-of-order remote answer")
                null
            } else {
                phase = Phase.SETTING_ANSWER
                beginOperationLocked()
                generation to activeSession
            }
        } ?: return

        try {
            current.second.setRemoteAnswer(sdp) { result ->
                handleAnswerResult(current.first, result)
            }
        } catch (error: Throwable) {
            handleAnswerResult(current.first, Result.failure(error))
        } finally {
            finishOperation(current.first)
        }
    }

    override fun addRemoteIceCandidate(candidate: IceCandidatePayload) {
        val drainTarget = withState {
            val activeSession = session
                ?: throw IllegalStateException("WebRtcPublisher is not prepared")
            when (phase) {
                Phase.REMOTE_ANSWER_SET,
                Phase.CONNECTED,
                -> {
                    enqueueRemoteCandidateLocked(candidate)
                    if (remoteIceDrainActive) {
                        null
                    } else {
                        remoteIceDrainActive = true
                        CandidateTarget(generation, activeSession)
                    }
                }

                Phase.PREPARING,
                Phase.PREPARED,
                Phase.CREATING_OFFER,
                Phase.LOCAL_OFFER_SET,
                Phase.SETTING_ANSWER,
                -> {
                    enqueueRemoteCandidateLocked(candidate)
                    null
                }

                Phase.IDLE -> throw IllegalStateException("WebRtcPublisher is not prepared")
            }
        }
        if (drainTarget != null) drainRemoteCandidates(drainTarget)
    }

    override fun onVideoFrame(frame: Nv21Frame) {
        val target = withState {
            if (options?.videoEnabled != true) return
            val adapter = videoAdapter ?: return
            beginOperationLocked()
            generation to adapter
        }
        try {
            target.second.onVideoFrame(frame)
        } catch (error: Throwable) {
            withState {
                if (generation == target.first) {
                    notifyNegotiationFailureLocked("Failed to inject an external NV21 frame", error)
                }
            }
        } finally {
            finishOperation(target.first)
        }
    }

    override fun onAudioFrame(frame: PcmFrame) {
        val target = withState {
            if (options?.audioEnabled != true) return
            val adapter = audioAdapter ?: return
            beginOperationLocked()
            generation to adapter
        }
        try {
            target.second.onAudioFrame(frame)
        } catch (error: Throwable) {
            withState {
                if (generation == target.first) {
                    notifyNegotiationFailureLocked("Failed to inject an external PCM frame", error)
                }
            }
        } finally {
            finishOperation(target.first)
        }
    }

    override fun close() {
        val snapshot = withState {
            if (phase == Phase.IDLE && session == null && audioAdapter == null) return
            val closingGeneration = generation
            generation += 1
            val closing = CloseSnapshot(session, audioAdapter)
            phase = Phase.IDLE
            options = null
            listener = null
            session = null
            audioAdapter = null
            videoAdapter = null
            connected = false
            terminalConnectionReported = false
            pendingLocalCandidates.clear()
            pendingRemoteCandidates.clear()
            remoteIceDrainActive = false
            if ((activeOperations[closingGeneration] ?: 0) > 0) {
                deferredCloses[closingGeneration] = closing
                null
            } else {
                closing
            }
        }
        snapshot?.let(::releaseSnapshot)
    }

    private fun beginOperationLocked() {
        activeOperations[generation] = (activeOperations[generation] ?: 0) + 1
    }

    private fun finishOperation(operationGeneration: Long) {
        val closing = withState {
            val remaining = (activeOperations[operationGeneration] ?: 1) - 1
            if (remaining == 0) {
                activeOperations.remove(operationGeneration)
                deferredCloses.remove(operationGeneration)
            } else {
                activeOperations[operationGeneration] = remaining
                null
            }
        }
        closing?.let(::releaseSnapshot)
    }

    private fun releaseSnapshot(snapshot: CloseSnapshot) {
        var adapterReleased = false
        try {
            snapshot.session?.close {
                adapterReleased = true
                snapshot.audioAdapter?.close()
            }
        } finally {
            if (!adapterReleased) snapshot.audioAdapter?.close()
        }
    }

    private fun sessionEvents(callbackGeneration: Long): PublisherSessionEvents =
        object : PublisherSessionEvents {
            override fun onLocalIceCandidate(candidate: IceCandidatePayload) {
                withState {
                    if (generation != callbackGeneration || phase == Phase.IDLE) return
                    if (phase.ordinal >= Phase.LOCAL_OFFER_SET.ordinal) {
                        notifyListenerLocked { it.onLocalIceCandidate(candidate) }
                    } else if (pendingLocalCandidates.size < MAX_PENDING_CANDIDATES) {
                        pendingLocalCandidates += candidate
                    } else {
                        notifyNegotiationFailureLocked("Too many local ICE candidates")
                    }
                }
            }

            override fun onConnectionStateChanged(state: PublisherConnectionState) {
                withState {
                    if (generation != callbackGeneration || phase == Phase.IDLE) return
                    when (state) {
                        PublisherConnectionState.CONNECTED -> {
                            if (!connected && !terminalConnectionReported) {
                                connected = true
                                phase = Phase.CONNECTED
                                notifyListenerLocked { it.onConnected() }
                            }
                        }

                        PublisherConnectionState.DISCONNECTED,
                        PublisherConnectionState.FAILED,
                        PublisherConnectionState.CLOSED,
                        -> reportTerminalConnectionLocked(state)
                    }
                }
            }

            override fun onStats(stats: TransportStats) {
                withState {
                    if (
                        generation != callbackGeneration ||
                        phase == Phase.IDLE ||
                        terminalConnectionReported
                    ) {
                        return
                    }
                    val pcm = audioAdapter?.metrics()
                    val snapshot = stats.copy(
                        pcmUnderrunBytes = pcm?.silenceBytes ?: 0,
                        pcmDroppedBytes = pcm?.droppedBytes ?: 0,
                    )
                    notifyListenerLocked { it.onStats(snapshot) }
                }
            }

            override fun onFailure(message: String, cause: Throwable?) {
                withState {
                    if (generation != callbackGeneration || phase == Phase.IDLE) return
                    notifyNegotiationFailureLocked(message, cause)
                }
            }
        }

    private fun handleOfferResult(callbackGeneration: Long, result: Result<String>) {
        withState {
            if (generation != callbackGeneration || phase != Phase.CREATING_OFFER) return
            result.fold(
                onSuccess = { sdp ->
                    if (sdp.isBlank()) {
                        phase = Phase.PREPARED
                        pendingLocalCandidates.clear()
                        notifyNegotiationFailureLocked("Created local offer SDP was blank")
                    } else {
                        phase = Phase.LOCAL_OFFER_SET
                        notifyListenerLocked { it.onLocalOffer(sdp) }
                        val candidates = pendingLocalCandidates.toList()
                        pendingLocalCandidates.clear()
                        for (candidate in candidates) {
                            if (generation != callbackGeneration || phase == Phase.IDLE) break
                            notifyListenerLocked { it.onLocalIceCandidate(candidate) }
                        }
                    }
                },
                onFailure = { error ->
                    phase = Phase.PREPARED
                    pendingLocalCandidates.clear()
                    notifyNegotiationFailureLocked("Failed to create or set the local offer", error)
                },
            )
        }
    }

    private fun handleAnswerResult(callbackGeneration: Long, result: Result<Unit>) {
        val drainTarget = withState {
            if (generation != callbackGeneration || phase != Phase.SETTING_ANSWER) {
                return
            }
            result.fold(
                onSuccess = {
                    phase = Phase.REMOTE_ANSWER_SET
                    val activeSession = session
                    if (
                        activeSession != null &&
                        pendingRemoteCandidates.isNotEmpty() &&
                        !remoteIceDrainActive
                    ) {
                        remoteIceDrainActive = true
                        CandidateTarget(callbackGeneration, activeSession)
                    } else {
                        null
                    }
                },
                onFailure = { error ->
                    phase = Phase.LOCAL_OFFER_SET
                    notifyNegotiationFailureLocked("Failed to set the remote answer", error)
                    null
                },
            )
        } ?: return
        drainRemoteCandidates(drainTarget)
    }

    private fun enqueueRemoteCandidateLocked(candidate: IceCandidatePayload) {
        if (pendingRemoteCandidates.size >= MAX_PENDING_CANDIDATES) {
            notifyNegotiationFailureLocked("Too many remote ICE candidates")
        } else {
            pendingRemoteCandidates += candidate
        }
    }

    private fun drainRemoteCandidates(target: CandidateTarget) {
        while (true) {
            val candidate = withState {
                if (!isCurrentTargetLocked(target)) return
                if (pendingRemoteCandidates.isEmpty()) {
                    remoteIceDrainActive = false
                    return
                }
                pendingRemoteCandidates.removeAt(0)
            }
            addCandidate(target, candidate)
        }
    }

    private fun addCandidate(target: CandidateTarget, candidate: IceCandidatePayload) {
        val active = withState {
            if (!isCurrentTargetLocked(target)) false else {
                beginOperationLocked()
                true
            }
        }
        if (!active) return
        val accepted = try {
            target.session.addRemoteIceCandidate(candidate)
        } catch (error: Throwable) {
            withState {
                if (isCurrentTargetLocked(target)) {
                    notifyNegotiationFailureLocked("Failed to add a remote ICE candidate", error)
                }
            }
            return
        } finally {
            finishOperation(target.generation)
        }
        if (!accepted) {
            withState {
                if (isCurrentTargetLocked(target)) {
                    notifyNegotiationFailureLocked("Failed to add a remote ICE candidate")
                }
            }
        }
    }

    private fun reportTerminalConnectionLocked(state: PublisherConnectionState) {
        if (terminalConnectionReported) return
        terminalConnectionReported = true
        if (connected) {
            notifyListenerLocked {
                it.onDisconnected(NETWORK_DISCONNECTED + ": WebRTC connection entered " + state.name.lowercase())
            }
        } else {
            notifyNegotiationFailureLocked(
                "WebRTC connection entered " + state.name.lowercase(),
            )
        }
    }

    private fun notifyNegotiationFailureLocked(message: String, cause: Throwable? = null) {
        notifyListenerLocked { it.onFailure(WEBRTC_NEGOTIATION_FAILED + ": " + message, cause) }
    }

    private fun failPrepare(
        callbackGeneration: Long,
        adapter: WebRtcAudioAdapter,
        message: String,
        error: Throwable,
    ) {
        withState {
            if (generation != callbackGeneration) return@withState
            val failedListener = listener
            generation += 1
            if (failedListener != null) {
                notifications += Notification(generation, failedListener) {
                    it.onFailure(WEBRTC_NEGOTIATION_FAILED + ": " + message, error)
                }
            }
            phase = Phase.IDLE
            options = null
            listener = null
            session = null
            audioAdapter = null
            videoAdapter = null
            pendingLocalCandidates.clear()
            pendingRemoteCandidates.clear()
            remoteIceDrainActive = false
        }
        adapter.close()
    }

    private fun isCurrentTargetLocked(target: CandidateTarget): Boolean {
        return generation == target.generation &&
            phase != Phase.IDLE &&
            session === target.session
    }

    // Only state and snapshots are touched under this monitor. Cross-component callbacks and
    // native operations run outside it, so signaling and WebRTC cannot acquire each other's locks.
    private inline fun <T> withState(block: () -> T): T {
        try {
            return synchronized(lock, block)
        } finally {
            drainNotifications()
        }
    }

    private fun notifyListenerLocked(deliver: (MediaPublisher.Listener) -> Unit) {
        val currentListener = listener ?: return
        notifications += Notification(generation, currentListener, deliver)
    }

    private fun drainNotifications() {
        if (Thread.holdsLock(lock)) return
        synchronized(lock) {
            if (notificationDrainActive) return
            notificationDrainActive = true
        }
        while (true) {
            val next = synchronized(lock) {
                while (notifications.isNotEmpty() && notifications.first().generation != generation) {
                    notifications.removeFirst()
                }
                if (notifications.isEmpty()) {
                    notificationDrainActive = false
                    return
                }
                notifications.removeFirst()
            }
            try {
                next.deliver(next.listener)
            } catch (error: Throwable) {
                synchronized(lock) { notificationDrainActive = false }
                throw error
            }
        }
    }

    private companion object {
        const val WEBRTC_NEGOTIATION_FAILED = "WEBRTC_NEGOTIATION_FAILED"
        const val NETWORK_DISCONNECTED = "NETWORK_DISCONNECTED"
        const val MAX_PENDING_CANDIDATES = 256
        const val EXTERNAL_PCM_BYTES_PER_SECOND = 32_000
    }
}
