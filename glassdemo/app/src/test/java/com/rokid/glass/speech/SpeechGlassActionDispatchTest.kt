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
    fun speechGestureActivityUsesSharedGlassCenterKeyDispatch() {
        val speech = source("SpeechGestureActivity.kt")
        val input = File("src/main/java/com/rokid/glass/base/GlassKeyInput.kt").readText()
        val base = File("src/main/java/com/rokid/glass/base/BaseActivity.kt").readText()

        assertFalse(speech.contains("override fun dispatchKeyEvent"))
        assertTrue(speech.contains("GlassKeyEvent(GlassKeyEvent.KEYCODE_CLICK)"))
        assertTrue(input.contains("KeyEvent.KEYCODE_DPAD_CENTER"))
        assertTrue(input.contains("KeyEvent.KEYCODE_ENTER"))
        assertTrue(input.contains("KeyEvent.KEYCODE_NUMPAD_ENTER"))
        assertTrue(base.contains("glassKeyInput.dispatch(event)"))
    }

    private fun source(relativePath: String): String {
        return File("src/main/java/com/rokid/glass/speech/$relativePath").readText()
    }
}
