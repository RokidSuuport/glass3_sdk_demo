package com.rokid.glass.speech.privateservice

enum class PrivateSpeechAction {
    INIT,
    RELEASE,
    ASR_CONNECT,
    ASR_START,
    ASR_STOP,
    ASR_CLOSE,
    TTS_CONNECT,
    TTS_SPEAK,
    TTS_STOP,
    TTS_CLOSE,
}

interface PrivateSpeechPage {
    fun requestActionFocus(itemIndex: Int)
}
