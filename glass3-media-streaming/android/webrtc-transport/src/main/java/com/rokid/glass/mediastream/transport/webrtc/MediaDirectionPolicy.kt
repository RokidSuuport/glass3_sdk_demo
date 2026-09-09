package com.rokid.glass.mediastream.transport.webrtc

import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.RtpTransceiver.RtpTransceiverDirection

internal enum class LocalMediaKind {
    AUDIO,
    VIDEO,
}

internal object MediaDirectionPolicy {
    val lanIceServers: List<PeerConnection.IceServer> = emptyList()

    fun directionFor(kind: LocalMediaKind): RtpTransceiverDirection = when (kind) {
        LocalMediaKind.AUDIO,
        LocalMediaKind.VIDEO,
        -> RtpTransceiverDirection.SEND_ONLY
    }

    fun rejectRemoteTrack(kind: LocalMediaKind): Nothing =
        throw IllegalStateException(
            "Sender endpoint does not accept remote " + kind.name.lowercase() + " tracks",
        )
}
