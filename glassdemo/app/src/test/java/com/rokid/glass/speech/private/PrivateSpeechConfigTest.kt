package com.rokid.glass.speech.privateservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateSpeechConfigTest {
    @Test
    fun reportsEveryMissingRequiredField() {
        val config = PrivateSpeechConfig("", "", "", "", "", "", "", false)

        assertEquals(
            listOf("domain", "ak", "sk", "uid", "deviceId", "asrPath", "ttsPath"),
            config.missingRequiredFields()
        )
    }

    @Test
    fun completeConfigHasNoMissingFields() {
        val config = PrivateSpeechConfig(
            "speech.example.com",
            "ak",
            "sk",
            "uid",
            "device",
            "/asr",
            "/tts",
            false
        )

        assertTrue(config.missingRequiredFields().isEmpty())
    }
}
