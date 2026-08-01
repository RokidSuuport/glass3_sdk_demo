package com.rokid.glass.speech.privateservice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.rokid.glass.base.GlassKeyEvent
import com.rokid.glass.speech.SpeechControlNavigationState
import com.rokid.glass.speech.SpeechControlPosition
import com.rokid.glass.speech.SpeechGestureActivity
import com.rokid.glesse.R
import com.rokid.online.speech.AsrClient
import com.rokid.online.speech.OnlineSpeechSdk
import com.rokid.online.speech.OnlineSpeechSdkConfig
import com.rokid.online.speech.TtsAudioConfig
import com.rokid.online.speech.TtsClient
import com.rokid.online.speech.TtsPlaybackState
import com.rokid.online.speech.open.AndroidPcmTtsStreamPlayer
import com.rokid.online.speech.open.OpenSdkAudioSource
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.client.IServiceConnectionCallback

class PrivateSpeechActivity : SpeechGestureActivity() {
    private lateinit var logView: TextView
    private lateinit var pager: ViewPager2
    private lateinit var pagerAdapter: PrivateSpeechPagerAdapter
    private val navigation = SpeechControlNavigationState(listOf(2, 4, 4))
    private val logLines = ArrayDeque<String>()
    private val config by lazy { PrivateSpeechConfig.fromBuildConfig() }

