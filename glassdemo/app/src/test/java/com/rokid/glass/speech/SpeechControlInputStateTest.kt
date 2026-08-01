package com.rokid.glass.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechControlInputStateTest {
    @Test
    fun delayedGlassClickIsIgnoredAfterButtonHandledSameTouch() {
        val state = SpeechControlInputState()

        state.onButtonClick()

        assertFalse(state.acceptGlassClick())
    }

    @Test
    fun onlyOneDelayedGlassClickIsIgnored() {
        val state = SpeechControlInputState()
        state.onButtonClick()

        state.acceptGlassClick()

        assertTrue(state.acceptGlassClick())
    }

    @Test
    fun standaloneGlassClickIsAccepted() {
        val state = SpeechControlInputState()

        assertTrue(state.acceptGlassClick())
    }

    @Test
    fun navigationCancelsPendingSuppression() {
        val state = SpeechControlInputState()
        state.onButtonClick()

        state.onNavigation()

        assertTrue(state.acceptGlassClick())
    }
}
