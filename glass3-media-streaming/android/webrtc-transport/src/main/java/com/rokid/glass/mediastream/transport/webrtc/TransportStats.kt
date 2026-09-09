package com.rokid.glass.mediastream.transport.webrtc

data class TransportStats(
    val videoBytesSent: Long = 0,
    val audioBytesSent: Long = 0,
    val videoBitrateBps: Long = 0,
    val audioBitrateBps: Long = 0,
    val framesSent: Long = 0,
    val framesEncoded: Long = 0,
    val packetsLost: Long = 0,
    val roundTripTimeMs: Long = 0,
    val pcmUnderrunBytes: Long = 0,
    val pcmDroppedBytes: Long = 0,
)

internal data class RawRtcStat(
    val type: String,
    val members: Map<String, Any>,
)
