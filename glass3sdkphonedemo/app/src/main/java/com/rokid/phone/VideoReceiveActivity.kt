package com.rokid.phone

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.rokid.phone.databinding.ActivityVideoReceiveBinding
import com.rokid.phone.utils.TimeUtils
import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.security.phone.sdk.api.msg.listener.IMessageListener
import com.rokid.security.phone.sdk.base.utils.other.mainScope
import com.rokid.security.sdk.base.common.GlassVideoStreamParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
    private var lastCallTime = 0L
    private var tryCount = 0

    @SuppressLint("SetTextI18n")
    private fun initView() {
        binding.btnStartPreview.setOnClickListener {
            validateAndStartPreview()
        }
        binding.glsurfaceview.setFpsListener { fps ->
            // 格式化两位不足以0不起
            lifecycleScope.launch(Dispatchers.Main) { binding.tvFps.text = "帧率: ${"%04.1f".format(fps)}" }
        }
        audioTrack.play()
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

        startPreview(fps, bitrate)
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

    private fun clearErrors() {
        binding.etFrameRate.error = null
        binding.etBitrate.error = null
    }

    /* ================= 预览控制 ================= */
    @SuppressLint("SetTextI18n")
    private fun startPreview(fps: Int, bitrate: Int) {
        switchPage(PageState.PREVIEW)

        // 1️⃣ 注册 NV21 监听
        PSecuritySDK.getMessageService()?.addMessageListener(nv21Listener)

        // 2️⃣ 请求视频流
        val param = GlassVideoStreamParam().apply {
            this.width = 1920
            this.height = 1080
            this.fps = fps
            this.bitrate = bitrate
            this.isARMixEnabled = true
        }
        PSecuritySDK.getAbsDeviceInfoService()?.requestVideoStream(VIDEO_TAG, videoStreamParam = param) {}
        PSecuritySDK.getAbsDeviceInfoService()?.requestAudioStream(AUDIO_TAG) {}
        startTime = System.currentTimeMillis()
        lastCallTime = startTime
        isGetVideo = false
        if (timerJob != null) {
            return
        }
        timerJob = lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                val duration = System.currentTimeMillis() - startTime
                if (System.currentTimeMillis() - lastCallTime > 1500 && isGetVideo) {
                    tryCount++
                    binding.tvDuration.text = "结束时长:${TimeUtils.formatDuration(duration)}"
                    if (tryCount >= 5) {
                        cancel()
                    }
                } else {
                    binding.tvDuration.text = "时长:${TimeUtils.formatDuration(duration)}"
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun stopPreview() {
        // 1️⃣ 停流
        PSecuritySDK.getAbsDeviceInfoService()?.stopVideoStream(VIDEO_TAG) {}
        PSecuritySDK.getAbsDeviceInfoService()?.stopAudioStream(AUDIO_TAG) {}
        isGetVideo = false
        tryCount = 0
        // 2️⃣ 解除 NV21 监听
        PSecuritySDK.getMessageService()?.removeMessageListener(nv21Listener)
        timerJob?.cancel()
        timerJob = null
        val duration = System.currentTimeMillis() - startTime
        binding.tvDuration.text = "结束时长:${TimeUtils.formatDuration(duration)}"

    }

    /* ================= NV21 → GLSurfaceView ================= */
    private val nv21Listener = object : IMessageListener {
        override fun onNv21Data(data: ByteArray, width: Int, height: Int) {
            tryCount = 0
            lastCallTime = System.currentTimeMillis()
            if (currentState != PageState.PREVIEW) return
//            Log.d(TAG, "-----onNv21Data: width=${width},height=${height}")
            isGetVideo = true
            mainScope.launch {
                binding.glsurfaceview.setPreviewData(data, width, height)
            }
        }

        override fun onVideoH264Stream(buffer: ByteBuffer) {
            super.onVideoH264Stream(buffer)
            // buffer 获取长度
//            Log.d(TAG, "-----video buffer size: ${buffer.remaining()}")
        }

        override fun onClassicBTAudioStream(buffer: ByteArray) {
            if (currentState != PageState.PREVIEW) return
            audioTrack.write(buffer, 0, buffer.size)
        }
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
        if (currentState == PageState.PREVIEW) {
            startPreview(parseFps() ?: defaultFps, parseBitrate() ?: defaultBitrate)
        }
    }

    private fun toast(msg: String) {
        mainScope.launch {
            Toast.makeText(this@VideoReceiveActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

}