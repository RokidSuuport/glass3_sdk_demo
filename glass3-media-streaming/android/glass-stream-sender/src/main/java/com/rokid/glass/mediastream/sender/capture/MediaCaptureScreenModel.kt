package com.rokid.glass.mediastream.sender.capture

import com.rokid.glass.mediastream.capture.AudioCaptureMetrics
import com.rokid.glass.mediastream.capture.CaptureState
import com.rokid.glass.mediastream.capture.CaptureStatus
import com.rokid.glass.mediastream.capture.MediaFailure
import com.rokid.glass.mediastream.capture.VideoCaptureMetrics
import java.util.Locale

internal enum class RecordingState {
    IDLE,
    RECORDING,
    FINALIZING,
    READY,
    ERROR,
}

internal enum class SnapshotState {
    UNAVAILABLE,
    READY,
    PENDING_FRAME,
    SAVING,
    SAVED,
    ERROR,
}

internal enum class PlaybackState {
    UNAVAILABLE,
    READY,
    PLAYING,
    ERROR,
}

/**
 * 原始媒体页面一次渲染所需的完整输入。
 *
 * 这里仅保存已经脱离媒体回调生命周期的状态和统计值，不保存 [Nv21Frame][com.rokid.glass.mediastream.capture.Nv21Frame]
 * 或其他由 SDK 管理的缓冲区。
 */
internal data class MediaCaptureScreenInput(
    val status: CaptureStatus,
    val videoSelected: Boolean = false,
    val audioSelected: Boolean = false,
    val hasVideoFrame: Boolean = false,
    val latestDbfs: Double? = null,
    val recordingState: RecordingState = RecordingState.IDLE,
    val snapshotState: SnapshotState = SnapshotState.UNAVAILABLE,
    val playbackState: PlaybackState = PlaybackState.UNAVAILABLE,
    val wavPath: String? = null,
    val jpegPath: String? = null,
    val localMessage: String? = null,
)

internal data class MediaCaptureViewState(
    val title: String,
    val actionHint: String,
    val videoMetricsText: String,
    val audioMetricsText: String,
    val snapshotStatusText: String,
    val recordingStatusText: String,
    val playbackStatusText: String,
    val jpegPathText: String?,
    val wavPathText: String?,
    val errorText: String?,
    val localFileMessageText: String?,
    val startEnabled: Boolean,
    val stopEnabled: Boolean,
    val mediaSelectionEnabled: Boolean,
    val recordEnabled: Boolean,
    val stopRecordingEnabled: Boolean,
    val saveFrameEnabled: Boolean,
    val playEnabled: Boolean,
    val stopPlaybackEnabled: Boolean,
    val keepScreenOn: Boolean,
)

/**
 * 把采集、截图、录音和播放状态转换为稳定的页面文案与控件策略。
 *
 * 该模型不依赖 Android UI 或线程，因此所有互斥规则都可以用 JVM 单元测试固定下来。
 * 媒体错误只输出面向客户的错误码、说明和建议，不会把异常类型、栈或厂商细节带到页面。
 */
internal class MediaCaptureScreenModel {
    fun render(input: MediaCaptureScreenInput): MediaCaptureViewState {
        val captureState = input.status.state
        val released = captureState == CaptureState.RELEASED
        val captureRestartable = captureState == CaptureState.IDLE || captureState == CaptureState.ERROR
        val captureActive = captureState == CaptureState.PREPARING || captureState == CaptureState.CAPTURING
        val recordingActive = input.recordingState == RecordingState.RECORDING
        val recordingFinalizing = input.recordingState == RecordingState.FINALIZING
        val playbackActive = input.playbackState == PlaybackState.PLAYING
        val snapshotActive = input.snapshotState == SnapshotState.PENDING_FRAME ||
            input.snapshotState == SnapshotState.SAVING
        val localMediaWorkActive = recordingActive || recordingFinalizing || playbackActive || snapshotActive

        val actualAudioFormat = input.audioSelected && input.status.audioMetrics.hasActualFormat()
        val recordingCanStart = input.recordingState in setOf(
            RecordingState.IDLE,
            RecordingState.READY,
            RecordingState.ERROR,
        )
        val snapshotCanStart = input.snapshotState in setOf(
            SnapshotState.READY,
            SnapshotState.SAVED,
            SnapshotState.ERROR,
        )

        return MediaCaptureViewState(
            title = title(captureState),
            actionHint = actionHint(captureState),
            videoMetricsText = videoMetrics(input.videoSelected, input.status.videoMetrics),
            audioMetricsText = audioMetrics(
                selected = input.audioSelected,
                metrics = input.status.audioMetrics,
                latestDbfs = input.latestDbfs,
            ),
            snapshotStatusText = snapshotStatus(input.snapshotState),
            recordingStatusText = recordingStatus(input.recordingState),
            playbackStatusText = playbackStatus(input.playbackState),
            jpegPathText = input.jpegPath.absolutePathOrNull(),
            wavPathText = input.wavPath.absolutePathOrNull(),
            errorText = input.status.failure
                ?.takeIf { captureState == CaptureState.ERROR }
                ?.toCustomerText(),
            localFileMessageText = input.localMessage
                ?.trim()
                ?.takeIf { it.isNotEmpty() && !released },
            startEnabled = captureRestartable && !localMediaWorkActive && !released,
            stopEnabled = captureActive && !released,
            mediaSelectionEnabled = captureRestartable && !localMediaWorkActive && !released,
            recordEnabled = captureState == CaptureState.CAPTURING &&
                actualAudioFormat &&
                recordingCanStart &&
                !playbackActive &&
                !snapshotActive &&
                !released,
            stopRecordingEnabled = recordingActive && !released,
            saveFrameEnabled = captureState == CaptureState.CAPTURING &&
                input.videoSelected &&
                input.hasVideoFrame &&
                snapshotCanStart &&
                !recordingActive &&
                !recordingFinalizing &&
                !playbackActive &&
                !released,
            playEnabled = captureState == CaptureState.IDLE &&
                input.recordingState == RecordingState.READY &&
                input.playbackState == PlaybackState.READY &&
                input.wavPath.absolutePathOrNull() != null &&
                !snapshotActive &&
                !released,
            stopPlaybackEnabled = playbackActive && !released,
            keepScreenOn = captureState == CaptureState.CAPTURING && !released,
        )
    }

