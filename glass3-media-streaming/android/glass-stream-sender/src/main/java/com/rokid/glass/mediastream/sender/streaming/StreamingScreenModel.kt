package com.rokid.glass.mediastream.sender.streaming

import com.rokid.glass.mediastream.capture.MediaErrorCode
import com.rokid.glass.mediastream.capture.MediaFailure
import com.rokid.glass.mediastream.streaming.StreamingState
import com.rokid.glass.mediastream.streaming.StreamingStats
import com.rokid.glass.mediastream.streaming.StreamingStatus
import java.util.Locale

internal data class StreamingViewState(
    val title: String,
    val actionHint: String,
    val metricsText: String,
    val errorText: String?,
    val startEnabled: Boolean,
    val stopEnabled: Boolean,
    val retryEnabled: Boolean,
    val inputsEnabled: Boolean,
    val keepScreenOn: Boolean,
)

/**
 * 将推流库的稳定状态转换为眼镜页面文案和控件状态。
 *
 * 这个类不依赖 Activity、View 或 Looper，因此状态规则可以通过普通 JVM 单元测试验证。
 * 面向客户的错误只展示稳定错误码、说明和处理建议，不展示异常栈等内部实现信息。
 */
internal class StreamingScreenModel {
    fun render(status: StreamingStatus): StreamingViewState {
        val state = status.state
        val receiverWaiting =
            state == StreamingState.WAITING_RECEIVER &&
                status.failure?.code == MediaErrorCode.RECEIVER_NOT_READY
        val baseHint = when (state) {
            StreamingState.IDLE -> "输入 PC 接收服务地址，选择需要发送的视频和音频。"
            StreamingState.PREPARING -> "正在连接 Glass 媒体服务和 PC 信令服务。"
            StreamingState.WAITING_RECEIVER -> if (receiverWaiting) {
                "PC 浏览器接收端尚未就绪，请启动接收页面；也可以保持等待。"
            } else {
                "请在 PC 上启动浏览器接收端，本页面会继续等待。"
            }
            StreamingState.NEGOTIATING -> "眼镜与 PC 浏览器正在协商音视频连接。"
            StreamingState.STREAMING -> "NV21 视频和 PCM 音频正在发送到 PC 浏览器。"
            StreamingState.STOPPING -> "正在按顺序关闭音视频、网络和媒体资源。"
            StreamingState.ERROR -> "资源已清理，可以修改设置后重新开始或使用重试。"
            StreamingState.RELEASED -> "当前页面已经释放媒体资源。"
        }
        val retryHint = status.retryAttempt.takeIf { it > 0 }
            ?.let { "（第 $it 次重连）" }
            .orEmpty()
        val inputState = state == StreamingState.IDLE || state == StreamingState.ERROR

        return StreamingViewState(
            title = title(state),
            actionHint = baseHint + retryHint,
            metricsText = metrics(status.stats),
            errorText = status.failure
                ?.takeUnless { receiverWaiting }
                ?.toCustomerText(),
            startEnabled = inputState,
            stopEnabled = state in setOf(
                StreamingState.PREPARING,
                StreamingState.WAITING_RECEIVER,
                StreamingState.NEGOTIATING,
                StreamingState.STREAMING,
            ),
            retryEnabled = state == StreamingState.ERROR,
            inputsEnabled = inputState,
            keepScreenOn = state == StreamingState.STREAMING,
        )
    }

    private fun title(state: StreamingState): String = when (state) {
        StreamingState.IDLE -> "准备音视频传输"
        StreamingState.PREPARING -> "正在准备媒体服务"
        StreamingState.WAITING_RECEIVER -> "等待浏览器接收端"
        StreamingState.NEGOTIATING -> "正在建立媒体连接"
        StreamingState.STREAMING -> "音视频传输中"
        StreamingState.STOPPING -> "正在停止传输"
        StreamingState.ERROR -> "音视频传输失败"
        StreamingState.RELEASED -> "页面资源已释放"
    }

    private fun metrics(stats: StreamingStats): String = buildString {
        if (stats.videoWidth > 0 && stats.videoHeight > 0) {
            append("采集：${stats.videoWidth} × ${stats.videoHeight} @ ")
            append(String.format(Locale.US, "%.1f", stats.videoFps))
            appendLine(" fps")
        } else {
            appendLine("采集：--")
        }
        if (stats.encodedVideoWidth > 0 && stats.encodedVideoHeight > 0) {
            append("发送：${stats.encodedVideoWidth} × ${stats.encodedVideoHeight} @ ")
            append(String.format(Locale.US, "%.1f", stats.encodedVideoFps))
            appendLine(" fps")
        } else {
            appendLine("发送：--")
        }
        appendLine("视频码率：${stats.videoBitrateBps / 1_000} kbps")
        appendLine("音频码率：${stats.audioBitrateBps / 1_000} kbps")
        appendLine("丢包：${stats.packetsLost}　RTT：${stats.roundTripTimeMs} ms")
        append("PCM 补静音：${stats.pcmUnderrunBytes} B　丢弃：${stats.pcmDroppedBytes} B")
    }

    private fun MediaFailure.toCustomerText(): String = buildString {
        appendLine(code.name)
        appendLine(userMessage)
        append(suggestedAction)
    }
}
