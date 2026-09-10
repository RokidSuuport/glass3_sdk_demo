package com.rokid.glass.mediastream.transport.webrtc

import android.content.Context
import android.os.SystemClock
import com.rokid.glass.mediastream.transport.signaling.IceCandidatePayload
import com.rokid.glass.mediastream.transport.webrtc.audio.AudioDeviceModuleConfig
import com.rokid.glass.mediastream.transport.webrtc.audio.WebRtcAudioAdapter
import com.rokid.glass.mediastream.transport.webrtc.audio.WebRtcAudioDeviceModuleFactory
import com.rokid.glass.mediastream.transport.webrtc.video.VideoFrameTarget
import java.util.concurrent.atomic.AtomicBoolean
import livekit.org.webrtc.AudioSource
import livekit.org.webrtc.AudioTrack
import livekit.org.webrtc.DataChannel
import livekit.org.webrtc.DefaultVideoDecoderFactory
import livekit.org.webrtc.DefaultVideoEncoderFactory
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.MediaStream
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.RtpTransceiver
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription
import livekit.org.webrtc.VideoSource
import livekit.org.webrtc.VideoTrack
import livekit.org.webrtc.audio.JavaAudioDeviceModule

internal class NativePublisherSessionFactory(
    context: Context,
) : PublisherSessionFactory {
    private val appContext = context.applicationContext

    override fun create(
        options: PublishOptions,
        audioAdapter: WebRtcAudioAdapter,
        audioConfig: AudioDeviceModuleConfig,
        events: PublisherSessionEvents,
    ): PublisherSession = NativePublisherSession.create(
        context = appContext,
        options = options,
        audioAdapter = audioAdapter,
        audioConfig = audioConfig,
        events = events,
    )
}

