package com.rokid.glass.speech

class SpeechChooserState(private val lastIndex: Int) {
    var selectedIndex: Int = 0
        private set

    private var isForeground = false

    fun select(index: Int) {
        selectedIndex = index.coerceIn(0, lastIndex)
    }

    fun setForeground(foreground: Boolean) {
        isForeground = foreground
    }

    fun acceptGlassClick(): Boolean = isForeground
}
