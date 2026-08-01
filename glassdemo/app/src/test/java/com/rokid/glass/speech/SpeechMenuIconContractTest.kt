package com.rokid.glass.speech

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SpeechMenuIconContractTest {
    @Test
    fun onlineSpeechMenuUsesDedicatedIconForBothStates() {
        val source = File("src/main/java/com/rokid/glass/HomeActivity.kt").readText()
        val speechBlock = source.substringAfter("\"在线ASR/TTS\"").substringBefore("false\n                )")

        assertEquals(2, Regex("R\\.mipmap\\.app_online_speech").findAll(speechBlock).count())
        assertFalse(speechBlock.contains("R.mipmap.app_take_photo"))
    }
}
