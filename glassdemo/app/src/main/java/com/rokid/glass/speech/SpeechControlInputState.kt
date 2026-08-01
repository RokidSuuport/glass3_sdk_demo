package com.rokid.glass.speech

/**
 * Prevents one touch-pad tap from executing a focused control twice.
 *
 * [BaseActivity] first lets the Android button handle the touch, then emits a
 * delayed glass click for the same gesture. The delayed event must be consumed
 * once, while a standalone hardware glass click must still execute normally.
 */
class SpeechControlInputState {
    private var buttonHandledCurrentTouch = false

    fun onButtonClick() {
        buttonHandledCurrentTouch = true
    }

    fun onNavigation() {
        buttonHandledCurrentTouch = false
    }

    fun acceptGlassClick(): Boolean {
        if (!buttonHandledCurrentTouch) return true
        buttonHandledCurrentTouch = false
        return false
    }
}
