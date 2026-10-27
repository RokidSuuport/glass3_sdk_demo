package com.rokid.glass.mediastream.capture

enum class CaptureState {
    IDLE,
    PREPARING,
    CAPTURING,
    STOPPING,
    ERROR,
    RELEASED,
}

data class CaptureStatus @JvmOverloads constructor(
    val state: CaptureState,
    val videoMetrics: VideoCaptureMetrics = VideoCaptureMetrics(),
    val audioMetrics: AudioCaptureMetrics = AudioCaptureMetrics(),
    val failure: MediaFailure? = null,
)

fun interface CaptureStatusListener {
    fun onStatusChanged(status: CaptureStatus)
}
