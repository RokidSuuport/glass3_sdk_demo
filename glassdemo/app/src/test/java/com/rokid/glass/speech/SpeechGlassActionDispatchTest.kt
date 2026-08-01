package com.rokid.glass.speech

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechGlassActionDispatchTest {
    @Test
    fun speechScreensUseSingleGestureDispatchPath() {
        val privateFragments = listOf(
            source("private/PrivateSpeechInitFragment.kt"),
            source("private/PrivateSpeechAsrFragment.kt"),
            source("private/PrivateSpeechTtsFragment.kt"),
        )
        privateFragments.forEach { source ->
            assertFalse(source.contains("setOnClickListener"))
        }

        val privateActivity = source("private/PrivateSpeechActivity.kt")
        assertTrue(privateActivity.contains(": SpeechGestureActivity()"))
        assertTrue(privateActivity.contains("dispatchAction(actionFor(navigation.current))"))

        assertTrue(privateActivity.contains("pager.isUserInputEnabled = false"))
        assertTrue(privateActivity.contains("tab.view.isClickable = false"))
    }

    @Test
    fun privateInitBindsGlassSdkAndContinuesAfterConnection() {
        val source = source("private/PrivateSpeechActivity.kt")

        assertTrue(source.contains("GlassSdk.bindSecurityService"))
        assertTrue(source.contains("initializeWhenBound = true"))
        assertTrue(source.contains("if (initializeWhenBound)"))
        assertTrue(source.contains("createPrivateSession()"))
    }

    @Test
    fun speechGestureActivityMapsGlassCenterKeysToClick() {
        val source = source("SpeechGestureActivity.kt")

        assertTrue(source.contains("override fun dispatchKeyEvent"))
        assertTrue(source.contains("KeyEvent.KEYCODE_DPAD_CENTER"))
        assertTrue(source.contains("KeyEvent.KEYCODE_ENTER"))
        assertTrue(source.contains("KeyEvent.KEYCODE_NUMPAD_ENTER"))
        assertTrue(source.contains("onGlassKeyEvent(GlassKeyEvent.KEYCODE_CLICK)"))
    }

    private fun source(relativePath: String): String {
        return File("src/main/java/com/rokid/glass/speech/$relativePath").readText()
    }
}
