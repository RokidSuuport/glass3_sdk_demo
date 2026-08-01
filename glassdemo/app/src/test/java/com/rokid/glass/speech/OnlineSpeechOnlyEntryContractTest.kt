package com.rokid.glass.speech

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineSpeechOnlyEntryContractTest {
    @Test
    fun homeOpensOnlineSpeechDirectlyWithoutDuplicateGlassSdkScreens() {
        val home = File("src/main/java/com/rokid/glass/HomeActivity.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(home.contains("\"独立ASR/TTS\""))
        assertTrue(home.contains("Intent(this, PrivateSpeechActivity::class.java)"))
        assertFalse(home.contains("SpeechServiceChooserActivity"))
        assertFalse(manifest.contains("SpeechServiceChooserActivity"))
        assertFalse(manifest.contains("publicservice.PublicSpeechActivity"))
        assertFalse(File("src/main/java/com/rokid/glass/speech/SpeechServiceChooserActivity.kt").exists())
        assertFalse(File("src/main/java/com/rokid/glass/speech/publicservice/PublicSpeechActivity.kt").exists())
        assertFalse(File("src/main/res/layout/activity_speech_service_chooser.xml").exists())
        assertFalse(File("src/main/res/layout/activity_public_speech.xml").exists())
    }
}
