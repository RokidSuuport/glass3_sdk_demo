package com.rokid.glass.speech

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendMessageSpeechButtonsContractTest {
    @Test
    fun messagePageShowsThreeCompactSpeechButtonsInServiceOrder() {
        val layout = File("src/main/res/layout/activity_sendmessage.xml").readText()
        val onlineTts = layout.indexOf("btnOnlineTts")
        val offlineTts = layout.indexOf("btnOfflineTts")
        val onlineAsr = layout.indexOf("btnOnlineAsr")

        assertTrue(onlineTts >= 0)
        assertTrue(onlineTts < offlineTts)
        assertTrue(offlineTts < onlineAsr)
        assertTrue(layout.contains("android:text=\"在线TTS\""))
        assertTrue(layout.contains("android:text=\"离线TTS\""))
        assertTrue(layout.contains("android:text=\"在线ASR\""))
        listOf("btnOnlineTts", "btnOfflineTts", "btnOnlineAsr").forEach { buttonId ->
            val button = layout.substringAfter("android:id=\"@+id/$buttonId\"")
                .substringBefore("/>")
            assertTrue(button.contains("android:gravity=\"center\""))
            assertTrue(button.contains("android:paddingHorizontal=\"8dp\""))
            assertTrue(button.contains("android:paddingVertical=\"4dp\""))
            assertTrue(button.contains("android:textSize=\"12sp\""))
        }
        assertFalse(layout.contains("android:id=\"@+id/btTts\""))
        assertFalse(layout.contains("android:id=\"@+id/btAsr\""))
    }

    @Test
    fun eachSpeechButtonDispatchesToItsMatchingGlassSdkService() {
        val source = File(
            "src/main/java/com/rokid/glass/SendMessageActivity.kt"
        ).readText()

        assertTrue(source.contains("R.id.btnOnlineTts -> playOnlineTts()"))
        assertTrue(source.contains("R.id.btnOfflineTts -> playOfflineTts()"))
        assertTrue(source.contains("R.id.btnOnlineAsr -> startAsr()"))
        assertTrue(source.contains("getGlassTtsService()"))
        assertTrue(source.contains("doSpeechTts(ONLINE_TTS_DEMO_TEXT)"))
        assertTrue(source.contains("getGlassOfflineTtsService()"))
        assertTrue(source.contains("playTtsMsg(OFFLINE_TTS_DEMO_TEXT)"))
        assertTrue(
            source.indexOf("binding.btnOnlineTts") < source.indexOf("binding.btnOfflineTts")
        )
        assertTrue(
            source.indexOf("binding.btnOfflineTts") < source.indexOf("binding.btnOnlineAsr")
        )
    }

    @Test
    fun ttsDemoReportsNetworkAuthenticationCompletionAndPlaybackFailures() {
        val source = File(
            "src/main/java/com/rokid/glass/SendMessageActivity.kt"
        ).readText()

        assertTrue(source.contains("这是在线TTS语音播报"))
        assertTrue(source.contains("这是离线TTS语音播报"))
        assertTrue(source.contains("SpeechCompleteListener.Stub()"))
        assertTrue(source.contains("在线 TTS 播放完成"))
        assertTrue(source.contains("当前无可用网络"))
        assertTrue(source.contains("灵眸账号鉴权状态"))
        assertTrue(source.contains("鉴权或权限失败"))
        assertTrue(source.contains("未收到完成回调"))
        assertTrue(source.contains("离线 TTS 请求已发送"))
        assertTrue(source.contains("removeSpeechCompleteListener"))
        assertFalse(source.contains("OnlineTtsStabilityRun"))
        assertFalse(source.contains("ONLINE_TTS_REPEAT_COUNT"))
    }

    @Test
    fun onlineTtsDispatchesOneRequestWithoutWaitingForConnectionReplay() {
        val source = File(
            "src/main/java/com/rokid/glass/SendMessageActivity.kt"
        ).readText()

        assertTrue(source.contains("dispatchOnlineTts()"))
        assertTrue(source.contains("在线 TTS 服务连接成功"))
        assertTrue(source.contains("在线 TTS 请求已发送"))
        assertFalse(source.contains("onlineTtsWaitingForConnection"))
        assertFalse(source.contains("正在连接在线 TTS 服务，请稍候..."))
        assertFalse(
            source.substringAfter("private fun playOnlineTts()")
                .substringBefore("private fun dispatchOnlineTts()")
                .contains("service.doSpeechTts(ONLINE_TTS_DEMO_TEXT)")
        )
    }

    @Test
    fun onlineAsrReportsNetworkConnectionAuthorizationAndTimeoutFailures() {
        val source = File(
            "src/main/java/com/rokid/glass/SendMessageActivity.kt"
        ).readText()

        assertTrue(source.contains("OnlineAsrStatusMessages.noNetwork"))
        assertTrue(source.contains("OnlineAsrStatusMessages.serviceUnavailable"))
        assertTrue(source.contains("OnlineAsrStatusMessages.connectionFailed"))
        assertTrue(source.contains("OnlineAsrStatusMessages.connectionTimeout"))
        assertTrue(source.contains("OnlineAsrStatusMessages.error(code)"))
        assertTrue(source.contains("onlineAsrTimeoutJob"))
        assertTrue(source.contains("scheduleOnlineAsrTimeout()"))
        assertTrue(source.contains("cancelOnlineAsrTimeout()"))
        assertTrue(
            source.substringAfter("private fun startAsr()")
                .substringBefore("private fun playOnlineTts()")
                .contains("if (!isNetworkAvailable())")
        )
    }
}
