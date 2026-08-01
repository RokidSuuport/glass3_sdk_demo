package com.rokid.glass.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SpeechControlNavigationStateTest {
    @Test
    fun privateForwardCrossesFromInitToAsr() {
        val state = SpeechControlNavigationState(listOf(2, 4, 4))
        state.select(groupIndex = 0, itemIndex = 1)

        assertEquals(SpeechControlPosition(1, 0, 2), state.moveForward())
    }

    @Test
    fun privateBackwardCrossesFromAsrToInit() {
        val state = SpeechControlNavigationState(listOf(2, 4, 4))
        state.select(groupIndex = 1, itemIndex = 0)

        assertEquals(SpeechControlPosition(0, 1, 1), state.moveBackward())
    }

    @Test
    fun privateNavigationWrapsAtBothEnds() {
        val state = SpeechControlNavigationState(listOf(2, 4, 4))

        assertEquals(SpeechControlPosition(2, 3, 9), state.moveBackward())
        assertEquals(SpeechControlPosition(0, 0, 0), state.moveForward())
    }

    @Test
    fun publicNavigationCyclesFourItems() {
        val state = SpeechControlNavigationState(listOf(4))

        repeat(4) { state.moveForward() }

        assertEquals(SpeechControlPosition(0, 0, 0), state.current)
    }

    @Test
    fun selectRejectsIndexesOutsideTheirGroup() {
        val state = SpeechControlNavigationState(listOf(2, 4, 4))

        assertThrows(IllegalArgumentException::class.java) {
            state.select(groupIndex = 0, itemIndex = 2)
        }
    }
}
