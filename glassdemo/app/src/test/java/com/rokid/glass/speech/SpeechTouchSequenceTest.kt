package com.rokid.glass.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTouchSequenceTest {
    @Test
    fun smallMovementIsStillRecognizedAsTap() {
        val sequence = SpeechTouchSequence(touchSlop = 12f)
        sequence.onDown(100f, 100f)

        assertTrue(sequence.isTapOnUp(107f, 105f))
    }

    @Test
    fun swipeIsNotRecognizedAsTap() {
        val sequence = SpeechTouchSequence(touchSlop = 12f)
        sequence.onDown(100f, 100f)

        assertFalse(sequence.isTapOnUp(140f, 100f))
    }

    @Test
    fun upWithoutDownIsNotATap() {
        val sequence = SpeechTouchSequence(touchSlop = 12f)

        assertFalse(sequence.isTapOnUp(100f, 100f))
    }
}