private class NativePublisherSession private constructor(
    private val peerConnection: PeerConnection,
    private val videoSource: VideoSource?,
    private val resourceReleaser: NativePublisherResourceReleaser,
) : PublisherSession {
    private val closed = AtomicBoolean(false)

    override val videoFrameTarget: VideoFrameTarget? = videoSource?.let { source ->
        VideoFrameTarget(source.capturerObserver::onFrameCaptured)
    }

    override fun createOffer(onResult: (Result<String>) -> Unit) {
        if (closed.get()) {
            onResult(Result.failure(IllegalStateException("Publisher session is closed")))
            return
        }
        peerConnection.createOffer(
            object : BaseSdpObserver() {
                override fun onCreateSuccess(description: SessionDescription) {
                    if (closed.get()) return
                    peerConnection.setLocalDescription(
                        object : BaseSdpObserver() {
                            override fun onSetSuccess() {
                                if (!closed.get()) {
                                    onResult(Result.success(description.description))
                                }
                            }

                            override fun onSetFailure(error: String) {
                                onResult(Result.failure(SdpOperationException("Set local offer failed: " + error)))
                            }
                        },
                        description,
                    )
                }

                override fun onCreateFailure(error: String) {
                    onResult(Result.failure(SdpOperationException("Create offer failed: " + error)))
                }
            },
            MediaConstraints(),
        )
    }

    override fun setRemoteAnswer(sdp: String, onResult: (Result<Unit>) -> Unit) {
        if (closed.get()) {
            onResult(Result.failure(IllegalStateException("Publisher session is closed")))
            return
        }
        peerConnection.setRemoteDescription(
            object : BaseSdpObserver() {
                override fun onSetSuccess() {
                    if (!closed.get()) onResult(Result.success(Unit))
                }

                override fun onSetFailure(error: String) {
                    onResult(Result.failure(SdpOperationException("Set remote answer failed: " + error)))
                }
            },
            SessionDescription(SessionDescription.Type.ANSWER, sdp),
        )
    }

    override fun addRemoteIceCandidate(candidate: IceCandidatePayload): Boolean {
        if (closed.get()) return false
        return peerConnection.addIceCandidate(
            IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate),
        )
    }

    override fun close(releaseAudioAdapter: () -> Unit) {
        if (!closed.compareAndSet(false, true)) return
        resourceReleaser.release(releaseAudioAdapter)
    }

    private open inner class BaseSdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }

    companion object {
        fun create(
            context: Context,
            options: PublishOptions,
            audioAdapter: WebRtcAudioAdapter,
            audioConfig: AudioDeviceModuleConfig,
            events: PublisherSessionEvents,
        ): NativePublisherSession {
            WebRtcRuntimeInitializer.ensureInitialized(context)

            val eventGate = NativeEventGate(events)
            var eglBase: EglBase? = null
            var audioDeviceModule: JavaAudioDeviceModule? = null
            var factory: PeerConnectionFactory? = null
            var peer: PeerConnection? = null
            var videoSource: VideoSource? = null
            var videoSourceLifecycle: NativeVideoSourceLifecycle? = null
            var videoTrack: VideoTrack? = null
            var audioSource: AudioSource? = null
            var audioTrack: AudioTrack? = null
            var statsCollector: StatsCollector? = null

            try {
                val createdAudioDeviceModule = WebRtcAudioDeviceModuleFactory.create(
                    context = context,
                    audioAdapter = audioAdapter,
                    config = audioConfig,
                )
                audioDeviceModule = createdAudioDeviceModule
                val createdEglBase = if (options.videoEnabled) EglBase.create() else null
                eglBase = createdEglBase

                val builder = PeerConnectionFactory.builder()
                    .setAudioDeviceModule(createdAudioDeviceModule)
                if (options.videoEnabled) {
                    val eglContext = requireNotNull(createdEglBase).eglBaseContext
                    builder
                        .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
                        .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
                }
                val createdFactory = builder.createPeerConnectionFactory()
                factory = createdFactory

                val configuration = PeerConnection.RTCConfiguration(MediaDirectionPolicy.lanIceServers).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                }
                val createdPeer = requireNotNull(
                    createdFactory.createPeerConnection(configuration, createPeerObserver(eventGate)),
                ) { "WebRTC failed to create PeerConnection" }
                peer = createdPeer
                createdPeer.setAudioPlayout(false)
                createdPeer.setAudioRecording(options.audioEnabled)

                if (options.videoEnabled) {
                    val createdVideoSource = createdFactory.createVideoSource(false)
                    videoSource = createdVideoSource
                    val capturerObserver = createdVideoSource.capturerObserver
                    val createdVideoSourceLifecycle = NativeVideoSourceLifecycle(
                        onStarted = capturerObserver::onCapturerStarted,
                        onStopped = capturerObserver::onCapturerStopped,
                    )
                    videoSourceLifecycle = createdVideoSourceLifecycle
                    val createdVideoTrack = createdFactory.createVideoTrack(
                        VIDEO_TRACK_ID,
                        createdVideoSource,
                    ).apply {
                        setEnabled(true)
                    }
                    videoTrack = createdVideoTrack
                    val transceiver = createdPeer.addTransceiver(
                        createdVideoTrack,
                        RtpTransceiver.RtpTransceiverInit(
                            MediaDirectionPolicy.directionFor(LocalMediaKind.VIDEO),
                            listOf(STREAM_ID),
                        ),
                    )
                    check(transceiver.direction == RtpTransceiver.RtpTransceiverDirection.SEND_ONLY) {
                        "Video transceiver is not send-only"
                    }
                    createdVideoSourceLifecycle.start()
                }

                if (options.audioEnabled) {
                    val constraints = audioConstraints()
                    val createdAudioSource = createdFactory.createAudioSource(constraints)
                    audioSource = createdAudioSource
                    val createdAudioTrack = createdFactory.createAudioTrack(
                        AUDIO_TRACK_ID,
                        createdAudioSource,
                    ).apply {
                        setEnabled(true)
                    }
                    audioTrack = createdAudioTrack
                    val transceiver = createdPeer.addTransceiver(
                        createdAudioTrack,
                        RtpTransceiver.RtpTransceiverInit(
                            MediaDirectionPolicy.directionFor(LocalMediaKind.AUDIO),
                            listOf(STREAM_ID),
                        ),
                    )
                    check(transceiver.direction == RtpTransceiver.RtpTransceiverDirection.SEND_ONLY) {
                        "Audio transceiver is not send-only"
                    }
                }

                val createdStatsCollector = StatsCollector(
                    onStats = eventGate::onStats,
                    elapsedRealtimeMs = SystemClock::elapsedRealtime,
                ).also { it.start(createdPeer) }
                statsCollector = createdStatsCollector

                val resourceReleaser = createResourceReleaser(
                    eventGate = eventGate,
                    statsCollector = createdStatsCollector,
                    peerConnection = createdPeer,
                    videoTrack = videoTrack,
                    audioTrack = audioTrack,
                    videoSource = videoSource,
                    audioSource = audioSource,
                    videoSourceLifecycle = videoSourceLifecycle,
                    audioDeviceModule = createdAudioDeviceModule,
                    peerConnectionFactory = createdFactory,
                    eglBase = createdEglBase,
                )
                return NativePublisherSession(
                    peerConnection = createdPeer,
                    videoSource = videoSource,
                    resourceReleaser = resourceReleaser,
                )
            } catch (error: Throwable) {
                createResourceReleaser(
                    eventGate = eventGate,
                    statsCollector = statsCollector,
                    peerConnection = peer,
                    videoTrack = videoTrack,
                    audioTrack = audioTrack,
                    videoSource = videoSource,
                    audioSource = audioSource,
                    videoSourceLifecycle = videoSourceLifecycle,
                    audioDeviceModule = audioDeviceModule,
                    peerConnectionFactory = factory,
                    eglBase = eglBase,
                ).release()
                throw error
            }
        }

        private fun createResourceReleaser(
            eventGate: NativeEventGate,
            statsCollector: StatsCollector?,
            peerConnection: PeerConnection?,
            videoTrack: VideoTrack?,
            audioTrack: AudioTrack?,
            videoSource: VideoSource?,
            audioSource: AudioSource?,
            videoSourceLifecycle: NativeVideoSourceLifecycle?,
            audioDeviceModule: JavaAudioDeviceModule?,
            peerConnectionFactory: PeerConnectionFactory?,
            eglBase: EglBase?,
        ): NativePublisherResourceReleaser = NativePublisherResourceReleaser(
            NativePublisherReleaseActions(
                closeEventGate = eventGate::close,
                stopStats = { statsCollector?.stop() },
                disableAudioRecording = { peerConnection?.setAudioRecording(false) },
                disableAudioPlayout = { peerConnection?.setAudioPlayout(false) },
                stopVideoSource = { videoSourceLifecycle?.stop() },
                // PeerConnection.dispose() already invokes close() in the bundled WebRTC SDK.
                disposePeer = { peerConnection?.dispose() },
                disposeVideoTrack = {
                    videoTrack?.setEnabled(false)
                    videoTrack?.dispose()
                },
                disposeAudioTrack = {
                    audioTrack?.setEnabled(false)
                    audioTrack?.dispose()
                },
                disposeVideoSource = { videoSource?.dispose() },
                disposeAudioSource = { audioSource?.dispose() },
                releaseAudioDeviceModule = { audioDeviceModule?.release() },
                disposePeerFactory = { peerConnectionFactory?.dispose() },
                releaseEgl = { eglBase?.release() },
            ),
        )

        private fun audioConstraints(): MediaConstraints = MediaConstraints().apply {
            mandatory += MediaConstraints.KeyValuePair("googEchoCancellation", "true")
            mandatory += MediaConstraints.KeyValuePair("googNoiseSuppression", "true")
            mandatory += MediaConstraints.KeyValuePair("googAutoGainControl", "true")
            mandatory += MediaConstraints.KeyValuePair("googHighpassFilter", "true")
        }

        private fun createPeerObserver(eventGate: NativeEventGate): PeerConnection.Observer =
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    publisherStateForIceConnection(state)?.let(eventGate::onConnectionStateChanged)
                }

                override fun onIceCandidate(candidate: IceCandidate) {
                    eventGate.onLocalIceCandidate(
                        IceCandidatePayload(
                            sdpMid = candidate.sdpMid,
                            sdpMLineIndex = candidate.sdpMLineIndex,
                            candidate = candidate.sdp,
                        ),
                    )
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

                override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                    val publisherState = when (state) {
                        PeerConnection.PeerConnectionState.CONNECTED -> PublisherConnectionState.CONNECTED
                        PeerConnection.PeerConnectionState.DISCONNECTED -> PublisherConnectionState.DISCONNECTED
                        PeerConnection.PeerConnectionState.FAILED -> PublisherConnectionState.FAILED
                        PeerConnection.PeerConnectionState.CLOSED -> PublisherConnectionState.CLOSED
                        else -> null
                    }
                    if (publisherState != null) eventGate.onConnectionStateChanged(publisherState)
                }

                override fun onAddStream(stream: MediaStream) {
                    eventGate.onFailure("Sender endpoint received an unexpected remote media stream")
                }

                override fun onRemoveStream(stream: MediaStream) = Unit

                override fun onDataChannel(dataChannel: DataChannel) {
                    dataChannel.close()
                    dataChannel.dispose()
                    eventGate.onFailure("Sender endpoint does not accept remote data channels")
                }

                override fun onRenegotiationNeeded() = Unit

                override fun onTrack(transceiver: RtpTransceiver) {
                    val kind = when (transceiver.mediaType) {
                        livekit.org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO ->
                            LocalMediaKind.AUDIO

                        else -> LocalMediaKind.VIDEO
                    }
                    runCatching { MediaDirectionPolicy.rejectRemoteTrack(kind) }
                        .onFailure { eventGate.onFailure(it.message ?: "Unexpected remote track", it) }
                }

                override fun onAddTrack(
                    receiver: RtpReceiver,
                    mediaStreams: Array<out MediaStream>,
                ) {
                    eventGate.onFailure("Sender endpoint received an unexpected remote media track")
                }
            }

        private const val STREAM_ID = "glass-stream"
        private const val VIDEO_TRACK_ID = "glass-video"
        private const val AUDIO_TRACK_ID = "glass-audio"
    }
}

