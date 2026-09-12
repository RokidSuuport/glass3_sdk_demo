/*
 * 用途：在完整源码工程中接入浏览器推流；可直接复制页面代码到眼镜业务模块。
 * 放置位置：app/src/main/java/com/rokid/glass/mediastream/guide/kotlin/StreamingActivity.kt。
 * 依赖：implementation project(":glass3-media-streaming")；另一个工程先按 source-integration.md 注册组件。
 * Manifest：INTERNET、ACCESS_NETWORK_STATE、CAMERA、RECORD_AUDIO、MODIFY_AUDIO_SETTINGS；
 * 使用 ws 地址时 application 还需 android:usesCleartextTraffic="true"。
 * 必改参数：把 SERVER_URL_HINT 替换为 PC 页面显示的信令地址；按业务修改 ROOM_ID。
 * 预期结果：状态依次到 WAITING_RECEIVER、NEGOTIATING、STREAMING，浏览器收到视频和音频。
 * 排障错误：PERMISSION_REQUIRED、SERVER_UNREACHABLE、RECEIVER_NOT_READY、
 * WEBRTC_NEGOTIATION_FAILED、NETWORK_DISCONNECTED；其余设备错误见 troubleshooting.md。
 */
package com.rokid.glass.mediastream.guide.kotlin

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.rokid.glass.mediastream.streaming.GlassMediaStreamer
import com.rokid.glass.mediastream.streaming.StreamingOptions
import com.rokid.glass.mediastream.streaming.StreamingState
import com.rokid.glass.mediastream.streaming.StreamingStatus

class StreamingActivity : Activity() {
    private lateinit var streamer: GlassMediaStreamer
    private lateinit var serverUrlInput: EditText
    private lateinit var statusView: TextView
    private var destroyed = false
    private var foreground = false
    private var generation = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        streamer = GlassMediaStreamer.create(applicationContext)

        serverUrlInput = EditText(this).apply {
            hint = SERVER_URL_HINT
            setSingleLine(true)
        }
        statusView = TextView(this).apply { text = "状态：${StreamingState.IDLE}" }
        val startButton = Button(this).apply {
            text = "开始音视频传输"
            setOnClickListener { requestPermissionsOrStart() }
        }
        val stopButton = Button(this).apply {
            text = "停止"
            setOnClickListener { runCatching { streamer.stop() }.onFailure(::showLocalError) }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val width = ViewGroup.LayoutParams.MATCH_PARENT
                val height = ViewGroup.LayoutParams.WRAP_CONTENT
                addView(serverUrlInput, LinearLayout.LayoutParams(width, height))
                addView(startButton, LinearLayout.LayoutParams(width, height))
                addView(stopButton, LinearLayout.LayoutParams(width, height))
                addView(statusView, LinearLayout.LayoutParams(width, height))
            },
        )
    }

    private fun requestPermissionsOrStart() {
        if (!foreground || destroyed) return
        val missing = REQUIRED_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startStreaming() else requestPermissions(missing.toTypedArray(), REQUEST_MEDIA)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MEDIA) return
        if (!foreground || destroyed) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startStreaming()
        } else {
            statusView.text = "PERMISSION_REQUIRED：请授予相机和录音权限后重试"
        }
    }

    private fun startStreaming() {
        if (!foreground || destroyed) return
        if (streamer.currentStatus().state !in setOf(StreamingState.IDLE, StreamingState.ERROR)) return
        val url = serverUrlInput.text.toString().trim()
        if (url.isEmpty() || url.contains('<') || url.contains('>')) {
            statusView.text = "请输入浏览器接收页显示的完整 ws:// 或 wss:// 信令地址"
            return
        }
        runCatching {
            val token = ++generation
            streamer.start(
                StreamingOptions(
                    serverUrl = url,
                    videoEnabled = true,
                    audioEnabled = true,
                    roomId = ROOM_ID,
                ),
            ) { status ->
                runOnUiThread { if (!destroyed && foreground && token == generation) render(status) }
            }
        }.onFailure(::showLocalError)
    }

    private fun render(status: StreamingStatus) {
        val failure = status.failure
        statusView.text = if (failure == null) {
            "状态：${status.state}\n" +
                "采集：${status.stats.videoWidth} × ${status.stats.videoHeight} " +
                "${"%.1f".format(status.stats.videoFps)} FPS\n" +
                "编码：${status.stats.encodedVideoWidth} × ${status.stats.encodedVideoHeight} " +
                "${"%.1f".format(status.stats.encodedVideoFps)} FPS\n" +
                "码率：视频 ${status.stats.videoBitrateBps} bps，音频 ${status.stats.audioBitrateBps} bps"
        } else {
            "${failure.code}：${failure.userMessage}\n${failure.suggestedAction}"
        }
    }

    private fun showLocalError(error: Throwable) {
        runOnUiThread { if (!destroyed) statusView.text = "启动或清理失败：${error.message.orEmpty()}" }
    }

    override fun onStop() {
        foreground = false
        if (::streamer.isInitialized) runCatching { streamer.stop() }
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        render(streamer.currentStatus())
    }

    override fun onDestroy() {
        destroyed = true
        if (::streamer.isInitialized) runCatching { streamer.release() }
        super.onDestroy()
    }

    private companion object {
        const val SERVER_URL_HINT = "ws://<PC-IP>:8080/ws"
        const val ROOM_ID = "default"
        const val REQUEST_MEDIA = 1001
        val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }
}
