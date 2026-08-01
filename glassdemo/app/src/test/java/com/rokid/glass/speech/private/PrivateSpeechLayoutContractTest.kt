package com.rokid.glass.speech.privateservice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateSpeechLayoutContractTest {
    @Test
    fun privateDemoUsesSourceTabsAndTenActions() {
        val activityXml = layout("activity_private_speech.xml")
        val initXml = layout("fragment_private_speech_init.xml")
        val asrXml = layout("fragment_private_speech_asr.xml")
        val ttsXml = layout("fragment_private_speech_tts.xml")

        assertTrue(activityXml.contains("com.google.android.material.tabs.TabLayout"))
        assertTrue(activityXml.contains("androidx.viewpager2.widget.ViewPager2"))
        assertFalse(activityXml.contains("HorizontalScrollView"))
        assertTrue(initXml.contains("btn_private_init"))
        assertTrue(initXml.contains("btn_private_release"))
        assertEquals(4, Regex("<Button").findAll(asrXml).count())
        assertEquals(4, Regex("<Button").findAll(ttsXml).count())

        val buttonLayouts = initXml + asrXml + ttsXml
        assertEquals(
            10,
            Regex("android:background=\"@drawable/speech_button_background\"")
                .findAll(buttonLayouts).count(),
        )
        assertEquals(
            10,
            Regex("android:textColor=\"@color/speech_button_text\"")
                .findAll(buttonLayouts).count(),
        )
        assertEquals(
            10,
            Regex("android:backgroundTint=\"@null\"").findAll(buttonLayouts).count(),
        )
        assertFalse(buttonLayouts.contains("#1E88E5"))
        assertFalse(buttonLayouts.contains("#43A047"))
        assertFalse(buttonLayouts.contains("#E53935"))
        assertFalse(buttonLayouts.contains("#8E24AA"))
        assertFalse(buttonLayouts.contains("#6D4C41"))
    }

    private fun layout(name: String): String {
        return File("src/main/res/layout/$name").readText()
    }
}
