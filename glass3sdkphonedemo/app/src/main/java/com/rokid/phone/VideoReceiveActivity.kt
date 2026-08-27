package com.rokid.phone

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
import com.rokid.phone.video.H264SurfaceDecoder
import com.rokid.phone.video.DecodedVideoGeometry
import com.rokid.phone.video.VideoStreamRecoveryPolicy
import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.security.phone.sdk.api.msg.listener.IMessageListener
import com.rokid.security.phone.sdk.base.utils.other.mainScope
import com.rokid.security.sdk.base.common.GlassVideoStreamParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoReceiveBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initView()
        switchPage(PageState.CONFIG)
        PSecuritySDK.getMessageService()?.addMessageListener(nv21Listener)
    }

    private val audioTrack = AudioTrack(
        AudioManager.STREAM_MUSIC,                // 音频流类型
        16000,                                    // 采样率（必须一致）
        AudioFormat.CHANNEL_OUT_MONO,             // 声道配置（与录音一致）
        AudioFormat.ENCODING_PCM_16BIT,           // 编码格式（必须一致）
        AudioTrack.getMinBufferSize(              // 合理的缓冲区大小
            16000,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ),
        AudioTrack.MODE_STREAM                    // 流式模式（适合实时播放）
    )

    private enum class PageState { CONFIG, PREVIEW }

    /**
     * 两种模式在网络中传输的都是眼镜端编码后的 H.264 压缩码流，区别在手机端：
     *
     * NV21：手机 SDK 内部解码 H.264 并转换成 NV21，再由 Demo 的 GLSurfaceView 渲染。
     * 适合需要逐帧算法、截图或像素处理的场景，但解码、格式转换和内存复制开销较高。
     *
     * H264：Demo 将收到的 H.264 码流交给 MediaCodec 硬解并直接输出到 SurfaceView。
     * 适合仅做实时预览的场景，延迟和资源占用更低，但不向业务层提供 NV21 像素帧。
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
        val previewMode: PreviewMode
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
        // 这里只展示已实测可用、且交换后的 Camera2 纹理尺寸也由 HAL 支持的横屏尺寸。
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

    private fun switchPage(state: PageState) {
        currentState = state
        when (state) {
            PageState.CONFIG -> {
                binding.configContainer.visibility = View.VISIBLE
                binding.glsurfaceview.visibility = View.GONE
                binding.h264SurfaceContainer.visibility = View.GONE
                binding.tvFps.visibility = View.GONE
            }

            PageState.PREVIEW -> {
                binding.configContainer.visibility = View.GONE
                val mode = currentConfig?.previewMode ?: PreviewMode.NV21
                binding.glsurfaceview.visibility = if (mode == PreviewMode.NV21) View.VISIBLE else View.GONE
                binding.h264SurfaceContainer.visibility = if (mode == PreviewMode.H264) View.VISIBLE else View.GONE
                binding.tvFps.visibility = View.VISIBLE
            }
        }
    }

    /* ================= 参数校验 ================= */
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
     * 检查实际 P2P 数据通道，避免系统 P2P 组仍存在、但 SDK 数据连接已失效时
     * 进入黑屏页面并等待多轮首帧超时。
     */
    private fun checkP2pAndStartPreview(
        fps: Int,
        bitrate: Int,
        resolution: ResolutionOption,
        isARMixEnabled: Boolean,
        previewMode: PreviewMode
    ) {
        if (!GlobalData.btConnectState.value) {
            toast("蓝牙未连接，无法拉取音视频流")
            return
        }
        if (isCheckingP2p) {
            return
        }

        val p2pService = PSecuritySDK.getWifiP2PClientService()
        if (p2pService == null) {
            GlobalData.setP2pConnectState(false)
            toast("请重新连接 Wi-Fi P2P")
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
                    toast("请重新连接 Wi-Fi P2P")
                    return@runOnUiThread
                }

                startPreview(fps, bitrate, resolution, isARMixEnabled, previewMode)
            }
        }
    }

    private fun parseFps(): Int? {
        val fps = binding.etFrameRate.text.toString().toIntOrNull() ?: defaultFps
        return if (fps in 5..30) fps else {
            binding.etFrameRate.error = "帧率范围 5~30"
            toast("帧率不合法")
            null
        }
    }

    private fun parseBitrate(): Int? {
        val bitrate = binding.etBitrate.text.toString().toIntOrNull() ?: defaultBitrate
        return if (bitrate in 500_000..30_000_000) bitrate else {
            binding.etBitrate.error = "码率范围 500k~30M"
            toast("码率不合法")
            null
        }
    }

    private fun parseResolution(): ResolutionOption? {
        val width = binding.etResolutionWidth.text.toString().toIntOrNull()
        val height = binding.etResolutionHeight.text.toString().toIntOrNull()
        if (width == null || width !in 16..4096 || width % 2 != 0) {
            binding.etResolutionWidth.error = "请输入 16~4096 的偶数"
            toast("分辨率宽度不合法")
            return null
        }
        if (height == null || height !in 16..4096 || height % 2 != 0) {
            binding.etResolutionHeight.error = "请输入 16~4096 的偶数"
            toast("分辨率高度不合法")
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
    @SuppressLint("SetTextI18n")
    private fun startPreview(
        fps: Int,
        bitrate: Int,
        resolution: ResolutionOption,
        isARMixEnabled: Boolean,
        previewMode: PreviewMode
    ) {
        if (!GlobalData.btConnectState.value) {
            toast("蓝牙未连接，无法拉取音视频流")
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
        currentConfig = PreviewConfig(fps, bitrate, resolution, isARMixEnabled, previewMode)
        PSecuritySDK.getWifiP2PClientService()?.setAutoDecodeH264ToNv21(previewMode == PreviewMode.NV21)
        resetFps()
        updateStreamState("等待首帧")
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
        tryCount = 0
        isPreviewStarted = true
        if (previewMode == PreviewMode.H264 && binding.h264SurfaceView.holder.surface.isValid) {
            h264DisplayWidth = resolution.width
            h264DisplayHeight = resolution.height
            applyH264SurfaceFitCenter(resolution.width, resolution.height)
            h264Decoder.start(binding.h264SurfaceView.holder.surface, resolution.width, resolution.height)
        }
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
        videoRequestCount = 0
        audioRequestCount = 0
        isAudioRequestedForCurrentStream = false
        hasVideoFrame = false
        hasAudioFrame = false
        isGetVideo = false
        tryCount = 0
        lastAudioTime = System.currentTimeMillis()
        Log.d(TAG, "startStreamRequest: reason=$reason, version=$requestVersion, config=$config")
        binding.tvDuration.text = "正在请求音视频流..."


        delay(STREAM_RETRY_DELAY_MS)
        if (!isActiveRequest(requestVersion)) {
            return
        }
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
                    binding.tvDuration.text = "视频流中断:${TimeUtils.formatDuration(duration)}"
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
                    binding.tvDuration.text = "视频解码无画面:${TimeUtils.formatDuration(duration)}"
                    handleVideoNoFrames("h264 packets received but decoder not rendering")
                } else {
                    binding.tvDuration.text = "时长:${TimeUtils.formatDuration(duration)}"
                }
            }
        }
    }

    private fun requestVideoStream(requestVersion: Int, config: PreviewConfig, reason: String) {
        val deviceInfoService = PSecuritySDK.getAbsDeviceInfoService()
        if (deviceInfoService == null) {
            handleStreamFailed(requestVersion, "设备信息服务未初始化，无法请求视频流")
            return
        }
        videoRequestCount++
        hasVideoFrame = false
        isGetVideo = false
        val attempt = videoRequestCount
        val param = buildVideoStreamParam(config)
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
            handleStreamFailed(requestVersion, "设备信息服务未初始化，无法请求音频流")
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

    /** 无画面时先定位故障层级，再执行最小恢复动作，避免反复 stop/start 冲击控制链路。 */
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
                        reportVideoNoData("视频流无数据，蓝牙已断开")
                    VideoStreamRecoveryPolicy.Action.REPORT_P2P_DISCONNECTED ->
                        reportVideoNoData("视频流无数据，Wi-Fi P2P 已断开")
                    VideoStreamRecoveryPolicy.Action.RESTART_LOCAL_DECODER -> restartLocalH264Decoder()
                    VideoStreamRecoveryPolicy.Action.RESTART_REMOTE_STREAM ->
                        restartRemoteVideoStreamOnce(reason)
                    VideoStreamRecoveryPolicy.Action.REPORT_NO_DATA ->
                        reportVideoNoData("视频流无数据，请返回后重新进入预览")
                    VideoStreamRecoveryPolicy.Action.NONE -> Unit
                }
            }
        } ?: runOnUiThread {
            isCheckingVideoRecovery = false
            GlobalData.setP2pConnectState(false)
            reportVideoNoData("视频流无数据，Wi-Fi P2P 已断开")
        }
    }

    private fun reportVideoNoData(message: String) {
        updateStreamState("无数据")
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
            reportVideoNoData("视频解码无画面，请返回后重新进入预览")
            return
        }
        localDecoderRestartUsed = true
        lastRenderedFrameTime = System.currentTimeMillis()
        Log.w(TAG, "restart local H264 decoder; keep remote stream running")
        h264Decoder.start(surface, config.resolution.width, config.resolution.height)
        updateStreamState("重启本地解码器")
    }

    private fun restartRemoteVideoStreamOnce(reason: String) {
        val config = currentConfig ?: return
        val requestVersion = streamRequestVersion
        if (!isActiveRequest(requestVersion) || videoRetryJob?.isActive == true) return
        remoteVideoRestartCount++
        lastRemoteVideoRestartTime = System.currentTimeMillis()
        videoRetryJob = lifecycleScope.launch {
            Log.w(TAG, "restart remote video stream once: reason=$reason")
            updateStreamState("正在恢复")
            val stopCompleted = stopRemoteVideoStreamAndAwait()
            Log.d(TAG, "stopVideoStream before controlled restart: completed=$stopCompleted")
            delay(STREAM_RETRY_DELAY_MS)
            if (isActiveRequest(requestVersion)) {
                hasVideoFrame = false
                isGetVideo = false
                lastCallTime = System.currentTimeMillis()
                lastRenderedFrameTime = lastCallTime
                requestVideoStream(requestVersion, config, "controlled recovery: $reason")
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
            toast("音频流启动失败，可返回后重试")
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

    private fun stopCurrentStream() {
        PSecuritySDK.getAbsDeviceInfoService()?.stopVideoStream(VIDEO_TAG) { isSuccess ->
            Log.d(TAG, "stopVideoStream before request: $isSuccess")
        }
        PSecuritySDK.getAbsDeviceInfoService()?.stopAudioStream(AUDIO_TAG) { isSuccess ->
            Log.d(TAG, "stopAudioStream before request: $isSuccess")
        }
    }

    @SuppressLint("SetTextI18n")
    private fun stopPreview() {
        isPreviewStarted = false
        streamRequestVersion++
        startPreviewJob?.cancel()
        videoWatchdogJob?.cancel()
        audioWatchdogJob?.cancel()
        videoRetryJob?.cancel()
        audioRetryJob?.cancel()
        stopCurrentStream()
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
        h264Decoder.stop()
        PSecuritySDK.getWifiP2PClientService()?.setAutoDecodeH264ToNv21(true)
        h264RenderFpsMeter.reset()
        val duration = System.currentTimeMillis() - startTime
        binding.tvDuration.text = "结束时长:${TimeUtils.formatDuration(duration)}"
        Log.d(TAG, "---------stopPreview=${currentState}")
    }

    /* ================= NV21 → GLSurfaceView ================= */
    private val nv21Listener = object : IMessageListener {
        override fun onNv21Data(data: ByteArray, width: Int, height: Int) {
            if (currentState != PageState.PREVIEW || !isPreviewStarted) return
            if (currentConfig?.previewMode != PreviewMode.NV21) return
            onVideoFrameReceived("NV21", width, height)
            mainScope.launch {
                binding.glsurfaceview.setPreviewData(data, width, height)
            }
        }

        override fun onVideoH264Stream(buffer: ByteBuffer) {
            if (currentState != PageState.PREVIEW || !isPreviewStarted) return
            if (currentConfig?.previewMode != PreviewMode.H264) return
            val config = currentConfig ?: return
            if (buffer.hasRemaining()) {
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
//            Log.d(TAG,"--------onClassicBTAudioStream--")
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
        updateStreamState("接收中")
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
        h264RenderFpsMeter.recordFrame()?.let(::updateFps)
    }

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
        binding.tvFps.text = "帧率：-- fps"
    }

    @SuppressLint("SetTextI18n")
    private fun updateFps(fps: Float) {
        runOnUiThread { binding.tvFps.text = "帧率：${formatFps(fps)} fps" }
    }

    @SuppressLint("SetTextI18n")
    private fun updateStreamState(videoState: String) {
        val bluetooth = if (GlobalData.btConnectState.value) "已连接" else "已断开"
        val p2p = if (GlobalData.p2pConnectState.value) "已连接" else "已断开"
        runOnUiThread {
            binding.tvStreamState.text = "蓝牙：$bluetooth  P2P：$p2p  视频：$videoState"
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