internal fun publisherStateForIceConnection(
    state: PeerConnection.IceConnectionState,
): PublisherConnectionState? = when (state) {
    PeerConnection.IceConnectionState.CONNECTED,
    PeerConnection.IceConnectionState.COMPLETED,
    -> PublisherConnectionState.CONNECTED

    PeerConnection.IceConnectionState.DISCONNECTED -> PublisherConnectionState.DISCONNECTED
    PeerConnection.IceConnectionState.FAILED -> PublisherConnectionState.FAILED
    PeerConnection.IceConnectionState.CLOSED -> PublisherConnectionState.CLOSED
    else -> null
}

internal class NativeVideoSourceLifecycle(
    private val onStarted: (Boolean) -> Unit,
    private val onStopped: () -> Unit,
) {
    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    fun start() {
        if (started.compareAndSet(false, true)) {
            onStarted(true)
        }
    }

    fun stop() {
        if (started.get() && stopped.compareAndSet(false, true)) {
            onStopped()
        }
    }
}

internal data class NativePublisherReleaseActions(
    val closeEventGate: () -> Unit = {},
    val stopStats: () -> Unit = {},
    val disableAudioRecording: () -> Unit = {},
    val disableAudioPlayout: () -> Unit = {},
    val stopVideoSource: () -> Unit = {},
    val disposePeer: () -> Unit = {},
    val disposeVideoTrack: () -> Unit = {},
    val disposeAudioTrack: () -> Unit = {},
    val disposeVideoSource: () -> Unit = {},
    val disposeAudioSource: () -> Unit = {},
    val releaseAudioDeviceModule: () -> Unit = {},
    val disposePeerFactory: () -> Unit = {},
    val releaseEgl: () -> Unit = {},
)

