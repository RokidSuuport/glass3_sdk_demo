package com.rokid.glass.speech

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechFocusContractTest {
    @Test
    fun everySpeechButtonUsesPersistentSelectedForeground() {
        val layoutNames = listOf(
            "fragment_private_speech_init.xml",
            "fragment_private_speech_asr.xml",
            "fragment_private_speech_tts.xml",
            "activity_public_speech.xml",
        )

        layoutNames.forEach { name ->
            val xml = File("src/main/res/layout/$name").readText()
            val buttonCount = Regex("<Button").findAll(xml).count()
            val focusForegroundCount = Regex(
                "android:foreground=\"@drawable/speech_button_focus_foreground\""
            ).findAll(xml).count()
            assertTrue("$name must apply focus foreground to every button", buttonCount > 0)
            assertTrue(
                "$name has $buttonCount buttons but $focusForegroundCount focus foregrounds",
                buttonCount == focusForegroundCount,
            )
        }

        val selector = File(
            "src/main/res/drawable/speech_button_focus_foreground.xml"
        ).readText()
        assertTrue(selector.contains("android:state_selected=\"true\""))
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

        val publicSource = source("publicservice/PublicSpeechActivity.kt")
        assertTrue(publicSource.contains("controls.forEach { it.isSelected = false }"))
        assertTrue(publicSource.contains("isSelected = true"))
    }

    private fun source(relativePath: String): String {
        return File("src/main/java/com/rokid/glass/speech/$relativePath").readText()
    }
}
