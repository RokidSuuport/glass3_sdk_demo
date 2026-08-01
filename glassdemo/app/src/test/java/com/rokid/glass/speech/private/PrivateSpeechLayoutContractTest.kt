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
    }

    private fun layout(name: String): String {
        return File("src/main/res/layout/$name").readText()
    }
}
