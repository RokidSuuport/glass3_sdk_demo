package com.rokid.glass.mediastream.streaming.internal

import com.rokid.glass.mediastream.streaming.StreamingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StreamingStateMachineTest {
    @Test
    fun happy_path_uses_the_public_state_sequence() {
        val machine = StreamingStateMachine()

        val states = listOf(
            machine.moveTo(StreamingState.PREPARING),
            machine.moveTo(StreamingState.WAITING_RECEIVER),
            machine.moveTo(StreamingState.NEGOTIATING),
            machine.moveTo(StreamingState.STREAMING),
        )

        assertEquals(
            listOf(
                StreamingState.PREPARING,
                StreamingState.WAITING_RECEIVER,
                StreamingState.NEGOTIATING,
                StreamingState.STREAMING,
            ),
            states,
        )
    }

    @Test
    fun invalid_jump_is_rejected_without_changing_state() {
        val machine = StreamingStateMachine()

        assertThrows(IllegalStateException::class.java) {
            machine.moveTo(StreamingState.STREAMING)
        }

        assertEquals(StreamingState.IDLE, machine.current())
    }

    @Test
    fun stop_error_restart_and_release_transitions_are_explicit() {
        val machine = StreamingStateMachine()
        machine.moveTo(StreamingState.PREPARING)
        machine.moveTo(StreamingState.ERROR)
        machine.moveTo(StreamingState.PREPARING)
        machine.moveTo(StreamingState.STOPPING)
        machine.moveTo(StreamingState.IDLE)
        machine.moveTo(StreamingState.RELEASED)

        assertEquals(StreamingState.RELEASED, machine.current())
        assertThrows(IllegalStateException::class.java) {
            machine.moveTo(StreamingState.PREPARING)
        }
    }
}
