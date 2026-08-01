package com.rokid.glass.speech

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechFocusContractTest {
    @Test
    fun everySpeechButtonUsesPersistentSelectedOutline() {
        val layoutNames = listOf(
            "fragment_private_speech_init.xml",
            "fragment_private_speech_asr.xml",
            "fragment_private_speech_tts.xml",
        )

        layoutNames.forEach { name ->
            val xml = File("src/main/res/layout/$name").readText()
            val buttonCount = Regex("<Button").findAll(xml).count()
            val selectedOutlineCount = Regex(
                "android:background=\"@drawable/speech_button_background\""
            ).findAll(xml).count()
            assertTrue("$name must apply outline background to every button", buttonCount > 0)
            assertTrue(
                "$name has $buttonCount buttons but $selectedOutlineCount outline backgrounds",
                buttonCount == selectedOutlineCount,
            )
        }

        val selector = File(
            "src/main/res/drawable/speech_button_background.xml"
        ).readText()
        assertTrue(selector.contains("android:state_selected=\"true\""))
        assertTrue(selector.contains("@drawable/round_select_bg"))
        assertTrue(selector.contains("@drawable/round_unselect_bg"))
    }

    @Test
    fun selectionRenderersClearOldSelectionAndSelectTarget() {
        val privateSources = listOf(
            "PrivateSpeechInitFragment.kt",
            "PrivateSpeechAsrFragment.kt",
            "PrivateSpeechTtsFragment.kt",
        )
        privateSources.forEach { name ->
            val source = source("private/$name")
            assertTrue(source.contains("buttons.forEach { it.isSelected = false }"))
            assertTrue(source.contains("isSelected = true"))
        }
    }

    private fun source(relativePath: String): String {
        return File("src/main/java/com/rokid/glass/speech/$relativePath").readText()
    }
}
