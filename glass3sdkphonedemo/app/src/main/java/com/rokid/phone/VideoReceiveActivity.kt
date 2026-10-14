package com.rokid.phone

import com.rokid.phone.utils.demoString
import com.rokid.phone.R

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.rokid.phone.data.GlobalData
import com.rokid.phone.databinding.ActivityVideoReceiveBinding
import com.rokid.phone.utils.TimeUtils
import com.rokid.phone.video.FrameRateMeter
import com.rokid.phone.video.FitCenterScaleCalculator
import com.rokid.phone.video.H264NalUnitInspector
import com.rokid.phone.video.H264SurfaceDecoder
import com.rokid.phone.video.DecodedVideoGeometry
import com.rokid.phone.video.VideoFrameRecoveryGate
import com.rokid.phone.video.VideoStreamRecoveryPolicy
import com.rokid.phone.video.VideoStreamZoomControls
import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.security.phone.sdk.api.msg.listener.IMessageListener
import com.rokid.security.phone.sdk.base.utils.other.mainScope
import com.rokid.security.sdk.base.common.GlassVideoStreamParam
import com.rokid.security.sdk.ability.message.ICommonMessage
import com.rokid.security.sdk.ability.message.MessageReceiver
import com.rokid.security.sdk.ability.message.MessageType
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import kotlin.coroutines.resume

class VideoReceiveActivity : ComponentActivity() {

