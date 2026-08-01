package com.rokid.glass.speech

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechServiceNamingContractTest {
    @Test
    fun speechScreensUseSdkBasedNames() {
        val chooser = File("src/main/res/layout/activity_speech_service_chooser.xml").readText()
        val publicLayout = File("src/main/res/layout/activity_public_speech.xml").readText()
        val privateLayout = File("src/main/res/layout/activity_private_speech.xml").readText()
        val publicSource = File(
            "src/main/java/com/rokid/glass/speech/publicservice/PublicSpeechActivity.kt"
        ).readText()

        assertTrue(chooser.contains("GlassSdk 在线语音"))
        assertTrue(chooser.contains("Online-Speech 在线语音"))
        assertEquals(2, Regex("android:textAllCaps=\"false\"").findAll(chooser).count())
        assertTrue(publicLayout.contains("GlassSdk 在线语音"))
        assertTrue(privateLayout.contains("Online-Speech 在线语音"))
        assertFalse(chooser.contains("公共语音服务"))
        assertFalse(chooser.contains("私有化语音服务"))
        assertTrue(publicSource.contains("GlassSdk 示例由 GlassSdk 管理 api.rokid.com 环境与鉴权"))
    }
}
