package com.rokid.glass.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechChooserStateTest {
    @Test
    fun selectingPrivateServiceUpdatesCurrentIndex() {
        val state = SpeechChooserState(lastIndex = 1)

        state.select(1)

        assertEquals(1, state.selectedIndex)
    }

    @Test
    fun delayedGlassClickIsRejectedAfterChooserLeavesForeground() {
        val state = SpeechChooserState(lastIndex = 1)
        state.setForeground(true)
        state.setForeground(false)

        assertFalse(state.acceptGlassClick())
    }

    @Test
    fun glassClickIsAcceptedWhileChooserIsForeground() {
        val state = SpeechChooserState(lastIndex = 1)
        state.setForeground(true)

        assertTrue(state.acceptGlassClick())
    }
}
