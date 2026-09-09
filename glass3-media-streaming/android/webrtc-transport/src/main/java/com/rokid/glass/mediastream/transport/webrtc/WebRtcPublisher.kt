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

data class PublishOptions(
    val videoEnabled: Boolean,
    val audioEnabled: Boolean,
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

    private val lock = Any()
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
        val adapter = WebRtcAudioAdapter(ringBufferFactory())
        val callbackGeneration = synchronized(lock) {
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

        val setupError = runCatching {
            if (options.videoEnabled) {
                requireNotNull(created.videoFrameTarget) {
                    "Video-enabled publisher did not create a video source"
                }
            } else {
                check(created.videoFrameTarget == null) {
                    "Video-disabled publisher unexpectedly created a video source"
                }
            }
        }.exceptionOrNull()
        if (setupError != null) {
            runCatching { created.close(adapter::close) }
            failPrepare(callbackGeneration, adapter, "WebRTC media setup failed", setupError)
            return
        }

        val accepted = synchronized(lock) {
            if (generation == callbackGeneration && phase == Phase.PREPARING) {
                session = created
                videoAdapter = created.videoFrameTarget?.let(::WebRtcVideoAdapter)
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
        val current = synchronized(lock) {
            val activeSession = session
            if (phase != Phase.PREPARED || activeSession == null) {
                notifyNegotiationFailureLocked("Cannot create a duplicate or out-of-order offer")
                null
            } else {
                phase = Phase.CREATING_OFFER
                generation to activeSession
            }
        } ?: return

        try {
            current.second.createOffer { result ->
                handleOfferResult(current.first, result)
            }
        } catch (error: Throwable) {
            handleOfferResult(current.first, Result.failure(error))
        }
    }

    override fun setRemoteAnswer(sdp: String) {
        val current = synchronized(lock) {
            if (sdp.isBlank()) {
                notifyNegotiationFailureLocked("Remote answer SDP must not be blank")
                return@synchronized null
            }
            val activeSession = session
            if (phase != Phase.LOCAL_OFFER_SET || activeSession == null) {
                notifyNegotiationFailureLocked("Cannot set a duplicate or out-of-order remote answer")
                null
            } else {
                phase = Phase.SETTING_ANSWER
                generation to activeSession
            }
        } ?: return

        try {
            current.second.setRemoteAnswer(sdp) { result ->
                handleAnswerResult(current.first, result)
            }
        } catch (error: Throwable) {
            handleAnswerResult(current.first, Result.failure(error))
        }
    }

    override fun addRemoteIceCandidate(candidate: IceCandidatePayload) {
        val drainTarget = synchronized(lock) {
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
        synchronized(lock) {
            if (options?.videoEnabled != true) return
            val adapter = videoAdapter ?: return
            try {
                adapter.onVideoFrame(frame)
            } catch (error: Throwable) {
                notifyNegotiationFailureLocked("Failed to inject an external NV21 frame", error)
            }
        }
    }

    override fun onAudioFrame(frame: PcmFrame) {
        synchronized(lock) {
            if (options?.audioEnabled != true) return
            val adapter = audioAdapter ?: return
            try {
                adapter.onAudioFrame(frame)
            } catch (error: Throwable) {
                notifyNegotiationFailureLocked("Failed to inject an external PCM frame", error)
            }
        }
    }

    override fun close() {
        val snapshot = synchronized(lock) {
            if (phase == Phase.IDLE && session == null && audioAdapter == null) return
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
            closing
        }

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
                synchronized(lock) {
                    if (generation != callbackGeneration || phase == Phase.IDLE) return
                    if (phase.ordinal >= Phase.LOCAL_OFFER_SET.ordinal) {
                        listener?.onLocalIceCandidate(candidate)
                    } else if (pendingLocalCandidates.size < MAX_PENDING_CANDIDATES) {
                        pendingLocalCandidates += candidate
                    } else {
                        notifyNegotiationFailureLocked("Too many local ICE candidates")
                    }
                }
            }

            override fun onConnectionStateChanged(state: PublisherConnectionState) {
                synchronized(lock) {
                    if (generation != callbackGeneration || phase == Phase.IDLE) return
                    when (state) {
                        PublisherConnectionState.CONNECTED -> {
                            if (!connected && !terminalConnectionReported) {
                                connected = true
                                phase = Phase.CONNECTED
                                listener?.onConnected()
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
                synchronized(lock) {
                    if (
                        generation != callbackGeneration ||
                        phase == Phase.IDLE ||
                        terminalConnectionReported
                    ) {
                        return
                    }
                    val pcm = audioAdapter?.metrics()
                    listener?.onStats(
                        stats.copy(
                            pcmUnderrunBytes = pcm?.silenceBytes ?: 0,
                            pcmDroppedBytes = pcm?.droppedBytes ?: 0,
                        ),
                    )
                }
            }

            override fun onFailure(message: String, cause: Throwable?) {
                synchronized(lock) {
                    if (generation != callbackGeneration || phase == Phase.IDLE) return
                    notifyNegotiationFailureLocked(message, cause)
                }
            }
        }

    private fun handleOfferResult(callbackGeneration: Long, result: Result<String>) {
        synchronized(lock) {
            if (generation != callbackGeneration || phase != Phase.CREATING_OFFER) return
            result.fold(
                onSuccess = { sdp ->
                    if (sdp.isBlank()) {
                        phase = Phase.PREPARED
                        pendingLocalCandidates.clear()
                        notifyNegotiationFailureLocked("Created local offer SDP was blank")
                    } else {
                        phase = Phase.LOCAL_OFFER_SET
                        listener?.onLocalOffer(sdp)
                        val candidates = pendingLocalCandidates.toList()
                        pendingLocalCandidates.clear()
                        for (candidate in candidates) {
                            if (generation != callbackGeneration || phase == Phase.IDLE) break
                            listener?.onLocalIceCandidate(candidate)
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
        val drainTarget = synchronized(lock) {
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
            val candidate = synchronized(lock) {
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
        if (!isCurrentTarget(target)) return
        val accepted = try {
            target.session.addRemoteIceCandidate(candidate)
        } catch (error: Throwable) {
            synchronized(lock) {
                if (isCurrentTargetLocked(target)) {
                    notifyNegotiationFailureLocked("Failed to add a remote ICE candidate", error)
                }
            }
            return
        }
        if (!accepted) {
            synchronized(lock) {
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
            listener?.onDisconnected(
                NETWORK_DISCONNECTED + ": WebRTC connection entered " + state.name.lowercase(),
            )
        } else {
            notifyNegotiationFailureLocked(
                "WebRTC connection entered " + state.name.lowercase(),
            )
        }
    }

    private fun notifyNegotiationFailureLocked(message: String, cause: Throwable? = null) {
        listener?.onFailure(WEBRTC_NEGOTIATION_FAILED + ": " + message, cause)
    }

    private fun failPrepare(
        callbackGeneration: Long,
        adapter: WebRtcAudioAdapter,
        message: String,
        error: Throwable,
    ) {
        synchronized(lock) {
            if (generation != callbackGeneration) return@synchronized
            notifyNegotiationFailureLocked(message, error)
            generation += 1
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

    private fun isCurrentTarget(target: CandidateTarget): Boolean = synchronized(lock) {
        isCurrentTargetLocked(target)
    }

    private fun isCurrentTargetLocked(target: CandidateTarget): Boolean {
        return generation == target.generation &&
            phase != Phase.IDLE &&
            session === target.session
    }

    private companion object {
        const val WEBRTC_NEGOTIATION_FAILED = "WEBRTC_NEGOTIATION_FAILED"
        const val NETWORK_DISCONNECTED = "NETWORK_DISCONNECTED"
        const val MAX_PENDING_CANDIDATES = 256
        const val EXTERNAL_PCM_BYTES_PER_SECOND = 32_000
    }
}
