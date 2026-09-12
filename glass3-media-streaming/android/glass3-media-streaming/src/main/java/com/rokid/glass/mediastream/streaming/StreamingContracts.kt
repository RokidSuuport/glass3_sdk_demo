package com.rokid.glass.mediastream.streaming

import com.rokid.glass.mediastream.capture.MediaFailure
import com.rokid.glass.mediastream.capture.VideoCaptureOptions

/** Customer-selected signaling endpoint and media combination. */
data class StreamingOptions @JvmOverloads constructor(
    val serverUrl: String,
    val videoEnabled: Boolean = true,
    val audioEnabled: Boolean = true,
    val roomId: String = "default",
    /** Requested camera output; inspect stats for the dimensions actually delivered by the device. */
    val videoCapture: VideoCaptureOptions = VideoCaptureOptions(),
    /** Optional encoder ceiling, not a guaranteed bitrate. Null preserves WebRTC defaults. */
    val maxVideoBitrateBps: Int? = null,
)

/** A stable, UI-friendly snapshot of the complete streaming operation. */
data class StreamingStatus @JvmOverloads constructor(
    val state: StreamingState,
    val stats: StreamingStats = StreamingStats(),
    val retryAttempt: Int = 0,
    val failure: MediaFailure? = null,
)

/**
 * videoWidth/videoHeight/videoFps retain their capture-side meaning. encodedVideo* describes
 * WebRTC's reported outbound video encoding and may be smaller/slower under load. Zero means
 * unavailable; quality limitation is "unknown" when the platform does not report a reason.
 */
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
    val encodedVideoWidth: Int = 0,
    val encodedVideoHeight: Int = 0,
    val encodedVideoFps: Double = 0.0,
    val videoQualityLimitationReason: String = "unknown",
)

fun interface StreamingStatusListener {
    fun onStatusChanged(status: StreamingStatus)
}
