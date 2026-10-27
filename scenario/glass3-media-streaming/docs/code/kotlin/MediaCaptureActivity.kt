/*
 * 用途：在完整源码工程中只获取 Glass3 NV21/PCM；可直接复制页面代码到眼镜业务模块。
 * 放置位置：app/src/main/java/com/rokid/glass/mediastream/guide/kotlin/MediaCaptureActivity.kt。
 * 依赖：implementation project(":glass3-media-capture")；另一个工程先按 source-integration.md 注册模块。
 * 一并复制 docs/code/shared/LatestVideoWorker.java（示例后台队列与显示快照）。
 * Manifest：CAMERA、RECORD_AUDIO；只启用一种媒体时可以只申请对应运行时权限。
 * 服务参数：原始采集不使用信令 URL 和 roomId；需要传浏览器时改用 GlassMediaStreamer。
 * 预期结果：页面持续显示真实 NV21 宽高/帧大小和 PCM 格式/帧大小。
 * 排障错误：PERMISSION_REQUIRED、SDK_NOT_READY、SDK_DISCONNECTED、CAMERA_IN_USE、
 * CAMERA_START_TIMEOUT、VIDEO_FRAME_TIMEOUT、AUDIO_START_FAILED、AUDIO_DATA_TIMEOUT。
 */
package com.rokid.glass.mediastream.guide.kotlin

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.rokid.glass.mediastream.capture.AudioFrameListener
import com.rokid.glass.mediastream.capture.CaptureOptions
import com.rokid.glass.mediastream.capture.CaptureStatus
import com.rokid.glass.mediastream.capture.CaptureState
import com.rokid.glass.mediastream.capture.GlassMediaCapture
import com.rokid.glass.mediastream.capture.VideoFrameListener
import com.rokid.glass.mediastream.guide.common.LatestVideoWorker

class MediaCaptureActivity : Activity() {
    private lateinit var capture: GlassMediaCapture
    private lateinit var statusView: TextView
    private lateinit var videoView: TextView
    private lateinit var audioView: TextView
    private val frameWorker = LatestVideoWorker()
    private val ui = Handler(Looper.getMainLooper())
    private var activeToken = 0L
    private var foreground = false
    private var destroyed = false
    private val refresh = object : Runnable {
        override fun run() {
            if (!foreground || destroyed) return
            render(capture.currentStatus())
            videoView.text = frameWorker.latest().ifEmpty { "NV21：等待数据" }
            audioView.text = frameWorker.latestAudio().ifEmpty { "PCM：等待数据" }
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        capture = GlassMediaCapture.create(applicationContext)
        statusView = TextView(this).apply { text = "等待开始原始媒体采集" }
        videoView = TextView(this)
        audioView = TextView(this)
        val startButton = Button(this).apply {
            text = "开始获取 NV21/PCM"
            setOnClickListener { requestPermissionsOrStart() }
        }
        val stopButton = Button(this).apply {
            text = "停止"
            setOnClickListener { stopCapture() }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val width = ViewGroup.LayoutParams.MATCH_PARENT
                val height = ViewGroup.LayoutParams.WRAP_CONTENT
                addView(startButton, LinearLayout.LayoutParams(width, height))
                addView(stopButton, LinearLayout.LayoutParams(width, height))
                addView(statusView, LinearLayout.LayoutParams(width, height))
                addView(videoView, LinearLayout.LayoutParams(width, height))
                addView(audioView, LinearLayout.LayoutParams(width, height))
            },
        )
    }

    private fun requestPermissionsOrStart() {
        if (!foreground || destroyed) return
        val missing = REQUIRED_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startCapture() else requestPermissions(missing.toTypedArray(), REQUEST_MEDIA)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MEDIA) return
        if (!foreground || destroyed) return // 页面离开后的授权结果不自动开启采集。
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startCapture()
        } else {
            statusView.text = "PERMISSION_REQUIRED：请授予相机和录音权限后重试"
        }
    }

    private fun startCapture() {
        if (!foreground || destroyed) return
        // 重复点击不替换正在使用的帧回调和工作队列令牌。
        if (capture.currentStatus().state !in setOf(CaptureState.IDLE, CaptureState.ERROR)) return
        val token = frameWorker.start()
        activeToken = token
        runCatching {
            capture.start(
                options = CaptureOptions(),
                videoListener = VideoFrameListener { frame ->
                    // SDK 回调结束后缓冲区会复用；跨线程使用前必须在回调内复制。
                    val width = frame.width
                    val height = frame.height
                    val ownedNv21 = frame.copyData()
                    frameWorker.offer(token) {
                        // 可在此接入耗时算法，勿用此丢旧帧队列录制连续视频。
                        "NV21：$width × $height，本帧 ${ownedNv21.size} bytes"
                    }
                },
                audioListener = AudioFrameListener { frame ->
                    val text = "PCM：${frame.sampleRateHz} Hz / ${frame.channelCount} 声道 / " +
                        "${frame.bitsPerSample} bit，本帧 ${frame.data.size} bytes"
                    frameWorker.publishAudio(token, text)
                },
                statusListener = { status ->
                    runOnUiThread { if (!destroyed && foreground && token == activeToken) render(status) }
                },
            )
        }.onFailure(::showLocalError)
    }

    private fun render(status: CaptureStatus) {
        val failure = status.failure
        if (failure != null) {
            statusView.text = "${failure.code}：${failure.userMessage}\n${failure.suggestedAction}"
        } else statusView.text = "状态：${status.state}"
    }

    private fun showLocalError(error: Throwable) {
        runOnUiThread { if (!destroyed) statusView.text = "启动或清理失败：${error.message.orEmpty()}" }
    }

    private fun stopCapture() {
        frameWorker.stop()
        // 保留本轮状态订阅，接收异步 STOPPING -> IDLE；只停止旧媒体任务。
        if (::capture.isInitialized) runCatching { capture.stop() }.onFailure(::showLocalError)
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        render(capture.currentStatus())
        ui.post(refresh)
    }

    override fun onStop() {
        foreground = false
        ui.removeCallbacks(refresh)
        stopCapture()
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        if (::capture.isInitialized) runCatching { capture.release() }
        frameWorker.close()
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_MEDIA = 1002
        val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }
}