internal class NativePublisherResourceReleaser(
    private val actions: NativePublisherReleaseActions,
) {
    private val released = AtomicBoolean(false)

    fun release(releaseAudioAdapter: () -> Unit = {}) {
        if (!released.compareAndSet(false, true)) return
        runReleaseAction(actions.closeEventGate)
        runReleaseAction(actions.stopStats)
        runReleaseAction(actions.disableAudioRecording)
        runReleaseAction(actions.disableAudioPlayout)
        runReleaseAction(actions.stopVideoSource)
        runReleaseAction(actions.disposePeer)
        runReleaseAction(actions.disposeVideoTrack)
        runReleaseAction(actions.disposeAudioTrack)
        runReleaseAction(actions.disposeVideoSource)
        runReleaseAction(actions.disposeAudioSource)
        runReleaseAction(releaseAudioAdapter)
        runReleaseAction(actions.releaseAudioDeviceModule)
        runReleaseAction(actions.disposePeerFactory)
        runReleaseAction(actions.releaseEgl)
    }

    private fun runReleaseAction(action: () -> Unit) {
        runCatching(action)
    }
}

private class NativeEventGate(
    private val delegate: PublisherSessionEvents,
) : PublisherSessionEvents {
    private val closed = AtomicBoolean(false)

    fun close() {
        closed.set(true)
    }

    override fun onLocalIceCandidate(candidate: IceCandidatePayload) {
        if (!closed.get()) delegate.onLocalIceCandidate(candidate)
    }

    override fun onConnectionStateChanged(state: PublisherConnectionState) {
        if (!closed.get()) delegate.onConnectionStateChanged(state)
    }

    override fun onStats(stats: TransportStats) {
        if (!closed.get()) delegate.onStats(stats)
    }

    override fun onFailure(message: String, cause: Throwable?) {
        if (!closed.get()) delegate.onFailure(message, cause)
    }
}

private object WebRtcRuntimeInitializer {
    private val lock = Any()
    @Volatile private var initialized = false

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .createInitializationOptions(),
            )
            initialized = true
        }
    }
}

private class SdpOperationException(message: String) : IllegalStateException(message)
