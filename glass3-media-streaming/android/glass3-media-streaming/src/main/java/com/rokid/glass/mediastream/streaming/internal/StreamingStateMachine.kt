package com.rokid.glass.mediastream.streaming.internal

import com.rokid.glass.mediastream.streaming.StreamingState

internal class StreamingStateMachine {
    private val allowedTransitions = mapOf(
        StreamingState.IDLE to setOf(StreamingState.PREPARING, StreamingState.RELEASED),
        StreamingState.PREPARING to setOf(
            StreamingState.WAITING_RECEIVER,
            StreamingState.STOPPING,
            StreamingState.ERROR,
        ),
        StreamingState.WAITING_RECEIVER to setOf(
            StreamingState.NEGOTIATING,
            StreamingState.STOPPING,
            StreamingState.ERROR,
        ),
        StreamingState.NEGOTIATING to setOf(
            StreamingState.STREAMING,
            StreamingState.WAITING_RECEIVER,
            StreamingState.STOPPING,
            StreamingState.ERROR,
        ),
        StreamingState.STREAMING to setOf(
            StreamingState.WAITING_RECEIVER,
            StreamingState.STOPPING,
            StreamingState.ERROR,
        ),
        StreamingState.STOPPING to setOf(StreamingState.IDLE, StreamingState.RELEASED),
        StreamingState.ERROR to setOf(
            StreamingState.PREPARING,
            StreamingState.STOPPING,
            StreamingState.RELEASED,
        ),
        StreamingState.RELEASED to emptySet(),
    )

    private var state = StreamingState.IDLE

    @Synchronized
    fun moveTo(next: StreamingState): StreamingState {
        if (next == state) return state
        check(next in allowedTransitions.getValue(state)) {
            "Invalid transition: $state -> $next"
        }
        state = next
        return state
    }

    @Synchronized
    fun current(): StreamingState = state
}
