package com.rokid.glass.speech.publicservice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.rokid.glass.base.GlassKeyEvent
import com.rokid.glass.speech.SpeechControlNavigationState
import com.rokid.glass.speech.SpeechGestureActivity
import com.rokid.glesse.R
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.system.server.asr.listener.SpeechCallback
import com.rokid.security.system.server.tts.listener.SpeechCompleteListener

class PublicSpeechActivity : SpeechGestureActivity() {
    private lateinit var controls: List<Button>
    private lateinit var logView: TextView
    private val navigation = SpeechControlNavigationState(listOf(4))
    private val logLines = ArrayDeque<String>()

    private val asrCallback = object : SpeechCallback.Stub() {
        override fun onStart() = log("ASR 已开始，请说话")
        override fun onIntermediateVad(content: String) = log("ASR 中间结果：$content")
        override fun onAsrComplete(content: String?) = log("ASR 最终结果：${content.orEmpty()}")
        override fun onAsrCompleteWithIntent(content: String?, intent: Int, intentJson: String) {
            log("ASR 意图结果：${content.orEmpty()}，intent=$intent")
        }
        override fun onError(code: Int) = log("ASR 错误：$code")
        override fun onServiceConnectState(connect: Boolean) = log("ASR 服务连接：$connect")
    }

    private val ttsListener = object : SpeechCompleteListener.Stub() {
        override fun onComplete() = log("在线 TTS 播放完成")
        override fun onServiceConnectState(connected: Boolean) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_public_speech)
        logView = findViewById(R.id.tv_public_speech_log)
        controls = listOf(
            findViewById(R.id.btn_public_asr_start),
            findViewById(R.id.btn_public_asr_stop),
            findViewById(R.id.btn_public_tts_start),
            findViewById(R.id.btn_public_tts_stop),
        )
        renderSelection(navigation.current.itemIndex)
        GlassSdk.getGlassTtsService()?.setSpeechCompleteListener(ttsListener)
        log("GlassSdk 示例由 GlassSdk 管理 api.rokid.com 环境与鉴权")
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        when (keyEvent) {
            GlassKeyEvent.KEYCODE_FRONT -> {
                renderSelection(navigation.moveForward().itemIndex)
            }
            GlassKeyEvent.KEYCODE_BEHIND -> {
                renderSelection(navigation.moveBackward().itemIndex)
            }
            GlassKeyEvent.KEYCODE_CLICK -> {
                runSelectedAction(navigation.current.itemIndex)
            }
            else -> return super.onGlassKeyEvent(keyEvent)
        }
        return true
    }

    private fun runSelectedAction(index: Int) {
        when (index) {
            0 -> startAsr()
            1 -> stopAsr()
            2 -> playTts()
            3 -> stopTts()
        }
    }

    private fun startAsr() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            return
        }
        if (!GlassSdk.isReady()) return log("GlassSdk 尚未初始化完成")
        val service = GlassSdk.getGlassAsrService() ?: return log("公共 ASR 服务不可用")
        service.stopSpeech()
        log("正在启动公共 ASR...")
        service.startSpeech(asrCallback)
    }

    private fun stopAsr() {
        GlassSdk.getGlassAsrService()?.stopSpeech()
        log("公共 ASR 已停止")
    }

    private fun playTts() {
        if (!GlassSdk.isReady()) return log("GlassSdk 尚未初始化完成")
        val service = GlassSdk.getGlassTtsService() ?: return log("公共在线 TTS 服务不可用")
        log("开始公共在线 TTS；测试时请关闭投屏，避免声音路由到投屏设备")
        service.doSpeechTts(DEFAULT_TTS_TEXT)
    }

    private fun stopTts() {
        GlassSdk.getGlassTtsService()?.doCancelTts()
        log("公共在线 TTS 已停止")
    }

    private fun renderSelection(index: Int) {
        controls.forEach { it.isSelected = false }
        controls[index].apply {
            isSelected = true
            requestFocus()
        }
    }

    private fun log(message: String) {
        runOnUiThread {
            logLines.addFirst(message)
            while (logLines.size > MAX_LOG_LINES) logLines.removeLast()
            logView.text = logLines.joinToString("\n")
        }
    }

    override fun onStop() {
        GlassSdk.getGlassAsrService()?.stopSpeech()
        GlassSdk.getGlassTtsService()?.doCancelTts()
        super.onStop()
    }

    override fun onDestroy() {
        GlassSdk.getGlassTtsService()?.removeSpeechCompleteListener()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 701
        private const val MAX_LOG_LINES = 120
        private const val DEFAULT_TTS_TEXT = "你好，这是 Rokid 公共在线语音服务测试。"
    }
}