    private var sdk: OnlineSpeechSdk? = null
    private var asr: AsrClient? = null
    private var tts: TtsClient? = null
    private val audioSource = OpenSdkAudioSource()
    private val streamPlayer = AndroidPcmTtsStreamPlayer()
    private var asrConnected = false
    private var asrMicRunning = false
    private var ttsConnected = false
    private var isBindingRequested = false
    private var initializeWhenBound = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_private_speech)
        logView = findViewById(R.id.tv_private_speech_log)
        pager = findViewById(R.id.pager_private_speech)
        pagerAdapter = PrivateSpeechPagerAdapter(this)
        pager.adapter = pagerAdapter
        pager.offscreenPageLimit = 2
        pager.isUserInputEnabled = false
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (navigation.current.groupIndex != position) {
                    renderPosition(navigation.select(position, 0))
                }
            }
        })
        val tabLayout = findViewById<TabLayout>(R.id.tabs_private_speech)
        TabLayoutMediator(
            tabLayout,
            pager,
        ) { tab, position ->
            tab.text = TAB_TITLES[position]
        }.attach()
        repeat(tabLayout.tabCount) { index ->
            tabLayout.getTabAt(index)?.let { tab ->
                tab.view.isClickable = false
            }
        }
        pager.post { renderPosition(navigation.current) }
        log("domain=${config.domain.ifBlank { "-" }} asrPath=${config.asrPath} ttsPath=${config.ttsPath}")
        log("ak=${mask(config.ak)} sk=${mask(config.sk)} trustAllCerts=${config.trustAllCerts}")
        bindOpenSdkService()
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        when (keyEvent) {
            GlassKeyEvent.KEYCODE_FRONT -> {
                renderPosition(navigation.moveForward())
            }
            GlassKeyEvent.KEYCODE_BEHIND -> {
                renderPosition(navigation.moveBackward())
            }
            GlassKeyEvent.KEYCODE_CLICK -> {
                dispatchAction(actionFor(navigation.current))
            }
            else -> return super.onGlassKeyEvent(keyEvent)
        }
        return true
    }

    private fun dispatchAction(action: PrivateSpeechAction) {
        log("执行操作：${action.name}")
        when (action) {
            PrivateSpeechAction.INIT -> initializePrivateSession()
            PrivateSpeechAction.RELEASE -> {
                closePrivateSession()
                log("私有语音资源已释放")
            }
            PrivateSpeechAction.ASR_CONNECT -> connectAsr()
            PrivateSpeechAction.ASR_START -> startAsr()
            PrivateSpeechAction.ASR_STOP -> stopAsr()
            PrivateSpeechAction.ASR_CLOSE -> closeAsrClient()
            PrivateSpeechAction.TTS_CONNECT -> connectTts()
            PrivateSpeechAction.TTS_SPEAK -> playTts()
            PrivateSpeechAction.TTS_STOP -> stopTts()
            PrivateSpeechAction.TTS_CLOSE -> closeTtsClient()
        }
    }

    private fun renderPosition(position: SpeechControlPosition) {
        if (pager.currentItem != position.groupIndex) {
            pager.setCurrentItem(position.groupIndex, false)
        }
        pager.post {
            (pagerAdapter.fragments[position.groupIndex] as PrivateSpeechPage)
                .requestActionFocus(position.itemIndex)
        }
    }

    private fun actionFor(position: SpeechControlPosition): PrivateSpeechAction {
        return ACTION_GROUPS[position.groupIndex][position.itemIndex]
    }

    private fun initializePrivateSession() {
        val missing = config.missingRequiredFields()
        if (missing.isNotEmpty()) return log("缺少配置: ${missing.joinToString()}")
        if (!GlassSdk.isReady()) {
            initializeWhenBound = true
            bindOpenSdkService()
            log("正在绑定 GlassSdk，绑定完成后自动初始化")
            return
        }
        createPrivateSession()
    }

    private fun createPrivateSession() {
        initializeWhenBound = false
        closePrivateSession()

        val privateSdk = OnlineSpeechSdk(
            OnlineSpeechSdkConfig(
                domain = config.domain,
                ak = config.ak,
                sk = config.sk,
                uid = config.uid,
                deviceId = config.deviceId,
                asrPath = config.asrPath,
                ttsPath = config.ttsPath,
                trustAllCerts = config.trustAllCerts,
                staticHttpHeaders = requestHeaders(),
                staticMessageHeaders = requestHeaders(),
            )
        )
        sdk = privateSdk
        asr = privateSdk.createAsrClient().attachAudioSource(audioSource).also(::setupAsrCallbacks)
        tts = privateSdk.createTtsClient().attachStreamPlayer(streamPlayer).also(::setupTtsCallbacks)
        log("私有 SDK 初始化完成")
    }

    private fun bindOpenSdkService() {
        if (GlassSdk.isReady() || isBindingRequested) return
        isBindingRequested = true
        GlassSdk.bindSecurityService(this, object : IServiceConnectionCallback {
            override fun onServiceConnected() {
                isBindingRequested = false
                log("GlassSdk 服务已连接")
                if (initializeWhenBound) {
                    createPrivateSession()
                }
            }

            override fun onServiceDisconnected() {
                isBindingRequested = false
                log("GlassSdk 服务已断开")
            }

            override fun onBindingDied() {
                isBindingRequested = false
                log("GlassSdk 服务绑定失效")
            }
        })
    }

    private fun connectAsr() {
        val client = asr ?: return log("请先初始化私有 SDK")
        client.connect()
        log("正在连接私有 ASR")
    }

    private fun startAsr() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            return
        }
        val client = asr ?: return log("请先初始化私有 SDK")
        if (!asrConnected) return log("请先连接私有 ASR")
        runCatching { client.startAsrWithMic() }
            .onSuccess { asrMicRunning = true; log("私有 ASR 已开始，请说话") }
            .onFailure { log("私有 ASR 启动失败：${it.message}") }
    }

    private fun stopAsr() {
        val client = asr ?: return log("私有 ASR 尚未初始化")
        if (!asrMicRunning) return log("私有 ASR 麦克风未运行")
        runCatching { client.stopAsrWithMic() }
            .onSuccess { asrMicRunning = false; log("私有 ASR 已停止") }
            .onFailure { log("私有 ASR 停止失败：${it.message}") }
    }

    private fun closeAsrClient() {
        if (asrMicRunning) runCatching { asr?.stopAsrWithMic() }
        runCatching { asr?.close() }
        asrConnected = false
        asrMicRunning = false
        log("私有 ASR 已关闭")
    }

    private fun connectTts() {
        val client = tts ?: return log("请先初始化私有 SDK")
        client.connect()
        log("正在连接私有 TTS")
    }

    private fun playTts() {
        val client = tts ?: return log("请先初始化私有 SDK")
        if (!ttsConnected) return log("请先连接私有 TTS")
        log("开始私有 TTS；测试时请关闭投屏，避免声音路由到投屏设备")
        client.speak(DEFAULT_TTS_TEXT, TtsAudioConfig())
    }

    private fun stopTts() {
        val client = tts ?: return log("私有 TTS 尚未初始化")
        client.stop()
        log("私有 TTS 已停止")
    }

    private fun closeTtsClient() {
        runCatching { tts?.stop() }
        runCatching { tts?.close() }
        ttsConnected = false
        log("私有 TTS 已关闭")
    }

    private fun setupAsrCallbacks(client: AsrClient) {
        client.setListener(object : AsrClient.Listener {
            override fun onOpen() { asrConnected = true; log("私有 ASR WebSocket 已连接") }
            override fun onStart(taskId: String) = log("私有 ASR 任务开始：$taskId")
            override fun onPartialResult(taskId: String, text: String) = log("私有 ASR 中间结果：$text")
            override fun onFinalResult(taskId: String, text: String) = log("私有 ASR 最终结果：$text")
            override fun onFinished(taskId: String) = log("私有 ASR 任务结束：$taskId")
            override fun onError(code: Int, message: String) = log("私有 ASR 错误：$code $message")
            override fun onClosed(code: Int, reason: String) {
                asrConnected = false
                asrMicRunning = false
                log("私有 ASR 已关闭：$code $reason")
            }
        })
    }

    private fun setupTtsCallbacks(client: TtsClient) {
        client.setListener(object : TtsClient.Listener {
            override fun onOpen() { ttsConnected = true; log("私有 TTS WebSocket 已连接") }
            override fun onStart(taskId: String) = log("私有 TTS 任务开始：$taskId")
            override fun onAudioChunk(taskId: String, audio: ByteArray) = log("私有 TTS 音频块：${audio.size}")
            override fun onFinished(taskId: String) = log("私有 TTS 任务结束：$taskId")
            override fun onError(code: Int, message: String) = log("私有 TTS 错误：$code $message")
            override fun onClosed(code: Int, reason: String) {
                ttsConnected = false
                log("私有 TTS 已关闭：$code $reason")
            }
            override fun onPlaybackStateChanged(state: TtsPlaybackState) = log("私有 TTS 播放状态：$state")
        })
    }

    private fun closePrivateSession() {
        if (asrMicRunning) runCatching { asr?.stopAsrWithMic() }
        runCatching { asr?.close() }
        runCatching { tts?.stop() }
        runCatching { tts?.close() }
        runCatching { sdk?.close() }
        asr = null
        tts = null
        sdk = null
        asrConnected = false
        asrMicRunning = false
        ttsConnected = false
    }

    private fun requestHeaders(): Map<String, String> = mapOf(
        "appCredential" to "userInfo",
        "messageId" to "glass-demo-${System.currentTimeMillis()}",
    )

    private fun mask(value: String): String = when {
        value.isBlank() -> "-"
        value.length <= 8 -> "***"
        else -> "***${value.takeLast(8)}"
    }

    private fun log(message: String) {
        runOnUiThread {
            logLines.addFirst(message)
            while (logLines.size > MAX_LOG_LINES) logLines.removeLast()
            logView.text = logLines.joinToString("\n")
        }
    }

    override fun onDestroy() {
        initializeWhenBound = false
        closePrivateSession()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 702
        private const val MAX_LOG_LINES = 160
        private const val DEFAULT_TTS_TEXT = "你好，这是私有化部署在线语音服务测试。"
        private val TAB_TITLES = listOf("初始化", "ASR", "TTS")
        private val ACTION_GROUPS = listOf(
            listOf(PrivateSpeechAction.INIT, PrivateSpeechAction.RELEASE),
            listOf(
                PrivateSpeechAction.ASR_CONNECT,
                PrivateSpeechAction.ASR_START,
                PrivateSpeechAction.ASR_STOP,
                PrivateSpeechAction.ASR_CLOSE,
            ),
            listOf(
                PrivateSpeechAction.TTS_CONNECT,
                PrivateSpeechAction.TTS_SPEAK,
                PrivateSpeechAction.TTS_STOP,
                PrivateSpeechAction.TTS_CLOSE,
            ),
        )
    }
}
