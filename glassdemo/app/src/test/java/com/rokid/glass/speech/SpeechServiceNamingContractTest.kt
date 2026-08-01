package com.rokid.glass.speech

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechServiceNamingContractTest {
    @Test
    fun onlineSpeechScreenUsesSdkBasedName() {
        val home = File("src/main/java/com/rokid/glass/HomeActivity.kt").readText()
        val privateLayout = File("src/main/res/layout/activity_private_speech.xml").readText()

        assertTrue(home.contains("独立ASR/TTS"))
        assertTrue(privateLayout.contains("独立ASR/TTS"))
        assertTrue(privateLayout.contains("独立部署在眼镜端的在线 ASR/TTS 应用"))
        assertTrue(privateLayout.contains("通过 Online-Speech 实现"))
        assertTrue(privateLayout.contains("通过 OpenSdkAudioSource 修改输入音源"))
        assertTrue(privateLayout.contains("部署到其他 Android 系统"))
        assertFalse(home.contains("Online-Speech ASR/TTS"))
        assertFalse(home.contains("在线ASR/TTS"))
        assertFalse(home.contains("GlassSdk 在线语音"))
    }
}
