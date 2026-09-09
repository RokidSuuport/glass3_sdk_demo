package com.rokid.glass.mediastream.streaming

import com.rokid.glass.mediastream.capture.MediaFailure

/** Customer-selected signaling endpoint and media combination. */
data class StreamingOptions @JvmOverloads constructor(
    val serverUrl: String,
    val videoEnabled: Boolean = true,
    val audioEnabled: Boolean = true,
    val roomId: String = "default",
)

/** A stable, UI-friendly snapshot of the complete streaming operation. */
data class StreamingStatus @JvmOverloads constructor(
    val state: StreamingState,
    val stats: StreamingStats = StreamingStats(),
    val retryAttempt: Int = 0,
    val failure: MediaFailure? = null,
)

/** Capture dimensions plus outbound WebRTC transport statistics. */
data class StreamingStats @JvmOverloads constructor(
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoFps: Double = 0.0,
    val videoBitrateBps: Long = 0L,
    val audioBitrateBps: Long = 0L,
    val packetsLost: Long = 0L,
    val roundTripTimeMs: Long = 0L,
    val pcmUnderrunBytes: Long = 0L,
    val pcmDroppedBytes: Long = 0L,
)

fun interface StreamingStatusListener {
    fun onStatusChanged(status: StreamingStatus)
}
