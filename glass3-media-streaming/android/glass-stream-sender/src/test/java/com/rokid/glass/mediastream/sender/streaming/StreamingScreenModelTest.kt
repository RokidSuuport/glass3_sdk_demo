package com.rokid.glass.mediastream.sender.streaming

import com.rokid.glass.mediastream.capture.MediaErrorCode
import com.rokid.glass.mediastream.capture.MediaFailure
import com.rokid.glass.mediastream.capture.MediaFailureCatalog
import com.rokid.glass.mediastream.streaming.StreamingState
import com.rokid.glass.mediastream.streaming.StreamingStats
import com.rokid.glass.mediastream.streaming.StreamingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingScreenModelTest {
    private val model = StreamingScreenModel()

    @Test
    fun every_streaming_state_has_a_meaningful_customer_title() {
        val titles = StreamingState.entries.associateWith { state ->
            model.render(StreamingStatus(state)).title
        }

        assertEquals("准备音视频传输", titles.getValue(StreamingState.IDLE))
        assertEquals("正在准备媒体服务", titles.getValue(StreamingState.PREPARING))
        assertEquals("等待浏览器接收端", titles.getValue(StreamingState.WAITING_RECEIVER))
        assertEquals("正在建立媒体连接", titles.getValue(StreamingState.NEGOTIATING))
        assertEquals("音视频传输中", titles.getValue(StreamingState.STREAMING))
        assertEquals("正在停止传输", titles.getValue(StreamingState.STOPPING))
        assertEquals("音视频传输失败", titles.getValue(StreamingState.ERROR))
        assertEquals("页面资源已释放", titles.getValue(StreamingState.RELEASED))
    }

    @Test
    fun every_streaming_state_has_a_complete_control_policy_and_action_hint() {
        data class ExpectedPolicy(
            val start: Boolean,
            val stop: Boolean,
            val retry: Boolean,
            val inputs: Boolean,
            val keepScreenOn: Boolean,
        )

        val expected = mapOf(
            StreamingState.IDLE to ExpectedPolicy(true, false, false, true, false),
            StreamingState.PREPARING to ExpectedPolicy(false, true, false, false, false),
            StreamingState.WAITING_RECEIVER to ExpectedPolicy(false, true, false, false, false),
            StreamingState.NEGOTIATING to ExpectedPolicy(false, true, false, false, false),
            StreamingState.STREAMING to ExpectedPolicy(false, true, false, false, true),
            StreamingState.STOPPING to ExpectedPolicy(false, false, false, false, false),
            StreamingState.ERROR to ExpectedPolicy(true, false, true, true, false),
            StreamingState.RELEASED to ExpectedPolicy(false, false, false, false, false),
        )

        expected.forEach { (state, policy) ->
            val viewState = model.render(StreamingStatus(state))
            assertTrue("$state must have an action hint", viewState.actionHint.isNotBlank())
            assertEquals("$state start", policy.start, viewState.startEnabled)
            assertEquals("$state stop", policy.stop, viewState.stopEnabled)
            assertEquals("$state retry", policy.retry, viewState.retryEnabled)
            assertEquals("$state inputs", policy.inputs, viewState.inputsEnabled)
            assertEquals("$state keep screen on", policy.keepScreenOn, viewState.keepScreenOn)
        }
    }

    @Test
    fun waiting_receiver_explains_that_browser_must_be_started() {
        val viewState = model.render(StreamingStatus(StreamingState.WAITING_RECEIVER))

        assertEquals("等待浏览器接收端", viewState.title)
        assertTrue(viewState.actionHint.contains("PC"))
        assertTrue(viewState.stopEnabled)
        assertFalse(viewState.startEnabled)
        assertFalse(viewState.inputsEnabled)
    }

    @Test
    fun receiver_timeout_remains_a_non_fatal_waiting_instruction() {
        val viewState = model.render(
            StreamingStatus(
                state = StreamingState.WAITING_RECEIVER,
                failure = MediaFailureCatalog.forCode(MediaErrorCode.RECEIVER_NOT_READY),
            ),
        )

        assertEquals("等待浏览器接收端", viewState.title)
        assertTrue(viewState.actionHint.contains("保持等待"))
        assertNull(viewState.errorText)
        assertTrue(viewState.stopEnabled)
    }

    @Test
    fun stable_error_code_and_action_are_visible_but_technical_detail_is_not() {
        val failure = MediaFailure(
            MediaErrorCode.SERVER_UNREACHABLE,
            "无法连接 PC",
            "检查地址、端口、防火墙和网络",
            "java.net.ConnectException: refused",
            IllegalStateException("secret socket detail"),
        )

        val viewState = model.render(StreamingStatus(StreamingState.ERROR, failure = failure))

        assertTrue(viewState.errorText.orEmpty().contains("SERVER_UNREACHABLE"))
        assertTrue(viewState.errorText.orEmpty().contains("无法连接 PC"))
        assertTrue(viewState.errorText.orEmpty().contains("检查地址"))
        assertFalse(viewState.errorText.orEmpty().contains("ConnectException"))
        assertFalse(viewState.errorText.orEmpty().contains("secret socket detail"))
        assertTrue(viewState.retryEnabled)
        assertTrue(viewState.inputsEnabled)
    }

    @Test
    fun idle_error_and_released_states_have_stable_control_policies() {
        val idle = model.render(StreamingStatus(StreamingState.IDLE))
        val error = model.render(StreamingStatus(StreamingState.ERROR))
        val released = model.render(StreamingStatus(StreamingState.RELEASED))

        assertTrue(idle.startEnabled)
        assertFalse(idle.stopEnabled)
        assertFalse(idle.retryEnabled)
        assertTrue(idle.inputsEnabled)

        assertTrue(error.startEnabled)
        assertFalse(error.stopEnabled)
        assertTrue(error.retryEnabled)
        assertTrue(error.inputsEnabled)

        assertFalse(released.startEnabled)
        assertFalse(released.stopEnabled)
        assertFalse(released.retryEnabled)
        assertFalse(released.inputsEnabled)
    }

    @Test
    fun live_metrics_are_formatted_without_presenting_unknown_dimensions_as_real() {
        val unavailable = model.render(StreamingStatus(StreamingState.STREAMING)).metricsText
        val actual = model.render(
            StreamingStatus(
                state = StreamingState.STREAMING,
                stats = StreamingStats(
                    videoWidth = 1280,
                    videoHeight = 720,
                    videoFps = 14.5,
                    videoBitrateBps = 1_200_000,
                    audioBitrateBps = 24_000,
                    packetsLost = 3,
                    roundTripTimeMs = 37,
                    pcmUnderrunBytes = 320,
                    pcmDroppedBytes = 640,
                ),
            ),
        ).metricsText

        assertTrue(unavailable.contains("视频：--"))
        assertFalse(unavailable.contains("0 × 0"))
        assertTrue(actual.contains("1280 × 720 @ 14.5 fps"))
        assertTrue(actual.contains("1200 kbps"))
        assertTrue(actual.contains("24 kbps"))
        assertTrue(actual.contains("丢包：3"))
        assertTrue(actual.contains("RTT：37 ms"))
        assertTrue(actual.contains("补静音：320 B"))
        assertTrue(actual.contains("丢弃：640 B"))
    }

    @Test
    fun retry_attempt_is_visible_and_a_later_non_error_status_has_no_stale_error() {
        val retrying = model.render(
            StreamingStatus(
                state = StreamingState.PREPARING,
                retryAttempt = 2,
            ),
        )
        val later = model.render(StreamingStatus(StreamingState.STREAMING))

        assertTrue(retrying.actionHint.contains("第 2 次重连"))
        assertNull(later.errorText)
    }
}
