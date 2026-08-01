package com.rokid.glass.speech

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechChooserHighlightContractTest {
    @Test
    fun bothChooserEntriesUseAndMaintainSelectedHighlight() {
        val layout = File("src/main/res/layout/activity_speech_service_chooser.xml").readText()
        val activity = File(
            "src/main/java/com/rokid/glass/speech/SpeechServiceChooserActivity.kt"
        ).readText()

        assertEquals(
            2,
            Regex("android:foreground=\"@drawable/speech_button_focus_foreground\"")
                .findAll(layout).count(),
        )
        assertTrue(activity.contains("choices.forEach { it.isSelected = false }"))
        assertTrue(activity.contains("isSelected = true"))
    }
}
