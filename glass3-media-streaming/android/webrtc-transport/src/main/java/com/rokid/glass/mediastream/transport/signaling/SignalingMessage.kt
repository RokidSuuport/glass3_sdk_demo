package com.rokid.glass.mediastream.transport.signaling

enum class SignalingType(val wireValue: String) {
    JOIN("join"),
    PEER_READY("peer-ready"),
    OFFER("offer"),
    ANSWER("answer"),
    ICE_CANDIDATE("ice-candidate"),
    LEAVE("leave"),
    ERROR("error");

    companion object {
        fun fromWireValue(value: String): SignalingType =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Unknown signaling type: " + value)
    }
}

data class IceCandidatePayload(
    val sdpMid: String?,
    val sdpMLineIndex: Int,
    val candidate: String,
) {
    init {
        require(sdpMid == null || sdpMid.isNotBlank()) { "sdpMid must be null or non-blank" }
        require(sdpMLineIndex >= 0) { "sdpMLineIndex must be non-negative" }
        require(candidate.isNotBlank()) { "ICE candidate must not be blank" }
    }
}

data class SignalingMessage(
    val type: SignalingType,
    val roomId: String = DEFAULT_ROOM_ID,
    val role: String? = null,
    val sdp: String? = null,
    val candidate: IceCandidatePayload? = null,
    val message: String? = null,
) {
    companion object {
        const val DEFAULT_ROOM_ID = "default"
    }
}
