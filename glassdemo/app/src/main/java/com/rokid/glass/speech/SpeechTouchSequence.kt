package com.rokid.glass.speech

class SpeechTouchSequence(private val touchSlop: Float) {
    private var downX = 0f
    private var downY = 0f
    private var hasDown = false

    fun onDown(x: Float, y: Float) {
        downX = x
        downY = y
        hasDown = true
    }

    fun isTapOnUp(x: Float, y: Float): Boolean {
        if (!hasDown) return false
        hasDown = false
        val deltaX = x - downX
        val deltaY = y - downY
        return deltaX * deltaX + deltaY * deltaY <= touchSlop * touchSlop
    }

    fun cancel() {
        hasDown = false
    }
}
