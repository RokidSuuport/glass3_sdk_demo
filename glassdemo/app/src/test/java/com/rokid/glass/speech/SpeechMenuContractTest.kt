package com.rokid.glass.speech

import com.rokid.glass.annotation.MenuConfigType
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechMenuContractTest {
    @Test
    fun onlineSpeechMenuTypeIsStable() {
        assertEquals("online_asr_tts", MenuConfigType.MenuInfoType.ONLINE_ASR_TTS)
    }
}