    private val TAG = "VideoReceiveActivity"
    private lateinit var binding: ActivityVideoReceiveBinding
    private val VIDEO_TAG = "VIDEO_TAG"
    private val AUDIO_TAG = "AUDIO_TAG"
    private var startTime = 0L
    private val zoomControls = VideoStreamZoomControls()
    @Volatile private var zoomConnectionEpoch = 0
    @Volatile private var zoomStatusRequestVersion = -1
    private val zoomPresets = listOf<Float?>(null, 1f, 1.575f, 2f, 3f)

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoReceiveBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initView()
        switchPage(PageState.CONFIG)
        PSecuritySDK.getMessageService()?.addMessageListener(nv21Listener)
        // Public shared-message bus retains the transport envelope, including capability presence.
        MessageReceiver.getMessageAction().injectIMessage(zoomStatusListener)
        lifecycleScope.launch {
            combine(GlobalData.btConnectState, GlobalData.p2pConnectState) { bt, p2p -> bt to p2p }
                .collect {
                    zoomConnectionEpoch++
                    zoomControls.resetConnection()
                    currentConfig = currentConfig?.copy(zoomRatio = null)
                    binding.spinnerSdk222Zoom.setSelection(0)
                    renderZoomState()
                }
        }
    }

    private val audioTrack = AudioTrack(
        // 音频流类型
        // Audio stream type.
        AudioManager.STREAM_MUSIC,
        // 采样率（必须一致）
        // Sample rate; it must match the source.
        16000,
        // 声道配置（与录音一致）
        // Channel configuration; it must match the recording.
        AudioFormat.CHANNEL_OUT_MONO,
        // 编码格式（必须一致）
        // Encoding format; it must match the source.
        AudioFormat.ENCODING_PCM_16BIT,
        // 合理的缓冲区大小
        // Use an appropriate buffer size.
        AudioTrack.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ),
        // 流式模式（适合实时播放）
        // Streaming mode is suitable for real-time playback.
        AudioTrack.MODE_STREAM
    )

    private enum class PageState { CONFIG, PREVIEW }

    /**
     * 两种模式在网络中传输的都是眼镜端编码后的 H.264 压缩码流，区别在手机端：
     * Both modes transmit an H.264 compressed stream encoded by the glasses; they differ on the phone:
     *
     * NV21：手机 SDK 内部解码 H.264 并转换成 NV21，再由 Demo 的 GLSurfaceView 渲染。
     * NV21: the phone SDK decodes H.264 and converts it to NV21, which the Demo renders using GLSurfaceView.
     * 适合需要逐帧算法、截图或像素处理的场景，但解码、格式转换和内存复制开销较高。
     * Suitable for per-frame algorithms, screenshots, or pixel processing, but decoding, format conversion, and memory copies add overhead.
     *
     * H264：Demo 将收到的 H.264 码流交给 MediaCodec 硬解并直接输出到 SurfaceView。
     * H264: the Demo passes the received H.264 stream to MediaCodec for hardware decoding directly to SurfaceView.
     * 适合仅做实时预览的场景，延迟和资源占用更低，但不向业务层提供 NV21 像素帧。
     * Suitable for live preview with lower latency and resource use, but does not expose NV21 pixel frames to application code.
     */
    private enum class PreviewMode(private val label: String) {
        NV21("NV21"),
        H264("H.264");

        override fun toString(): String = label
    }

    private var currentState = PageState.CONFIG

    private val defaultFps = 15
    private val defaultBitrate = 20_000_000
    private var isGetVideo = false
    private var timerJob: Job? = null
    private var startPreviewJob: Job? = null
    private var videoWatchdogJob: Job? = null
    private var audioWatchdogJob: Job? = null
    private var videoRetryJob: Job? = null
    private var audioRetryJob: Job? = null
    private var pendingStreamStopJob: Job? = null
    private var lastCallTime = 0L
    private var lastRenderedFrameTime = 0L
    private var lastAudioTime = 0L
    private var tryCount = 0
    private var streamRequestVersion = 0
    private var videoRequestCount = 0
    private var audioRequestCount = 0
    private var isAudioRequestedForCurrentStream = false
    private var isCheckingP2p = false
    private var hasReportedVideoNoFrames = false
    private var isCheckingVideoRecovery = false
    private var remoteVideoRestartCount = 0
    private var lastRemoteVideoRestartTime = 0L
    private var localDecoderRestartUsed = false
    @Volatile
    private var videoFrameEpoch = 0
    private val videoFrameRecoveryGate = VideoFrameRecoveryGate()
    private val videoRecoveryPolicy = VideoStreamRecoveryPolicy(VIDEO_STALL_TIMEOUT_MS)
    private val h264RenderFpsMeter = FrameRateMeter()
    private val h264Decoder = H264SurfaceDecoder(
        onFrameRendered = ::onH264FrameRendered,
        onOutputFormatChanged = ::onH264OutputFormatChanged,
    )
    private var h264DisplayWidth = 0
    private var h264DisplayHeight = 0

    @Volatile
    private var isPreviewStarted = false

    @Volatile
    private var hasVideoFrame = false

    @Volatile
    private var hasAudioFrame = false

    private data class ResolutionOption(val width: Int, val height: Int) {
        override fun toString(): String = "${width} × ${height}"
    }

    private data class PreviewConfig(
        val fps: Int,
        val bitrate: Int,
        val resolution: ResolutionOption,
        val isARMixEnabled: Boolean,
        val previewMode: PreviewMode,
        val zoomRatio: Float?
    )

    private var currentConfig: PreviewConfig? = null

    private companion object {
        const val MAX_STREAM_RETRY_COUNT = 3
        const val FIRST_PACKET_TIMEOUT_MS = 1000L * 8
        const val STREAM_RETRY_DELAY_MS = 300L
        const val VIDEO_STALL_TIMEOUT_MS = 1000L * 5
        const val STOP_VIDEO_TIMEOUT_MS = 2_000L
        const val REMOTE_RESTART_COOLDOWN_MS = 5_000L
        const val RECOVERY_STABLE_TIME_MS = 10_000L
        val DEFAULT_RESOLUTION = ResolutionOption(2400, 1800)
        // Glass3 传感器方向为 270°，眼镜端自定义流会交换输出宽高后请求 Camera2。
        // The Glass3 sensor orientation is 270 degrees; the glasses swap the requested output width and height before calling Camera2 for a custom stream.
        // 这里只展示已实测可用、且交换后的 Camera2 纹理尺寸也由 HAL 支持的横屏尺寸。
        // Only show tested landscape sizes whose swapped Camera2 texture dimensions are also supported by the HAL.
        val SUPPORTED_RESOLUTIONS = listOf(
            ResolutionOption(2400, 1800),
            ResolutionOption(1920, 1080),
            ResolutionOption(1280, 720),
            ResolutionOption(648, 648),
            ResolutionOption(640, 480)
        )
    }

    @SuppressLint("SetTextI18n")
    private fun initView() {
        initResolutionSpinner()
        initPreviewModeSpinner()
        binding.spinnerSdk222Zoom.adapter = ArrayAdapter(
            this, R.layout.item_video_resolution_spinner,
            zoomPresets.map { it?.let { ratio -> "${ratio}×" } ?: getString(R.string.sdk222_phone_zoom_follow) }
        ).apply { setDropDownViewResource(R.layout.item_video_resolution_spinner_dropdown) }
        renderZoomState()
        initH264Surface()
        binding.btnStartPreview.setOnClickListener {
            validateAndStartPreview()
        }
        binding.glsurfaceview.setFpsListener { fps ->
            if (currentConfig?.previewMode == PreviewMode.NV21) {
                lifecycleScope.launch(Dispatchers.Main) { updateFps(fps) }
            }
        }
        audioTrack.play()
    }

    private fun initPreviewModeSpinner() {
        binding.spinnerPreviewMode.adapter = ArrayAdapter(
            this,
            R.layout.item_video_resolution_spinner,
            PreviewMode.values().toList()
        ).apply {
            setDropDownViewResource(R.layout.item_video_resolution_spinner_dropdown)
        }
    }

    private fun initH264Surface() {
        binding.h264SurfaceContainer.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            if (right > left && bottom > top) {
                applyH264SurfaceFitCenter(h264DisplayWidth, h264DisplayHeight)
            }
        }
        binding.h264SurfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                val config = currentConfig ?: return
                if (isPreviewStarted && config.previewMode == PreviewMode.H264) {
                    h264Decoder.start(holder.surface, config.resolution.width, config.resolution.height)
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                h264Decoder.stop()
            }
        })
    }

    private fun initResolutionSpinner() {
        val adapter = ArrayAdapter(
            this,
            R.layout.item_video_resolution_spinner,
            SUPPORTED_RESOLUTIONS
        ).apply {
            setDropDownViewResource(R.layout.item_video_resolution_spinner_dropdown)
        }
        binding.spinnerResolutionPreset.adapter = adapter
        val defaultIndex = SUPPORTED_RESOLUTIONS.indexOf(DEFAULT_RESOLUTION).takeIf { it >= 0 } ?: 0
        binding.spinnerResolutionPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                SUPPORTED_RESOLUTIONS.getOrNull(position)?.let(::fillResolutionInputs)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.spinnerResolutionPreset.setSelection(defaultIndex)
        fillResolutionInputs(DEFAULT_RESOLUTION)
    }

    private fun fillResolutionInputs(resolution: ResolutionOption) {
        binding.etResolutionWidth.setText(resolution.width.toString())
        binding.etResolutionHeight.setText(resolution.height.toString())
        binding.etResolutionWidth.error = null
        binding.etResolutionHeight.error = null
    }

    private fun formatFps(fps: Float): String {
        return "%.1f".format(fps)
    }

    /* ================= 页面切换 ================= */
    // ================= Page switching =================

    private fun switchPage(state: PageState) {
        currentState = state
        when (state) {
            PageState.CONFIG -> {
                binding.configContainer.visibility = View.VISIBLE
                binding.glsurfaceview.visibility = View.GONE
                binding.h264SurfaceContainer.visibility = View.GONE
                binding.tvFps.visibility = View.GONE
                binding.tvSdk222ZoomActual.visibility = View.GONE
            }

            PageState.PREVIEW -> {
                binding.configContainer.visibility = View.GONE
                val mode = currentConfig?.previewMode ?: PreviewMode.NV21
                binding.glsurfaceview.visibility = if (mode == PreviewMode.NV21) View.VISIBLE else View.GONE
                binding.h264SurfaceContainer.visibility = if (mode == PreviewMode.H264) View.VISIBLE else View.GONE
                binding.tvFps.visibility = View.VISIBLE
                binding.tvSdk222ZoomActual.visibility = View.VISIBLE
            }
        }
    }

    /* ================= 参数校验 ================= */
    // ================= Parameter validation =================
    private fun validateAndStartPreview() {
        clearErrors()

        val fps = parseFps() ?: return
        val bitrate = parseBitrate() ?: return
        val resolution = parseResolution() ?: return
        val isARMixEnabled = binding.swArMix.isChecked
        val previewMode = parsePreviewMode()

        checkP2pAndStartPreview(fps, bitrate, resolution, isARMixEnabled, previewMode)
    }

    /**
     * 视频控制命令走蓝牙，而视频帧走 Wi-Fi P2P。请求前使用 SDK 的 isConnect
     * Video control commands use Bluetooth, while video frames use Wi-Fi P2P. Before requesting a stream, use the SDK's isConnect
     * 检查实际 P2P 数据通道，避免系统 P2P 组仍存在、但 SDK 数据连接已失效时
     * to check the actual P2P data channel, so that an existing system P2P group with a stale SDK data connection does not
     * 进入黑屏页面并等待多轮首帧超时。
     * lead to a black preview and repeated first-frame timeouts.
     */
    private fun checkP2pAndStartPreview(
        fps: Int,
        bitrate: Int,
        resolution: ResolutionOption,
        isARMixEnabled: Boolean,
        previewMode: PreviewMode
    ) {
        if (!GlobalData.btConnectState.value) {
            toast(demoString(R.string.demo_bluetooth_disconnected_cannot_receive_audio_video))
            return
        }
        if (isCheckingP2p) {
            return
        }

        val p2pService = PSecuritySDK.getWifiP2PClientService()
        if (p2pService == null) {
            GlobalData.setP2pConnectState(false)
            toast(demoString(R.string.demo_reconnect_wi_fi_p2p))
            return
        }

        isCheckingP2p = true
        binding.btnStartPreview.isEnabled = false
        p2pService.isConnect { isConnected ->
            runOnUiThread {
                isCheckingP2p = false
                binding.btnStartPreview.isEnabled = true
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }

                GlobalData.setP2pConnectState(isConnected)
                if (!isConnected) {
                    Log.w(TAG, "start preview blocked: Wi-Fi P2P data channel disconnected")
                    if (currentState == PageState.PREVIEW) {
                        stopPreview()
                        switchPage(PageState.CONFIG)
                    }
                    toast(demoString(R.string.demo_reconnect_wi_fi_p2p))
                    return@runOnUiThread
                }

                startPreview(fps, bitrate, resolution, isARMixEnabled, previewMode)
            }
        }
    }

    private fun parseFps(): Int? {
        val fps = binding.etFrameRate.text.toString().toIntOrNull() ?: defaultFps
        return if (fps in 5..30) fps else {
            binding.etFrameRate.error = demoString(R.string.demo_frame_rate_must_be_between_5_and_30)
            toast(demoString(R.string.demo_invalid_frame_rate))
            null
        }
    }

    private fun parseBitrate(): Int? {
        val bitrate = binding.etBitrate.text.toString().toIntOrNull() ?: defaultBitrate
        return if (bitrate in 500_000..30_000_000) bitrate else {
            binding.etBitrate.error = demoString(R.string.demo_bitrate_must_be_between_500k_and_30m)
            toast(demoString(R.string.demo_invalid_bitrate))
            null
        }
    }

    private fun parseResolution(): ResolutionOption? {
        val width = binding.etResolutionWidth.text.toString().toIntOrNull()
        val height = binding.etResolutionHeight.text.toString().toIntOrNull()
        if (width == null || width !in 16..4096 || width % 2 != 0) {
            binding.etResolutionWidth.error = demoString(R.string.demo_enter_an_even_number_from_16_to_4096)
            toast(demoString(R.string.demo_invalid_resolution_width))
            return null
        }
        if (height == null || height !in 16..4096 || height % 2 != 0) {
            binding.etResolutionHeight.error = demoString(R.string.demo_enter_an_even_number_from_16_to_4096)
            toast(demoString(R.string.demo_invalid_resolution_height))
            return null
        }
        return ResolutionOption(width, height)
    }

    private fun parsePreviewMode(): PreviewMode {
        return binding.spinnerPreviewMode.selectedItem as? PreviewMode ?: PreviewMode.NV21
    }

    private fun clearErrors() {
        binding.etFrameRate.error = null
        binding.etBitrate.error = null
        binding.etResolutionWidth.error = null
        binding.etResolutionHeight.error = null
    }

    /* ================= 预览控制 ================= */
    // ================= Preview controls =================
    @SuppressLint("SetTextI18n")
    private fun startPreview(
        fps: Int,
        bitrate: Int,
        resolution: ResolutionOption,
        isARMixEnabled: Boolean,
        previewMode: PreviewMode
    ) {
        if (!GlobalData.btConnectState.value) {
            toast(demoString(R.string.demo_bluetooth_disconnected_cannot_receive_audio_video))
            return
        }
        if (currentState == PageState.PREVIEW && isPreviewStarted && startPreviewJob?.isActive == true) {
            Log.d(TAG, "startPreview ignored: stream request is starting")
            return
        }
        if (currentState == PageState.PREVIEW && isPreviewStarted && !hasVideoFrame) {
            Log.d(TAG, "startPreview ignored: waiting first video frame")
            return
        }
        val zoomRatio = zoomControls.requestRatio(zoomPresets.getOrNull(binding.spinnerSdk222Zoom.selectedItemPosition))
        currentConfig = PreviewConfig(fps, bitrate, resolution, isARMixEnabled, previewMode, zoomRatio)
        zoomControls.clearAppliedRatio()
        renderZoomState()
        PSecuritySDK.getWifiP2PClientService()?.setAutoDecodeH264ToNv21(previewMode == PreviewMode.NV21)
        resetFps()
        updateStreamState(demoString(R.string.demo_waiting_for_first_frame))
        switchPage(PageState.PREVIEW)
        Log.d(TAG, "---------startPreview()---${currentState}")
        startTime = System.currentTimeMillis()
        lastCallTime = startTime
        lastRenderedFrameTime = startTime
        lastAudioTime = startTime
        isGetVideo = false
        hasVideoFrame = false
        hasAudioFrame = false
        hasReportedVideoNoFrames = false
        isCheckingVideoRecovery = false
        remoteVideoRestartCount = 0
        lastRemoteVideoRestartTime = 0L
        localDecoderRestartUsed = false
        videoFrameEpoch++
        videoFrameRecoveryGate.beginDrain()
        tryCount = 0
        isPreviewStarted = true
        binding.glsurfaceview.releasePreview()
        binding.h264SurfaceView.alpha = if (previewMode == PreviewMode.H264) 0f else 1f
        if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
            audioTrack.play()
        }
        startDurationTimer()
        startPreviewJob?.cancel()
        startPreviewJob = lifecycleScope.launch {
            startStreamRequest("startPreview")
        }
    }

    private suspend fun startStreamRequest(reason: String) {
        val config = currentConfig ?: return
        val requestVersion = ++streamRequestVersion
        zoomStatusRequestVersion = -1
        videoRequestCount = 0
        audioRequestCount = 0
        isAudioRequestedForCurrentStream = false
        hasVideoFrame = false
        hasAudioFrame = false
        isGetVideo = false
        tryCount = 0
        lastAudioTime = System.currentTimeMillis()
        Log.d(TAG, "startStreamRequest: reason=$reason, version=$requestVersion, config=$config")
        binding.tvDuration.text = demoString(R.string.demo_requesting_audio_video_stream)

        awaitPreviousStreamStopAndDrain()
        delay(STREAM_RETRY_DELAY_MS)
        if (!isActiveRequest(requestVersion)) {
            return
        }
        resumeVideoAfterDrain(config)
        requestVideoStream(requestVersion, config, reason)
        Log.d(TAG, "startStreamRequest: wait first video frame before requesting audio")
    }

    private fun startDurationTimer() {
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                val duration = System.currentTimeMillis() - startTime
                val now = System.currentTimeMillis()
                if (now - lastCallTime > VIDEO_STALL_TIMEOUT_MS && isGetVideo) {
                    tryCount++
                    binding.tvDuration.text = demoString(R.string.demo_video_stream_interrupted, TimeUtils.formatDuration(duration))
                    if (tryCount >= 3) {
                        tryCount = 0
                        isGetVideo = false
                        handleVideoNoFrames("video stream stalled")
                    }
                } else if (
                    currentConfig?.previewMode == PreviewMode.H264 &&
                    hasVideoFrame &&
                    now - lastRenderedFrameTime > VIDEO_STALL_TIMEOUT_MS
                ) {
                    binding.tvDuration.text = demoString(R.string.demo_no_decoded_video, TimeUtils.formatDuration(duration))
                    handleVideoNoFrames("h264 packets received but decoder not rendering")
                } else {
                    binding.tvDuration.text = demoString(R.string.demo_duration, TimeUtils.formatDuration(duration))
                }
            }
        }
    }

    private fun requestVideoStream(requestVersion: Int, config: PreviewConfig, reason: String) {
        val deviceInfoService = PSecuritySDK.getAbsDeviceInfoService()
        if (deviceInfoService == null) {
            handleStreamFailed(requestVersion, demoString(R.string.demo_device_information_service_is_not_initialized_cannot_request))
            return
        }
        videoRequestCount++
        hasVideoFrame = false
        isGetVideo = false
        val attempt = videoRequestCount
        val param = buildVideoStreamParam(config)
        zoomStatusRequestVersion = requestVersion
        Log.d(TAG, "requestVideoStream: attempt=$attempt, reason=$reason, config=$config")
        deviceInfoService.requestVideoStream(VIDEO_TAG, videoStreamParam = param) callback@{ isSuccess ->
            Log.d(TAG, "requestVideoStream callback: success=$isSuccess, attempt=$attempt, version=$requestVersion")
            if (!isActiveRequest(requestVersion)) {
                return@callback
            }
            if (!isSuccess) {
                handleVideoNoFrames("video request callback false")
            }
        }
        startVideoWatchdog(requestVersion)
    }

    private fun requestAudioStream(requestVersion: Int, reason: String) {
        val deviceInfoService = PSecuritySDK.getAbsDeviceInfoService()
        if (deviceInfoService == null) {
            handleStreamFailed(requestVersion, demoString(R.string.demo_device_information_service_is_not_initialized_cannot_request_2))
            return
        }
        audioRequestCount++
        hasAudioFrame = false
        val attempt = audioRequestCount
        Log.d(TAG, "requestAudioStream: attempt=$attempt, reason=$reason")
        deviceInfoService.requestAudioStream(AUDIO_TAG) callback@{ isSuccess ->
            Log.d(TAG, "requestAudioStream callback: success=$isSuccess, attempt=$attempt, version=$requestVersion")
            if (!isActiveRequest(requestVersion)) {
                return@callback
            }
            if (!isSuccess) {
                retryAudioStream(requestVersion, "audio request callback false")
            }
        }
        startAudioWatchdog(requestVersion)
    }

    private fun buildVideoStreamParam(config: PreviewConfig): GlassVideoStreamParam {
        return GlassVideoStreamParam().apply {
            width = config.resolution.width
            height = config.resolution.height
            fps = config.fps
            bitrate = config.bitrate
            isARMixEnabled = config.isARMixEnabled
            // Leave null for old/unknown peers. Never infer support from local DTO defaults.
            zoomRatio = zoomControls.requestRatio(config.zoomRatio)
        }
    }

    private fun startVideoWatchdog(requestVersion: Int) {
        videoWatchdogJob?.cancel()
        videoWatchdogJob = lifecycleScope.launch {
            delay(FIRST_PACKET_TIMEOUT_MS)
            if (isActiveRequest(requestVersion) && !hasVideoFrame) {
                handleVideoNoFrames("no first video frame")
            }
        }
    }

    /**
     * 无画面时先定位故障层级，再执行最小恢复动作，避免反复 stop/start 冲击控制链路。
     * When no image is displayed, first identify the failing layer and apply the smallest recovery action to avoid stressing the control channel with repeated stop/start calls.
     */
    private fun handleVideoNoFrames(reason: String) {
        if (!isPreviewStarted || isCheckingVideoRecovery || videoRetryJob?.isActive == true) return
        Log.w(TAG, "handleVideoNoFrames: reason=$reason")
        isCheckingVideoRecovery = true
        PSecuritySDK.getWifiP2PClientService()?.isConnect { p2pConnected ->
            runOnUiThread {
                isCheckingVideoRecovery = false
                if (!isPreviewStarted) return@runOnUiThread
                GlobalData.setP2pConnectState(p2pConnected)
                val now = System.currentTimeMillis()
                val action = videoRecoveryPolicy.decide(
                    bluetoothConnected = GlobalData.btConnectState.value,
                    p2pConnected = p2pConnected,
                    isH264Mode = currentConfig?.previewMode == PreviewMode.H264,
                    hasReceivedPacket = hasVideoFrame,
                    lastPacketAgeMs = now - lastCallTime,
                    lastRenderedFrameAgeMs = now - lastRenderedFrameTime,
                    remoteRestartAvailable = remoteVideoRestartCount < 1 &&
                        now - lastRemoteVideoRestartTime >= REMOTE_RESTART_COOLDOWN_MS,
                    localDecoderRestartAvailable = !localDecoderRestartUsed,
                )
                Log.w(TAG, "video recovery decision: reason=$reason, action=$action")
                when (action) {
                    VideoStreamRecoveryPolicy.Action.REPORT_BLUETOOTH_DISCONNECTED ->
                        reportVideoNoData(demoString(R.string.demo_no_video_data_bluetooth_disconnected))
                    VideoStreamRecoveryPolicy.Action.REPORT_P2P_DISCONNECTED ->
                        reportVideoNoData(demoString(R.string.demo_no_video_data_wi_fi_p2p_disconnected))
                    VideoStreamRecoveryPolicy.Action.RESTART_LOCAL_DECODER -> restartLocalH264Decoder()
                    VideoStreamRecoveryPolicy.Action.RESTART_REMOTE_STREAM ->
                        restartRemoteVideoStreamOnce(reason)
                    VideoStreamRecoveryPolicy.Action.REPORT_NO_DATA ->
                        reportVideoNoData(demoString(R.string.demo_no_video_data_go_back_and_reopen_the))
                    VideoStreamRecoveryPolicy.Action.NONE -> Unit
                }
            }
        } ?: runOnUiThread {
            isCheckingVideoRecovery = false
            GlobalData.setP2pConnectState(false)
            reportVideoNoData(demoString(R.string.demo_no_video_data_wi_fi_p2p_disconnected))
        }
    }

    private fun reportVideoNoData(message: String) {
        updateStreamState(demoString(R.string.demo_no_data))
        binding.tvDuration.text = message
        if (!hasReportedVideoNoFrames) {
            hasReportedVideoNoFrames = true
            toast(message)
        }
    }

    private fun restartLocalH264Decoder() {
        val config = currentConfig ?: return
        val surface = binding.h264SurfaceView.holder.surface
        if (config.previewMode != PreviewMode.H264 || !surface.isValid) {
            reportVideoNoData(demoString(R.string.demo_no_decoded_video_go_back_and_reopen_the))
            return
        }
        localDecoderRestartUsed = true
        videoFrameEpoch++
        videoFrameRecoveryGate.beginDrain()
        binding.h264SurfaceView.alpha = 0f
        lastRenderedFrameTime = System.currentTimeMillis()
        Log.w(TAG, "restart local H264 decoder; keep remote stream running")
        h264Decoder.start(surface, config.resolution.width, config.resolution.height)
        videoFrameRecoveryGate.resume(VideoFrameRecoveryGate.Mode.H264)
        updateStreamState(demoString(R.string.demo_restarting_local_decoder))
    }

    private fun restartRemoteVideoStreamOnce(reason: String) {
        val config = currentConfig ?: return
        val requestVersion = streamRequestVersion
        if (!isActiveRequest(requestVersion) || videoRetryJob?.isActive == true) return
        remoteVideoRestartCount++
        lastRemoteVideoRestartTime = System.currentTimeMillis()
        beginRemoteVideoDrain()
        videoRetryJob = lifecycleScope.launch {
            Log.w(TAG, "restart remote video stream once: reason=$reason")
            updateStreamState(demoString(R.string.demo_recovering))
            val stopCompleted = stopRemoteVideoStreamAndAwait()
            Log.d(TAG, "stopVideoStream before controlled restart: completed=$stopCompleted")
            delay(STREAM_RETRY_DELAY_MS)
            if (isActiveRequest(requestVersion)) {
                hasVideoFrame = false
                isGetVideo = false
                lastCallTime = System.currentTimeMillis()
                lastRenderedFrameTime = lastCallTime
                resumeVideoAfterDrain(config)
                requestVideoStream(requestVersion, config, "controlled recovery: $reason")
            }
        }
    }

    /**
     * 停止旧流后关闭帧入口并清空本地渲染状态，排空期间的残留回调会被直接丢弃。
     * After stopping the old stream, close frame intake and clear local rendering state; discard residual callbacks while draining.
     */
    private fun beginRemoteVideoDrain() {
        videoFrameEpoch++
        videoFrameRecoveryGate.beginDrain()
        videoWatchdogJob?.cancel()
        hasVideoFrame = false
        isGetVideo = false
        when (currentConfig?.previewMode) {
            PreviewMode.H264 -> {
                h264Decoder.stop()
                binding.h264SurfaceView.alpha = 0f
            }

            PreviewMode.NV21 -> binding.glsurfaceview.releasePreview()
            null -> Unit
        }
    }

    /**
     * 为新请求创建干净的解码状态；H.264 仍会由门控等待新流 IDR 后才显示。
     * Create clean decoder state for the new request; the H.264 gate still waits for the new stream's IDR frame before displaying it.
     */
    private fun resumeVideoAfterDrain(config: PreviewConfig) {
        videoFrameRecoveryGate.resume(config.previewMode.toRecoveryGateMode())
        if (config.previewMode == PreviewMode.H264) {
            h264DisplayWidth = config.resolution.width
            h264DisplayHeight = config.resolution.height
            applyH264SurfaceFitCenter(config.resolution.width, config.resolution.height)
            val surface = binding.h264SurfaceView.holder.surface
            if (surface.isValid) {
                h264Decoder.start(surface, config.resolution.width, config.resolution.height)
            }
        }
    }

    private suspend fun stopRemoteVideoStreamAndAwait(): Boolean {
        val service = PSecuritySDK.getAbsDeviceInfoService() ?: return false
        return withTimeoutOrNull(STOP_VIDEO_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                service.stopVideoStream(VIDEO_TAG) { success ->
                    if (continuation.isActive) continuation.resume(success)
                }
            }
        } ?: false
    }

    private fun startAudioWatchdog(requestVersion: Int) {
        audioWatchdogJob?.cancel()
        audioWatchdogJob = lifecycleScope.launch {
            delay(FIRST_PACKET_TIMEOUT_MS)
            if (isActiveRequest(requestVersion) && !hasAudioFrame) {
                retryAudioStream(requestVersion, "no first audio packet")
            }
        }
    }

    private fun retryAudioStream(requestVersion: Int, reason: String) {
        if (!isActiveRequest(requestVersion) || hasAudioFrame || audioRetryJob?.isActive == true) {
            return
        }
        if (audioRequestCount >= MAX_STREAM_RETRY_COUNT) {
            Log.w(TAG, "retryAudioStream: audio stream failed after $audioRequestCount attempts")
            toast(demoString(R.string.demo_failed_to_start_audio_go_back_and_try))
            return
        }
        audioRetryJob = lifecycleScope.launch {
            Log.w(TAG, "retryAudioStream: reason=$reason, nextAttempt=${audioRequestCount + 1}")
            PSecuritySDK.getAbsDeviceInfoService()?.stopAudioStream(AUDIO_TAG) {}
            delay(STREAM_RETRY_DELAY_MS)
            if (isActiveRequest(requestVersion) && !hasAudioFrame) {
                requestAudioStream(requestVersion, reason)
            }
        }
    }

    private fun handleStreamFailed(requestVersion: Int, msg: String) {
        if (!isActiveRequest(requestVersion)) {
            return
        }
        Log.w(TAG, "handleStreamFailed: $msg")
        toast(msg)
        lifecycleScope.launch {
            stopPreview()
            switchPage(PageState.CONFIG)
        }
    }

    private fun isActiveRequest(requestVersion: Int): Boolean {
        return isPreviewStarted && currentState == PageState.PREVIEW && streamRequestVersion == requestVersion
    }

    /**
     * 立即发起远端停止，并保留可等待任务。使用 UNDISPATCHED 确保退出方法返回前，
     * Start the remote stop immediately and retain a task that can be awaited. UNDISPATCHED ensures that, before the exit method returns,
     * stopVideoStream/stopAudioStream 已经真正调用到 SDK。
     * stopVideoStream/stopAudioStream have actually been called on the SDK.
     */
    private fun scheduleCurrentStreamStop() {
        val previousStop = pendingStreamStopJob
        pendingStreamStopJob = lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            previousStop?.join()
            val results = coroutineScope {
                listOf(
                    async { stopRemoteVideoStreamAndAwait() },
                    async { stopRemoteAudioStreamAndAwait() },
                ).awaitAll()
            }
            Log.d(TAG, "remote stream stop completed: video=${results[0]}, audio=${results[1]}")
        }
    }

    /**
     * 新预览必须等旧流停止回调结束；随后仍保持帧入口关闭，由调用方完成排空延迟。
     * A new preview must wait for the previous stream's stop callback; keep frame intake closed afterward while the caller completes the drain delay.
     */
    private suspend fun awaitPreviousStreamStopAndDrain() {
        val pendingStop = pendingStreamStopJob
        pendingStop?.join()
        if (pendingStreamStopJob === pendingStop) pendingStreamStopJob = null
    }

    private suspend fun stopRemoteAudioStreamAndAwait(): Boolean {
        val service = PSecuritySDK.getAbsDeviceInfoService() ?: return false
        return withTimeoutOrNull(STOP_VIDEO_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                service.stopAudioStream(AUDIO_TAG) { success ->
                    if (continuation.isActive) continuation.resume(success)
                }
            }
        } ?: false
    }

    @SuppressLint("SetTextI18n")
    private fun stopPreview() {
        isPreviewStarted = false
        zoomStatusRequestVersion = -1
        zoomControls.clearAppliedRatio()
        renderZoomState()
        streamRequestVersion++
        startPreviewJob?.cancel()
        videoWatchdogJob?.cancel()
        audioWatchdogJob?.cancel()
        videoRetryJob?.cancel()
        audioRetryJob?.cancel()
        scheduleCurrentStreamStop()
        videoRequestCount = 0
        audioRequestCount = 0
        isGetVideo = false
        hasVideoFrame = false
        hasAudioFrame = false
        isCheckingVideoRecovery = false
        remoteVideoRestartCount = 0
        lastRemoteVideoRestartTime = 0L
        localDecoderRestartUsed = false
        tryCount = 0
        timerJob?.cancel()
        timerJob = null
        audioTrack.pause()
        audioTrack.flush()
        videoFrameEpoch++
        videoFrameRecoveryGate.beginDrain()
        binding.glsurfaceview.releasePreview()
        binding.h264SurfaceView.alpha = 0f
        h264Decoder.stop()
        PSecuritySDK.getWifiP2PClientService()?.setAutoDecodeH264ToNv21(true)
        h264RenderFpsMeter.reset()
        val duration = System.currentTimeMillis() - startTime
        binding.tvDuration.text = demoString(R.string.demo_final_duration, TimeUtils.formatDuration(duration))
        Log.d(TAG, "---------stopPreview=${currentState}")
    }

    /* ================= NV21 → GLSurfaceView ================= */
    private val zoomStatusListener = object : ICommonMessage {
        override fun onMessage(message: String, type: Int) {
            if (type != MessageType.BT && type != MessageType.P2P) return
            if (!isPreviewStarted || !GlobalData.isGlassConnect()) return
            val epoch = zoomConnectionEpoch
            val requestVersion = zoomStatusRequestVersion
            if (requestVersion < 0) return
            val state = VideoStreamZoomControls.parsePeerState(message) ?: return
            runOnUiThread {
                if (isDestroyed || epoch != zoomConnectionEpoch || !isPreviewStarted || !GlobalData.isGlassConnect()) return@runOnUiThread
                if (requestVersion != streamRequestVersion || requestVersion != zoomStatusRequestVersion) return@runOnUiThread
                zoomControls.update(state)
                renderZoomState()
            }
        }
    }

    private fun renderZoomState() {
        val state = zoomControls.peerState
        binding.spinnerSdk222Zoom.isEnabled = state.supportsFloatZoom
        binding.tvSdk222ZoomCapability.setText(when {
            state.capabilities == null -> R.string.sdk222_phone_zoom_unknown
            state.supportsFloatZoom -> R.string.sdk222_phone_zoom_supported
            else -> R.string.sdk222_phone_zoom_unsupported
        })
        val actual = state.appliedRatio?.let { getString(R.string.sdk222_phone_zoom_actual, it) }
            ?: getString(R.string.sdk222_phone_zoom_actual_unknown)
        binding.tvSdk222ZoomActual.text = currentConfig?.zoomRatio?.let {
            getString(R.string.sdk222_phone_zoom_requested, it, actual)
        } ?: actual
    }

    private val nv21Listener = object : IMessageListener {
        override fun onNv21Data(data: ByteArray, width: Int, height: Int) {
            if (currentState != PageState.PREVIEW || !isPreviewStarted) return
            if (currentConfig?.previewMode != PreviewMode.NV21) return
            if (!videoFrameRecoveryGate.shouldAcceptNv21()) return
            val callbackEpoch = videoFrameEpoch
            val ownedFrame = data.copyOf()
            onVideoFrameReceived("NV21", width, height)
            mainScope.launch {
                if (
                    callbackEpoch == videoFrameEpoch &&
                    videoFrameRecoveryGate.shouldAcceptNv21() &&
                    currentState == PageState.PREVIEW &&
                    isPreviewStarted
                ) {
                    binding.glsurfaceview.setPreviewData(ownedFrame, width, height)
                }
            }
        }

        override fun onVideoH264Stream(buffer: ByteBuffer) {
            if (currentState != PageState.PREVIEW || !isPreviewStarted) return
            if (currentConfig?.previewMode != PreviewMode.H264) return
            val config = currentConfig ?: return
            if (!videoFrameRecoveryGate.shouldAcceptH264(buffer.duplicate())) return
            if (H264NalUnitInspector.containsFrame(buffer.duplicate())) {
                onVideoFrameReceived("H264", config.resolution.width, config.resolution.height)
            }
            h264Decoder.queueAccessUnit(buffer.duplicate())
        }

        override fun onAudioStream(buffer: ByteBuffer) {
            if (currentState != PageState.PREVIEW || !isPreviewStarted) return
            val audioData = ByteArray(buffer.remaining())
            audioRequestCount = 0
            buffer.get(audioData)
            handleAudioBuffer(audioData, "onAudioStream")
        }

        override fun onClassicBTAudioStream(buffer: ByteArray) {
            handleAudioBuffer(buffer, "onClassicBTAudioStream")
        }

        override fun onBTStreamDataReceived(tag: String, data: ByteArray, clientId: String) {
            if (tag == AUDIO_TAG) {
                handleAudioBuffer(data, "onBTStreamDataReceived")
            }
        }

        private fun handleAudioBuffer(buffer: ByteArray, source: String) {
            if (currentState != PageState.PREVIEW || !isPreviewStarted) return
            lastAudioTime = System.currentTimeMillis()
            if (!hasAudioFrame) {
                Log.d(TAG, "$source: first audio packet, size=${buffer.size}")
                hasAudioFrame = true
                audioRequestCount = 0
                audioWatchdogJob?.cancel()
            }
            audioTrack.write(buffer, 0, buffer.size)
        }
    }

    private fun onVideoFrameReceived(source: String, width: Int, height: Int) {
        val now = System.currentTimeMillis()
        tryCount = 0
        lastCallTime = now
        isGetVideo = true
        hasReportedVideoNoFrames = false
        if (lastRemoteVideoRestartTime > 0L && now - lastRemoteVideoRestartTime >= RECOVERY_STABLE_TIME_MS) {
            remoteVideoRestartCount = 0
        }
        updateStreamState(demoString(R.string.demo_receiving_2))
        if (!hasVideoFrame) {
            Log.d(TAG, "$source first video frame, width=$width, height=$height")
            hasVideoFrame = true
            videoRequestCount = 0
            videoWatchdogJob?.cancel()
            requestAudioStreamAfterFirstVideoFrame()
        }
    }

    private fun onH264FrameRendered() {
        lastRenderedFrameTime = System.currentTimeMillis()
        localDecoderRestartUsed = false
        runOnUiThread {
            if (binding.h264SurfaceView.alpha != 1f) binding.h264SurfaceView.alpha = 1f
        }
        h264RenderFpsMeter.recordFrame()?.let(::updateFps)
    }

    private fun PreviewMode.toRecoveryGateMode(): VideoFrameRecoveryGate.Mode =
        if (this == PreviewMode.H264) VideoFrameRecoveryGate.Mode.H264 else VideoFrameRecoveryGate.Mode.NV21

    private fun onH264OutputFormatChanged(geometry: DecodedVideoGeometry) {
        runOnUiThread {
            if (currentConfig?.previewMode != PreviewMode.H264) return@runOnUiThread
            applyH264SurfaceFitCenter(geometry.displayWidth, geometry.displayHeight)
        }
    }

    private fun applyH264SurfaceFitCenter(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        h264DisplayWidth = width
        h264DisplayHeight = height
        val containerWidth = binding.h264SurfaceContainer.width
        val containerHeight = binding.h264SurfaceContainer.height
        if (containerWidth <= 0 || containerHeight <= 0) return
        val fitted = FitCenterScaleCalculator.calculateSize(
            frameWidth = width,
            frameHeight = height,
            containerWidth = containerWidth,
            containerHeight = containerHeight,
        )
        val params = binding.h264SurfaceView.layoutParams as FrameLayout.LayoutParams
        if (params.width == fitted.width && params.height == fitted.height && params.gravity == Gravity.CENTER) {
            return
        }
        params.width = fitted.width
        params.height = fitted.height
        params.gravity = Gravity.CENTER
        binding.h264SurfaceView.layoutParams = params
        binding.h264SurfaceView.requestLayout()
        Log.i(
            TAG,
            "H264 FIT_CENTER source=${width}x$height, " +
                "container=${containerWidth}x$containerHeight, " +
                "surface=${fitted.width}x${fitted.height}",
        )
    }

    @SuppressLint("SetTextI18n")
    private fun resetFps() {
        h264RenderFpsMeter.reset()
        binding.tvFps.text = demoString(R.string.demo_frame_rate_fps)
    }

    @SuppressLint("SetTextI18n")
    private fun updateFps(fps: Float) {
        runOnUiThread { binding.tvFps.text = demoString(R.string.demo_frame_rate_fps_2, formatFps(fps)) }
    }

    @SuppressLint("SetTextI18n")
    private fun updateStreamState(videoState: String) {
        val bluetooth = if (GlobalData.btConnectState.value) demoString(R.string.demo_connected) else demoString(R.string.demo_disconnected)
        val p2p = if (GlobalData.p2pConnectState.value) demoString(R.string.demo_connected) else demoString(R.string.demo_disconnected)
        runOnUiThread {
            binding.tvStreamState.text = demoString(R.string.demo_bluetooth_p2p_video, bluetooth, p2p, videoState)
        }
    }

    private fun requestAudioStreamAfterFirstVideoFrame() {
        val requestVersion = streamRequestVersion
        if (!isActiveRequest(requestVersion) || isAudioRequestedForCurrentStream) {
            return
        }
        isAudioRequestedForCurrentStream = true
        Log.d(TAG, "requestAudioStreamAfterFirstVideoFrame: version=$requestVersion")
        requestAudioStream(requestVersion, "first video frame received")
    }

    /* ================= 生命周期 ================= */
    // ================= Lifecycle =================

    override fun onBackPressed() {
        if (currentState == PageState.PREVIEW) {
            stopPreview()
            switchPage(PageState.CONFIG)
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        if (currentState == PageState.PREVIEW) {
            stopPreview()
        }
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        super.onDestroy()
        PSecuritySDK.getWifiP2PClientService()?.setAutoDecodeH264ToNv21(true)
        h264Decoder.stop()
        audioTrack.stop()
        audioTrack.release()
        PSecuritySDK.getMessageService()?.removeMessageListener(nv21Listener)
        MessageReceiver.getMessageAction().unInjectIMessage(zoomStatusListener)
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d(TAG, "--------onResume--currentState=${currentState}")
        if (currentState == PageState.PREVIEW) {
            checkP2pAndStartPreview(
                parseFps() ?: defaultFps,
                parseBitrate() ?: defaultBitrate,
                currentConfig?.resolution ?: parseResolution() ?: DEFAULT_RESOLUTION,
                binding.swArMix.isChecked,
                parsePreviewMode()
            )
        }
    }

    private fun toast(msg: String) {
        lifecycleScope.launch {
            Toast.makeText(this@VideoReceiveActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

}
