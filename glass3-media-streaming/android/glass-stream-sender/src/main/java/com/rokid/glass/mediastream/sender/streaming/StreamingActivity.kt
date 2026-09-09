package com.rokid.glass.mediastream.sender.streaming

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.rokid.glass.mediastream.capture.MediaErrorCode
import com.rokid.glass.mediastream.capture.MediaFailure
import com.rokid.glass.mediastream.capture.MediaFailureCatalog
import com.rokid.glass.mediastream.sender.databinding.ActivityStreamingBinding
import com.rokid.glass.mediastream.sender.permission.MediaPermissionCoordinator
import com.rokid.glass.mediastream.streaming.GlassMediaStreamer
import com.rokid.glass.mediastream.streaming.StreamingOptions
import com.rokid.glass.mediastream.streaming.StreamingState
import com.rokid.glass.mediastream.streaming.StreamingStatus
import com.rokid.glass.mediastream.streaming.StreamingStatusListener

/**
 * 使用 [GlassMediaStreamer] 的完整可运行页面。
 *
 * 客户在同一仓库中可以直接参考本类；复制到另一个 Android 工程时，需要先按文档引入
 * `glass3-media-streaming` AAR/Maven 依赖，而不是复制本仓库的 `project(...)` 配置。
 */
class StreamingActivity : AppCompatActivity() {
    private lateinit var binding: ActivityStreamingBinding
    private lateinit var streamer: GlassMediaStreamer
    private lateinit var permissionCoordinator: MediaPermissionCoordinator
    private val screenModel = StreamingScreenModel()

    private var destroyed = false
    private var lastRenderedState = StreamingState.IDLE
    private var lastAttemptOptions: StreamingOptions? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStreamingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        streamer = GlassMediaStreamer.create(applicationContext)
        permissionCoordinator = MediaPermissionCoordinator.register(this)
        restoreSuccessfulServerUrl()

        binding.startButton.setOnClickListener { startFromInputs() }
        binding.retryButton.setOnClickListener {
            lastAttemptOptions?.let(::requestPermissionsAndStart)
                ?: showInputError("请先填写 PC 接收服务地址")
        }
        binding.stopButton.setOnClickListener { streamer.stop() }

        render(streamer.currentStatus(), null)
        binding.signalingUrl.requestFocus()
    }

    override fun onStop() {
        if (::streamer.isInitialized) streamer.stop()
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        if (::streamer.isInitialized) streamer.release()
        super.onDestroy()
    }

    private fun startFromInputs() {
        val serverUrl = binding.signalingUrl.text.toString().trim()
        if (serverUrl.isEmpty()) {
            showInputError("请输入 PC 接收服务地址，例如 ws://<PC-IP>:8080/ws")
            return
        }
        if (!binding.videoEnabled.isChecked && !binding.audioEnabled.isChecked) {
            showInputError("请至少选择视频或音频中的一项")
            return
        }
        requestPermissionsAndStart(
            StreamingOptions(
                serverUrl = serverUrl,
                videoEnabled = binding.videoEnabled.isChecked,
                audioEnabled = binding.audioEnabled.isChecked,
            ),
        )
    }

    private fun requestPermissionsAndStart(options: StreamingOptions) {
        val accepted = try {
            permissionCoordinator.request(
                videoEnabled = options.videoEnabled,
                audioEnabled = options.audioEnabled,
                onGranted = {
                    lastAttemptOptions = options
                    startStreaming(options)
                },
                onDenied = {
                    lastAttemptOptions = options
                    renderLocalFailure(MediaFailureCatalog.forCode(MediaErrorCode.PERMISSION_REQUIRED))
                },
            )
        } catch (error: Throwable) {
            lastAttemptOptions = options
            Log.e(LOG_TAG, "请求媒体权限失败", error)
            renderLocalFailure(MediaFailureCatalog.forCode(MediaErrorCode.PERMISSION_REQUIRED))
            return
        }
        if (!accepted) showInputError("权限请求正在处理中，请完成授权后再操作")
    }

    private fun startStreaming(options: StreamingOptions) {
        val listener = StreamingStatusListener { status -> postStatus(status, options) }
        try {
            streamer.start(options, listener)
        } catch (error: IllegalArgumentException) {
            Log.e(LOG_TAG, "推流参数无效", error)
            showInputError(error.message ?: "推流参数无效")
        } catch (error: IllegalStateException) {
            Log.e(LOG_TAG, "当前生命周期不能开始推流", error)
            showInputError(error.message ?: "当前不能开始推流")
        }
    }

    private fun postStatus(status: StreamingStatus, attemptOptions: StreamingOptions) {
        runOnUiThread {
            if (destroyed) return@runOnUiThread
            render(status, attemptOptions)
        }
    }

    private fun render(status: StreamingStatus, attemptOptions: StreamingOptions?) {
        status.failure?.takeIf { it.technicalMessage.isNotBlank() || it.cause != null }?.let { failure ->
            Log.e(LOG_TAG, "${failure.code}: ${failure.technicalMessage}", failure.cause)
        }
        if (
            status.state == StreamingState.STREAMING &&
            lastRenderedState != StreamingState.STREAMING &&
            attemptOptions != null
        ) {
            getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_SUCCESSFUL_SERVER_URL, attemptOptions.serverUrl)
                .apply()
        }
        lastRenderedState = status.state
        renderViewState(screenModel.render(status))
    }

    private fun renderViewState(viewState: StreamingViewState) {
        binding.statusTitle.text = viewState.title
        binding.actionHint.text = viewState.actionHint
        binding.metricsStatus.text = viewState.metricsText
        binding.errorStatus.apply {
            text = viewState.errorText.orEmpty()
            visibility = if (viewState.errorText.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        binding.startButton.isEnabled = viewState.startEnabled
        binding.stopButton.isEnabled = viewState.stopEnabled
        binding.retryButton.isEnabled = viewState.retryEnabled
        binding.signalingUrl.isEnabled = viewState.inputsEnabled
        binding.videoEnabled.isEnabled = viewState.inputsEnabled
        binding.audioEnabled.isEnabled = viewState.inputsEnabled
        binding.root.keepScreenOn = viewState.keepScreenOn
    }

    private fun renderLocalFailure(failure: MediaFailure) {
        renderViewState(
            screenModel.render(
                StreamingStatus(
                    state = StreamingState.ERROR,
                    failure = failure,
                ),
            ),
        )
    }

    private fun showInputError(message: String) {
        val idle = screenModel.render(StreamingStatus(StreamingState.IDLE))
        renderViewState(idle.copy(errorText = "INPUT_INVALID\n$message\n检查地址和媒体选择后重试"))
    }

    private fun restoreSuccessfulServerUrl() {
        val savedUrl = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .getString(KEY_SUCCESSFUL_SERVER_URL, null)
            .orEmpty()
        binding.signalingUrl.setText(savedUrl)
    }

    companion object {
        private const val LOG_TAG = "GlassMediaStream"
        private const val PREFERENCES_NAME = "media_streaming_settings"
        private const val KEY_SUCCESSFUL_SERVER_URL = "successful_server_url"
    }
}
