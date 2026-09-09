package com.rokid.glass.mediastream.streaming

/** High-level lifecycle visible to applications embedding Glass3 media streaming. */
enum class StreamingState {
    IDLE,
    PREPARING,
    WAITING_RECEIVER,
    NEGOTIATING,
    STREAMING,
    STOPPING,
    ERROR,
    RELEASED,
}
