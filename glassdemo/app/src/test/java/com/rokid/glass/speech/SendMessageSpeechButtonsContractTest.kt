package com.rokid.glass.speech

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class SendMessageSpeechButtonsContractTest {
    private fun ttsImplementation(): String = File("src/main/java/com/rokid/glass/speech")
        .listFiles().orEmpty().filter { it.name.startsWith("MessageTts") && it.extension == "kt" }
        .joinToString("\n") { it.readText() }

    @Test
    fun ttsModeSharesFirstRowWithAsrAndMessageButtonsStayOutside() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/layout/activity_sendmessage.xml"))
        val elements = document.getElementsByTagName("*").let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }
        }
        fun element(id: String) = elements.single { it.getAttribute("android:id") == "@+id/$id" }
        fun descendantIds(parent: Element) = parent.getElementsByTagName("androidx.appcompat.widget.AppCompatTextView").let { nodes ->
            (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("android:id").substringAfter('/') }
        }
        val group = element("speechControlsGroup")
        val modeRow = element("ttsModeRow")
        assertSame(group, modeRow.parentNode)
        assertSame(modeRow, element("btnTtsQueueMode").parentNode)
        assertEquals(listOf("btnTtsQueueMode", "btnOnlineAsr"), descendantIds(modeRow))
        assertEquals(listOf("btnTtsQueueMode", "btnOnlineAsr", "btnOnlineTts", "btnStopOnlineTts", "btnOfflineTts", "btnStopOfflineTts"), descendantIds(group))
        assertSame(group, element("onlineTtsRow").parentNode)
        assertSame(group, element("offlineTtsRow").parentNode)
        assertSame(modeRow, element("btnOnlineAsr").parentNode)
        assertEquals(listOf("btnOnlineTts", "btnStopOnlineTts"), descendantIds(element("onlineTtsRow")))
        assertEquals(listOf("btnOfflineTts", "btnStopOfflineTts"), descendantIds(element("offlineTtsRow")))
        assertSame(element("llMain"), element("wfSendTextBtn").parentNode.parentNode)
        assertFalse(group.hasAttribute("android:background"))
        assertEquals("1", element("controlsScrollView").getAttribute("app:layout_constraintVertical_weight"))
        assertEquals("1", element("scrollView").getAttribute("app:layout_constraintVertical_weight"))
        listOf("btQRCodeImage", "btQRCode", "btQRCodeXml", "btConnectWifi", "btDisconnectWifi", "btConfigureAppVisibility").forEach { moved ->
            assertFalse(elements.any { it.getAttribute("android:id") == "@+id/$moved" })
        }
        listOf("btnAiChat", "btnStopAiChat", "btnRegisterLeqi", "btnClearLog", "ttsModeHint", "btnCancelOnlineTts", "btnCancelOfflineTts", "btnTtsSessionState").forEach { removed ->
            assertFalse(elements.any { it.getAttribute("android:id") == "@+id/$removed" })
        }

        val source = File("src/main/java/com/rokid/glass/SendMessageActivity.kt").readText()
        assertTrue(source.substringAfter("override fun onCreate").substringBefore("private fun toClick")
            .contains("selectBtn(binding.btnTtsQueueMode)"))
        val menu = source.substringAfter("private fun menuButtons()").substringBefore("private fun moveSelect")
        val ids = listOf("btnTtsQueueMode", "btnOnlineAsr", "btnOnlineTts", "btnStopOnlineTts", "btnOfflineTts", "btnStopOfflineTts", "wfSendTextBtn")
        val indices = ids.map { menu.indexOf("binding.$it") }
        assertTrue(indices.all { it >= 0 })
        assertEquals(indices.sorted(), indices)
    }

    @Test
    fun messagePageShowsReadableSpeechControlsWithDistinctPlayAndStopLabels() {
        val layout = File("src/main/res/layout/activity_sendmessage.xml").readText()
        val onlineTts = layout.indexOf("btnOnlineTts")
        val offlineTts = layout.indexOf("btnOfflineTts")
        val onlineAsr = layout.indexOf("btnOnlineAsr")

        assertTrue(onlineTts >= 0)
        assertTrue(onlineTts < offlineTts)
        assertTrue(onlineAsr < onlineTts)
        assertTrue(layout.contains("@string/demo_online_tts_2"))
        assertTrue(layout.contains("@string/demo_offline_tts"))
        assertTrue(layout.contains("@string/demo_online_asr_2"))
        assertTrue(layout.contains("@string/message_tts_stop_online"))
        assertTrue(layout.contains("@string/message_tts_stop_offline"))
        listOf("btnOnlineTts", "btnStopOnlineTts", "btnOfflineTts", "btnStopOfflineTts", "btnOnlineAsr").forEach { buttonId ->
            val button = layout.substringAfter("android:id=\"@+id/$buttonId\"")
                .substringBefore("/>")
            assertTrue(button.contains("android:gravity=\"center\""))
            assertTrue(button.contains("android:singleLine=\"false\""))
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
        assertTrue(source.contains("R.id.btnStopOnlineTts -> stopTts(online = true)"))
        assertTrue(source.contains("R.id.btnOfflineTts -> playOfflineTts()"))
        assertTrue(source.contains("R.id.btnStopOfflineTts -> stopTts(online = false)"))
        assertTrue(source.contains("R.id.btnOnlineAsr -> startAsr()"))
        val controller = ttsImplementation()
        assertTrue(source.contains("messageTts.speak(online = true"))
        assertTrue(source.contains("messageTts.speak(online = false"))
        assertTrue(source.contains("messageTts.cancelOwned(online)"))
        assertFalse(source.contains("messageTts.toggle("))
        assertFalse(source.contains("updateTtsPlaybackButton"))
        assertFalse(source.contains("R.id.btnCancelOnlineTts"))
        assertFalse(source.contains("R.id.btnCancelOfflineTts"))
        assertFalse(source.contains("R.id.btnTtsSessionState"))
        // 两个引擎都使用会话接口，不静默降级到旧播报接口。
        // Both engines use session APIs without silently falling back to legacy playback.
        assertTrue(controller.contains("getGlassTtsService()"))
        assertTrue(controller.contains("getGlassOfflineTtsService()"))
        assertFalse(controller.contains("getGlassTtsManager().speak"))
        assertFalse(controller.contains("getGlassOfflineTtsManager().speak"))
        assertFalse(source.contains("doSpeechTts(ONLINE_TTS_DEMO_TEXT)"))
        assertFalse(source.contains("playTtsMsg(OFFLINE_TTS_DEMO_TEXT)"))
    }

    @Test
    fun ttsDemoReportsNetworkAuthenticationCompletionAndPlaybackFailures() {
        val source = File(
            "src/main/java/com/rokid/glass/SendMessageActivity.kt"
        ).readText()

        assertTrue(source.contains("R.string.demo_this_is_an_online_text_to_speech_demo"))
        assertTrue(source.contains("R.string.demo_this_is_an_offline_text_to_speech_demo"))
        val controller = ttsImplementation()
        assertTrue(controller.contains("R.string.message_tts_session_state"))
        assertTrue(controller.contains("R.string.message_tts_invalid_session"))
        assertTrue(controller.contains("R.string.message_tts_session_api_required"))
        assertTrue(controller.contains("R.string.message_tts_query_state"))
        assertFalse(controller.contains(".cancelAll()"))
        assertFalse(controller.contains(".cancelCurrent()"))
        assertFalse(controller.contains("getGlassTtsManager().release()"))
        assertFalse(controller.contains("getGlassOfflineTtsManager().release()"))
        assertFalse(source.contains("SpeechCompleteListener.Stub()"))
        assertFalse(source.contains("OnlineTtsStabilityRun"))
        assertFalse(source.contains("ONLINE_TTS_REPEAT_COUNT"))
    }

    @Test
    fun ttsRequestsCarrySelectedQueueModeWithoutLegacyPlaybackOrGlobalCancellation() {
        val source = File(
            "src/main/java/com/rokid/glass/SendMessageActivity.kt"
        ).readText()

        val controller = ttsImplementation()
        assertEquals(2, Regex("queueMode = ttsQueueMode").findAll(source).count())
        assertTrue(source.contains("TtsQueueMode.ADD"))
        assertTrue(source.contains("TtsQueueMode.FLUSH"))
        // 此处检查模式传递和操作范围，会话顺序由 SessionBook 测试覆盖。
        // Check mode forwarding and operation scope here; SessionBook tests cover session ordering.
        val playbackSources = source + controller
        assertFalse(playbackSources.contains(".doSpeechTts("))
        assertFalse(playbackSources.contains(".playTtsMsg("))
        assertFalse(playbackSources.contains(".cancelAll()"))
        assertFalse(playbackSources.contains(".cancelCurrent()"))
        assertFalse(source.contains("onlineTtsWaitingForConnection"))
        assertFalse(source.contains("dispatchNextOnlineTts"))

    }

    @Test
    fun everyTtsActionReportsModeAndExplanationInTheLog() {
        val source = File("src/main/java/com/rokid/glass/SendMessageActivity.kt").readText()
        listOf("playOnlineTts", "playOfflineTts", "stopTts", "toggleTtsQueueMode").forEach { action ->
            val body = source.substringAfter("private fun $action(").substringBefore("\n    private fun ")
            assertTrue("$action should log the selected mode", body.contains("logTtsQueueMode()"))
        }
        val logMode = source.substringAfter("private fun logTtsQueueMode()").substringBefore("override fun onStart")
        assertTrue(logMode.contains("R.string.message_tts_mode_add"))
        assertTrue(logMode.contains("R.string.message_tts_mode_flush"))
        assertTrue(logMode.contains("R.string.message_tts_add_explained"))
        assertTrue(logMode.contains("R.string.message_tts_flush_explained"))
    }

    @Test
    fun onlineAsrReportsNetworkConnectionAuthorizationAndTimeoutFailures() {
        val source = File(
            "src/main/java/com/rokid/glass/SendMessageActivity.kt"
        ).readText()

        assertTrue(source.contains("checkSpeechNetwork(demoString(R.string.demo_online_asr))"))
        assertTrue(source.contains("OnlineAsrStatusMessages.serviceUnavailable"))
        assertFalse(source.contains("log(OnlineAsrStatusMessages.connectionFailed)"))
        assertTrue(source.contains("OnlineAsrStatusMessages.connectionTimeout"))
        assertTrue(source.contains("OnlineAsrStatusMessages.error(code)"))
        assertTrue(source.contains("onlineAsrTimeoutJob"))
        assertTrue(source.contains("scheduleOnlineAsrTimeout()"))
        assertTrue(source.contains("cancelOnlineAsrTimeout()"))
        assertTrue(
            source.substringAfter("private fun startAsr()")
                .substringBefore("private fun playOnlineTts()")
                .contains("checkSpeechNetwork(demoString(R.string.demo_online_asr))")
        )
    }
}
