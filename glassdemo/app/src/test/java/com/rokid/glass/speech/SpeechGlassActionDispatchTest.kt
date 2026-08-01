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

        val publicActivity = source("publicservice/PublicSpeechActivity.kt")
        assertTrue(publicActivity.contains(": SpeechGestureActivity()"))
        assertFalse(publicActivity.contains("setOnClickListener"))
        assertTrue(publicActivity.contains("runSelectedAction(navigation.current.itemIndex)"))

        val chooser = source("SpeechServiceChooserActivity.kt")
        assertTrue(chooser.contains(": SpeechGestureActivity()"))
        assertFalse(chooser.contains("setOnClickListener"))
        assertTrue(chooser.contains("openSelected(chooserState.selectedIndex)"))
        assertFalse(chooser.contains("setOnFocusChangeListener"))
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

    @Test
    fun publicSpeechDoesNotLogInitialTtsConnectionState() {
        val source = source("publicservice/PublicSpeechActivity.kt")

        assertTrue(source.contains("override fun onServiceConnectState(connected: Boolean) = Unit"))
        assertFalse(source.contains("TTS 服务连接："))
    }

    private fun source(relativePath: String): String {
        return File("src/main/java/com/rokid/glass/speech/$relativePath").readText()
    }
}