    private fun title(state: CaptureState): String = when (state) {
        CaptureState.IDLE -> "准备原始媒体采集"
        CaptureState.PREPARING -> "正在准备媒体服务"
        CaptureState.CAPTURING -> "原始媒体采集中"
        CaptureState.STOPPING -> "正在停止采集"
        CaptureState.ERROR -> "媒体采集失败"
        CaptureState.RELEASED -> "页面资源已释放"
    }

    private fun actionHint(state: CaptureState): String = when (state) {
        CaptureState.IDLE -> "选择视频和/或音频，开始检查眼镜输出的原始媒体数据。"
        CaptureState.PREPARING -> "正在连接 Glass 媒体服务并等待首个媒体帧。"
        CaptureState.CAPTURING -> "可查看实际格式和音量，也可按需保存画面或录制音频。"
        CaptureState.STOPPING -> "正在停止媒体回调并安全释放本次采集资源。"
        CaptureState.ERROR -> "请根据错误提示处理后重试；组件会先完成资源清理并等待设备恢复。"
        CaptureState.RELEASED -> "当前页面已经释放相机、麦克风和文件资源。"
    }

    private fun videoMetrics(selected: Boolean, metrics: VideoCaptureMetrics): String {
        if (!selected || metrics.width <= 0 || metrics.height <= 0) {
            return "NV21 视频：--\n帧数：--　丢帧：--　字节：--"
        }
        val fps = metrics.fps
            .takeIf { it.isFinite() && it >= 0.0 }
            ?.let { String.format(Locale.US, "%.1f", it) }
            ?: "--"
        return buildString {
            appendLine("NV21 视频：${metrics.width} × ${metrics.height} @ $fps fps")
            append("帧数：${metrics.frameCount}　丢帧：${metrics.droppedFrames}　字节：${metrics.bytesReceived}")
        }
    }

    private fun audioMetrics(
        selected: Boolean,
        metrics: AudioCaptureMetrics,
        latestDbfs: Double?,
    ): String {
        val formatText = if (selected && metrics.hasActualFormat()) {
            "PCM 音频：${metrics.sampleRateHz} Hz / ${metrics.channelCount} 声道 / ${metrics.bitsPerSample} bit\n" +
                "帧数：${metrics.frameCount}　字节：${metrics.bytesReceived}"
        } else {
            "PCM 音频：--\n帧数：--　字节：--"
        }
        return "$formatText\n${dbfsText(selected, latestDbfs)}"
    }

    private fun dbfsText(selected: Boolean, latestDbfs: Double?): String {
        val value = latestDbfs?.takeIf { selected && it.isFinite() } ?: return "音量：--"
        val clamped = value.coerceIn(SILENCE_DBFS, 0.0)
        return if (clamped <= SILENCE_DBFS) {
            "音量：≤ -96.0 dBFS（静音）"
        } else {
            "音量：${String.format(Locale.US, "%.2f", clamped)} dBFS"
        }
    }

    private fun snapshotStatus(state: SnapshotState): String = when (state) {
        SnapshotState.UNAVAILABLE -> "画面保存：等待有效视频帧"
        SnapshotState.READY -> "画面保存：可以保存当前画面"
        SnapshotState.PENDING_FRAME -> "画面保存：等待下一帧"
        SnapshotState.SAVING -> "画面保存：正在保存"
        SnapshotState.SAVED -> "画面保存：已保存"
        SnapshotState.ERROR -> "画面保存：失败，可重试"
    }

    private fun recordingStatus(state: RecordingState): String = when (state) {
        RecordingState.IDLE -> "PCM 录音：未开始"
        RecordingState.RECORDING -> "PCM 录音中"
        RecordingState.FINALIZING -> "正在完成录音并写入 WAV 文件头"
        RecordingState.READY -> "PCM 录音：已保存"
        RecordingState.ERROR -> "PCM 录音：失败，可重试"
    }

    private fun playbackStatus(state: PlaybackState): String = when (state) {
        PlaybackState.UNAVAILABLE -> "录音播放：暂无可用 WAV 文件"
        PlaybackState.READY -> "录音播放：可以播放"
        PlaybackState.PLAYING -> "录音播放中"
        PlaybackState.ERROR -> "录音播放：失败，可重试"
    }

    private fun AudioCaptureMetrics.hasActualFormat(): Boolean =
        sampleRateHz > 0 && channelCount > 0 && bitsPerSample > 0

    private fun String?.absolutePathOrNull(): String? = this
        ?.trim()
        ?.takeIf { it.startsWith('/') }

    private fun MediaFailure.toCustomerText(): String = buildString {
        appendLine(code.name)
        appendLine(userMessage)
        append(suggestedAction)
    }

    private companion object {
        const val SILENCE_DBFS = -96.0
    }
}
