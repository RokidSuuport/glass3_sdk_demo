package com.rokid.phone

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.rokid.phone.data.GlobalData
import com.rokid.phone.databinding.ActivityVideoReceiveBinding
import com.rokid.phone.utils.TimeUtils
import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.security.phone.sdk.api.msg.listener.IMessageListener
import com.rokid.security.phone.sdk.base.utils.other.mainScope
import com.rokid.security.sdk.base.common.GlassVideoStreamParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

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

    private var currentState = PageState.CONFIG

    private val defaultFps = 10
    private val defaultBitrate = 3_000_000
    private var isGetVideo = false
    private var timerJob: Job? = null
    private var startPreviewJob: Job? = null
    private var videoWatchdogJob: Job? = null
    private var audioWatchdogJob: Job? = null
    private var videoRetryJob: Job? = null
    private var audioRetryJob: Job? = null
    private var lastCallTime = 0L
    private var lastAudioTime = 0L
    private var tryCount = 0
    private var streamRequestVersion = 0
    private var videoRequestCount = 0
    private var audioRequestCount = 0
    private var isAudioRequestedForCurrentStream = false

    @Volatile
    private var isPreviewStarted = false

    @Volatile
    private var hasVideoFrame = false

    @Volatile
    private var hasAudioFrame = false

    private data class ResolutionOption(val width: Int, val height: Int) {
        override fun toString(): String = "${width} x ${height}"
    }

    private data class PreviewConfig(
        val fps: Int,
        val bitrate: Int,
        val resolution: ResolutionOption,
        val isARMixEnabled: Boolean
    )

    private var currentConfig: PreviewConfig? = null

    private companion object {
        const val MAX_STREAM_RETRY_COUNT = 3
        const val FIRST_PACKET_TIMEOUT_MS = 1000L * 8
        const val STREAM_RETRY_DELAY_MS = 300L
        const val VIDEO_STALL_TIMEOUT_MS = 1000L * 5
        val DEFAULT_RESOLUTION = ResolutionOption(1920, 1080)
        val SUPPORTED_RESOLUTIONS = listOf(
            ResolutionOption(2400, 1800),
            ResolutionOption(1800, 2400),
            ResolutionOption(2560, 1440),
            ResolutionOption(2400, 1350),
            ResolutionOption(2048, 1536),
            ResolutionOption(2016, 1512),
            ResolutionOption(1512, 2016),
            ResolutionOption(2340, 1080),
            ResolutionOption(1920, 1080),
            ResolutionOption(1280, 720),
            ResolutionOption(720, 1280),
            ResolutionOption(1024, 768),
            ResolutionOption(800, 600),
            ResolutionOption(648, 648),
            ResolutionOption(854, 480),
            ResolutionOption(800, 480),
            ResolutionOption(640, 480),
            ResolutionOption(480, 640),
            ResolutionOption(640, 360),
            ResolutionOption(360, 640),
            ResolutionOption(352, 288),
            ResolutionOption(320, 240)
        )
    }

    @SuppressLint("SetTextI18n")
    private fun initView() {
        initResolutionSpinner()
        binding.btnStartPreview.setOnClickListener {
            validateAndStartPreview()
        }
        binding.glsurfaceview.setFpsListener { fps ->
            // 格式化两位不足以0不起
            lifecycleScope.launch(Dispatchers.Main) { binding.tvFps.text = "帧率: ${"%04.1f".format(fps)}" }
        }
        audioTrack.play()
    }

    private fun initResolutionSpinner() {
        val adapter = ArrayAdapter(
            this,
            R.layout.item_video_resolution_spinner,
            SUPPORTED_RESOLUTIONS
        ).apply {
            setDropDownViewResource(R.layout.item_video_resolution_spinner_dropdown)
        }
        binding.spinnerResolution.adapter = adapter
        val defaultIndex = SUPPORTED_RESOLUTIONS.indexOf(DEFAULT_RESOLUTION).takeIf { it >= 0 } ?: 0
        binding.spinnerResolution.setSelection(defaultIndex)
    }

    private fun formatFps(fps: Float): String {
        return if (fps % 1 == 0f) {
            "%02d.0".format(fps)
        } else {
            "%.1f".format(fps)
        }
    }

    /* ================= 页面切换 ================= */

    private fun switchPage(state: PageState) {
        currentState = state
        when (state) {
            PageState.CONFIG -> {
                binding.configContainer.visibility = View.VISIBLE
                binding.glsurfaceview.visibility = View.GONE
                binding.tvFps.visibility = View.GONE
            }

            PageState.PREVIEW -> {
                binding.configContainer.visibility = View.GONE
                binding.glsurfaceview.visibility = View.VISIBLE
                binding.tvFps.visibility = View.VISIBLE
            }
        }
    }

    /* ================= 参数校验 ================= */
    private fun validateAndStartPreview() {
        clearErrors()

        val fps = parseFps() ?: return
        val bitrate = parseBitrate() ?: return
        val resolution = parseResolution()
        val isARMixEnabled = binding.swArMix.isChecked

        startPreview(fps, bitrate, resolution, isARMixEnabled)
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
        return if (bitrate in 500_000..10_000_000) bitrate else {
            binding.etBitrate.error = "码率范围 500k~10M"
            toast("码率不合法")
            null
        }
    }

    private fun parseResolution(): ResolutionOption {
        return binding.spinnerResolution.selectedItem as? ResolutionOption ?: DEFAULT_RESOLUTION
    }

    private fun clearErrors() {
        binding.etFrameRate.error = null
        binding.etBitrate.error = null
    }

    /* ================= 预览控制 ================= */
    @SuppressLint("SetTextI18n")
    private fun startPreview(
        fps: Int,
        bitrate: Int,
        resolution: ResolutionOption,
        isARMixEnabled: Boolean
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
        switchPage(PageState.PREVIEW)
        Log.d(TAG, "---------startPreview()---${currentState}")

        currentConfig = PreviewConfig(fps, bitrate, resolution, isARMixEnabled)
        startTime = System.currentTimeMillis()
        lastCallTime = startTime
        lastAudioTime = startTime
        isGetVideo = false
        hasVideoFrame = false
        hasAudioFrame = false
        tryCount = 0
        isPreviewStarted = true
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
                if (System.currentTimeMillis() - lastCallTime > VIDEO_STALL_TIMEOUT_MS && isGetVideo) {
                    tryCount++
                    binding.tvDuration.text = "视频流中断:${TimeUtils.formatDuration(duration)}"
                    if (tryCount >= 3) {
                        tryCount = 0
                        hasVideoFrame = false
                        isGetVideo = false
                        Log.d(TAG,"-------tryCount=${tryCount}")
                        currentConfig?.let {
                            retryVideoStream(streamRequestVersion, it, "video stream stalled")
                        }
                    }
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
                retryVideoStream(requestVersion, config, "video request callback false")
            }
        }
        startVideoWatchdog(requestVersion, config)
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

    private fun startVideoWatchdog(requestVersion: Int, config: PreviewConfig) {
        videoWatchdogJob?.cancel()
        videoWatchdogJob = lifecycleScope.launch {
            delay(FIRST_PACKET_TIMEOUT_MS)
            if (isActiveRequest(requestVersion) && !hasVideoFrame) {
                retryVideoStream(requestVersion, config, "no first video frame")
            }
        }
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

    private fun retryVideoStream(requestVersion: Int, config: PreviewConfig, reason: String) {
        if (!isActiveRequest(requestVersion) || hasVideoFrame || videoRetryJob?.isActive == true) {
            return
        }
        if (videoRequestCount >= MAX_STREAM_RETRY_COUNT) {
            handleStreamFailed(requestVersion, "视频流启动失败，请返回后重新进入预览")
            return
        }
        videoRetryJob = lifecycleScope.launch {
            Log.w(TAG, "retryVideoStream: reason=$reason, nextAttempt=${videoRequestCount + 1}")
            PSecuritySDK.getAbsDeviceInfoService()?.stopVideoStream(VIDEO_TAG) {}
            delay(STREAM_RETRY_DELAY_MS)
            if (isActiveRequest(requestVersion) && !hasVideoFrame) {
                requestVideoStream(requestVersion, config, reason)
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
        tryCount = 0
        timerJob?.cancel()
        timerJob = null
        audioTrack.pause()
        audioTrack.flush()
        val duration = System.currentTimeMillis() - startTime
        binding.tvDuration.text = "结束时长:${TimeUtils.formatDuration(duration)}"
        Log.d(TAG, "---------stopPreview=${currentState}")
    }

    /* ================= NV21 → GLSurfaceView ================= */
    private val nv21Listener = object : IMessageListener {
        override fun onNv21Data(data: ByteArray, width: Int, height: Int) {
            tryCount = 0
            lastCallTime = System.currentTimeMillis()
            if (currentState != PageState.PREVIEW || !isPreviewStarted) return
//            Log.d(TAG, "-----onNv21Data: width=${width},height=${height}")
            isGetVideo = true
            if (!hasVideoFrame) {
                Log.d(TAG, "onNv21Data: first video frame, width=$width, height=$height")
                hasVideoFrame = true
                videoRequestCount = 0
                videoWatchdogJob?.cancel()
                requestAudioStreamAfterFirstVideoFrame()
            }
            mainScope.launch {
                binding.glsurfaceview.setPreviewData(data, width, height)
            }
        }

        override fun onVideoH264Stream(buffer: ByteBuffer) {
            super.onVideoH264Stream(buffer)
            // buffer 获取长度
//            Log.d(TAG, "-----video buffer size: ${buffer.remaining()}")
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
        audioTrack.stop()
        audioTrack.release()
        PSecuritySDK.getMessageService()?.removeMessageListener(nv21Listener)
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d(TAG, "--------onResume--currentState=${currentState}")
        if (currentState == PageState.PREVIEW) {
            startPreview(
                parseFps() ?: defaultFps,
                parseBitrate() ?: defaultBitrate,
                parseResolution(),
                binding.swArMix.isChecked
            )
        }
    }

    private fun toast(msg: String) {
        lifecycleScope.launch {
            Toast.makeText(this@VideoReceiveActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

}
