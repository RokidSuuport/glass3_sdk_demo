package com.rokid.glass.speech.publicservice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicSpeechLayoutContractTest {
    @Test
    fun publicControlsUseTwoEqualWidthRows() {
        val xml = File("src/main/res/layout/activity_public_speech.xml").readText()

        assertTrue(xml.contains("@+id/row_public_asr"))
        assertTrue(xml.contains("@+id/row_public_tts"))
        assertFalse(xml.contains("HorizontalScrollView"))
        val weightedButtons = Regex(
            "<Button[\\s\\S]*?android:layout_width=\\\"0dp\\\"[\\s\\S]*?android:layout_weight=\\\"1\\\"[\\s\\S]*?/>",
        ).findAll(xml).count()
        assertEquals(4, weightedButtons)
        listOf(
            "btn_public_asr_start",
            "btn_public_asr_stop",
            "btn_public_tts_start",
            "btn_public_tts_stop",
        ).forEach { id -> assertTrue(xml.contains(id)) }
    }
}
