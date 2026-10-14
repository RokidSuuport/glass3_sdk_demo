package com.rokid.glass

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.rokid.glass.audio.AudioStreamControl
import com.rokid.glass.audio.AudioStreamSession
import com.rokid.glass.base.BaseGlassActivity
import com.rokid.glass.base.GlassKeyEvent
import com.rokid.glass.media.MessageMediaActions
import com.rokid.glass.speech.MessageTtsController
import com.rokid.glass.speech.OnlineAsrStatusMessages
import com.rokid.glass.speech.OnlineSpeechSession
import com.rokid.glass.utils.FileSizeUtil
import com.rokid.glass.utils.demoString
import com.rokid.glesse.R
import com.rokid.glesse.databinding.ActivitySendmessageBinding
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.tts.TtsQueueMode
import com.rokid.security.glass3.sdk.base.data.offlineCmd.bean.VoiceAction
import com.rokid.security.glass3.sdk.base.data.offlineCmd.listener.IVoiceCallback
import com.rokid.security.system.server.asr.listener.SpeechCallback
import com.rokid.security.system.server.message.callback.IResultCallback
import com.rokid.security.system.server.message.file.listener.FileReceiveListener
import com.rokid.security.system.server.message.listener.IMessageListener
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 眼镜端 SDK 功能演示页面。
 * Demo page for glasses SDK features.
 *
 * 演示 TTS/ASR、P2P 与蓝牙消息、文件传输、音频流和视频发送。
 * Demonstrates TTS/ASR, P2P and Bluetooth messages, file transfer, audio streams and video transmission.
 * 音频采集、AI 对话和乐奇拦截位于“设备工具”页。
 * Audio capture, AI chat and Leqi interception live on the Device tools page.
 */
class SendMessageActivity : BaseGlassActivity() {
    override val translateTouchToGlassGesture = false

    companion object {
        private const val REQUEST_RECORD_AUDIO_PERMISSION = 1001
        private val ONLINE_TTS_DEMO_TEXT: String get() = demoString(R.string.demo_this_is_an_online_text_to_speech_demo)
        private val OFFLINE_TTS_DEMO_TEXT: String get() = demoString(R.string.demo_this_is_an_offline_text_to_speech_demo)
        private const val ONLINE_ASR_TIMEOUT_MS = 15_000L
    }

    // 当前获得焦点的功能按钮 id，点击事件通过该 id 分发到对应 SDK 功能。
    // ID of the focused feature button; clicks use it to dispatch the corresponding SDK operation.
    private var currentSelectId: Int = -1

    // 文件发送时在两个示例文件之间交替选择。
    // Alternate between the two sample files when sending files.
    private var randomFile = false
    private val TAG = "SendMessageActivity"
    private lateinit var binding: ActivitySendmessageBinding

    // 记录文件开始发送时间，用于在完成回调中计算耗时。
    // Record the file transfer start time to calculate elapsed time in the completion callback.
    private var startTime = 0L

    // 眼镜按键菜单当前位置，对应 menuButtons() 中的下标。
    // Current position in the glasses key menu, corresponding to an index in menuButtons().
    private var selectBtnStatus = 0

    // Assets 中的示例文件会复制到公共 Download 目录，再用于传输示例。
    // Copy sample assets to the public Download directory for the transfer example.
    private val sdDownload = File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS)

    private lateinit var huoVoiceAction: VoiceAction
    private var startAsrAfterPermissionGranted = false
    private var onlineAsrTimeoutJob: Job? = null
    private val onlineAsrSession = OnlineSpeechSession()
    private val messageTts by lazy { MessageTtsController(this, ::log) }
    private var ttsQueueMode = TtsQueueMode.ADD
    private val audioStreamSession = AudioStreamSession()
    private val mediaActions = lazy { MessageMediaActions(::log) }
    private val gson = Gson()
    private val speechHandler = Handler(Looper.getMainLooper())

    // 与手机端音频流保持相同格式：16 kHz、单声道、PCM 16 bit。
    // Match the phone audio stream: 16 kHz, mono, 16-bit PCM.
    private val audioTrack = AudioTrack(
        AudioManager.STREAM_MUSIC,
        16000,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        AudioTrack.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ),
        AudioTrack.MODE_STREAM
    )


    private fun deliver(session: OnlineSpeechSession, token: Long, action: () -> Unit) {
        speechHandler.post {
            if (session.accepts(token) && !isDestroyed) action()
        }
    }

    private fun speechCallback(token: Long) = object : SpeechCallback.Stub() {
        override fun onStart() = deliver(onlineAsrSession, token) {
            onlineAsrSession.started(token)
            cancelOnlineAsrTimeout()
            log(OnlineAsrStatusMessages.started)
        }

        override fun onIntermediateVad(content: String) = deliver(onlineAsrSession, token) { log(content) }

        override fun onAsrComplete(content: String?) = deliver(onlineAsrSession, token) {
            val result = content.orEmpty()
            log(if (result.isBlank()) demoString(R.string.demo_speech_recognition_finished_with_no_text) else result)
        }

        override fun onAsrCompleteWithIntent(content: String?, intent: Int, intentJson: String) = deliver(onlineAsrSession, token) {
            log(demoString(R.string.demo_recognized_text_intent_index_intent_json, content.orEmpty(), intent, intentJson))
        }

        override fun onError(code: Int) = deliver(onlineAsrSession, token) {
            stopOnlineAsr()
            log(OnlineAsrStatusMessages.error(code))
        }

        override fun onServiceConnectState(connect: Boolean) = deliver(onlineAsrSession, token) {
            // SDK 可能重放没有会话标识的缓存状态，不能仅据此判断本次识别失败。
            // Cached SDK status has no session ID, so it alone cannot prove this recognition request failed.
            if (connect) log(OnlineAsrStatusMessages.connected)
            else log(demoString(R.string.demo_online_asr_connection_is_not_ready_waiting_for))
        }
    }

    private val mFileOperator by lazy {
        GlassSdk.getGlassMessageService()?.glassFileOperater
    }

    private val mBTFileOperator by lazy {
        GlassSdk.getGlassMessageService()?.glassBtFileOperater
    }

    /**
     * 蓝牙文件发送状态回调。
     * Bluetooth file transfer status callback.
     *
     * FileReceiveListener 名称虽然是 ReceiveListener，但发送方也通过它接收
     * Despite its name, FileReceiveListener is also used by the sender to receive
     * 开始、进度、完成、失败和取消等传输状态。
     * transfer states such as start, progress, completion, failure, and cancellation.
     */
    private val bleFileReceiveListener = object : FileReceiveListener.Stub() {
        override fun onStart() {
            startTime = System.currentTimeMillis()
            Log.i(TAG, "onStart: 蓝牙本端开始发送文件")
            log(demoString(R.string.demo_started_sending_file_via_bluetooth))
        }

        override fun onProgressChanged(progress: Float) {
            Log.i(TAG, "onProgressChanged: 蓝牙本端发送文件的进度 $progress")
            log(demoString(R.string.demo_bluetooth_file_transfer_progress, progress))
        }

        /**
         * 眼镜端待发送文件的路径
         * Path of the file to send from the glasses.
         */
        override fun onComplete(filePath: String) {
            // 手机端接收文件的路径 /storage/emulated/0/Android/data/com.rokid.phone/files/receiver/gonglu.png
            // Received file path on the phone: /storage/emulated/0/Android/data/com.rokid.phone/files/receiver/gonglu.png
            Log.i(TAG, "onComplete: 蓝牙本端发送文件完成,----$filePath")
            var duration = System.currentTimeMillis() - startTime
            val fileSize = FileSizeUtil.formatFileSize(FileSizeUtil.getFileSizeBytes(curFile).toDouble())
            if (duration > 1000) {
                duration = duration / 1000
                log(demoString(R.string.demo_bluetooth_file_sent_in_seconds, duration, fileSize))
            } else {
                log(demoString(R.string.demo_bluetooth_file_sent_in_ms, duration, fileSize))
            }
        }

        override fun onFail() {
            Log.i(TAG, "onFail: 蓝牙本端发送文件失败")
            log(demoString(R.string.demo_failed_to_send_file_via_bluetooth))
        }

        override fun onCancel() {
            Log.i(TAG, "onCancel: 蓝牙对方取消了发送文件")
            log(demoString(R.string.demo_bluetooth_recipient_canceled_file_transfer))
        }
    }

    /**
     * P2P 文件发送状态回调，负责输出进度并统计发送耗时和文件大小。
     * P2P file transfer status callback; report progress and track transfer duration and file size.
     */
    private val p2pFileReceiveListener = object : FileReceiveListener.Stub() {
        override fun onStart() {
            startTime = System.currentTimeMillis()
            Log.i(TAG, "onStart: p2p本端开始发送文件")
            log(demoString(R.string.demo_started_sending_file_via_p2p))
        }

        override fun onProgressChanged(progress: Float) {
            Log.i(TAG, "onProgressChanged: p2p本端发送文件的进度 $progress")
            log(demoString(R.string.demo_p2p_file_transfer_progress, progress))
        }

        /**
         * 眼镜端待发送文件的路径
         * Path of the file to send from the glasses.
         */
        override fun onComplete(filePath: String) {
            // 手机端接收文件的路径 /storage/emulated/0/Android/data/com.rokid.phone/files/receiver/gonglu.png
            // Received file path on the phone: /storage/emulated/0/Android/data/com.rokid.phone/files/receiver/gonglu.png
            Log.i(TAG, "onComplete: p2p本端发送文件完成,----$filePath")
            var duration = System.currentTimeMillis() - startTime
            val fileSize = FileSizeUtil.formatFileSize(FileSizeUtil.getFileSizeBytes(curFile).toDouble())
            if (duration > 1000) {
                duration = duration / 1000
                log(demoString(R.string.demo_p2p_file_sent_in_seconds, duration, fileSize))
            } else {
                log(demoString(R.string.demo_p2p_file_sent_in_ms, duration, fileSize))
            }
        }

        override fun onFail() {
            Log.i(TAG, "onFail: p2p本端发送文件失败")
            log(demoString(R.string.demo_failed_to_send_file_via_p2p))
        }

        override fun onCancel() {
            Log.i(TAG, "onCancel: p2p对方取消了发送文件")
            log(demoString(R.string.demo_p2p_recipient_canceled_file_transfer))
        }
    }

    val a1File = "xiyi.jpg"
    val a2File = "gonglu.png"

    // 当前正在发送的文件，完成回调通过它统计源文件大小。
    // The file currently being sent; the completion callback uses it to determine the source file size.
    var curFile = File("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.e(TAG, "初始化")
        binding = ActivitySendmessageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 消息区至少占满按钮视口，滚动到底时不会残留上一组按钮的底边。
        // Fill at least one controls viewport so the previous group's clipped edges cannot remain at the bottom.
        binding.controlsScrollView.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            binding.llMain.minimumHeight = (bottom - top).coerceAtLeast(0)
        }

        // 提前准备文件传输演示使用的两个本地文件。
        // Prepare the two local files used by the file transfer demo in advance.
        copyFileFromAssetsToExternalStorage(this, a1File)
        copyFileFromAssetsToExternalStorage(this, a2File)

        // 默认选中第一个功能，确保眼镜点击键进入页面后可以直接操作。
        // Select the first feature by default so the glasses click key works as soon as the page opens.
        selectBtn(binding.btnTtsQueueMode)

        // 分别注册经典蓝牙和 P2P 文件传输状态监听。
        // Register file transfer status listeners for classic Bluetooth and P2P separately.
        mBTFileOperator?.setFileReceiveListener(bleFileReceiveListener)
        mFileOperator?.setFileReceiveListener(p2pFileReceiveListener)

        // 注册一条离线语音命令，页面销毁时会解除注册。
        // Register an offline voice command and unregister it when the page is destroyed.
        huoVoiceAction = VoiceAction(demoString(R.string.demo_hazard_detected), "fa xian yin huan", object : IVoiceCallback.Stub() {
            override fun onVoiceTriggered() {
                Log.e(TAG, "发现隐患")
            }
        })
        GlassSdk.getGlassOfflineCmdService()?.add(huoVoiceAction)

        menuButtons().forEachIndexed { index, button ->
            button.setOnClickListener {
                unSelectBtn(menuButtons()[selectBtnStatus])
                selectBtnStatus = index
                selectBtn(button)
                toClick()
            }
        }

        GlassSdk.getGlassMessageService()?.setMessageListener(mMessageListener)

    }

    /**
     * 执行当前选中菜单项。
     * Execute the currently selected menu item.
     *
     * 该方法只负责按按钮 id 分发功能；眼镜点击键和键盘 Enter 最终都会进入这里。
     * This method only dispatches actions by button ID; both the glasses click key and keyboard Enter eventually reach it.
     */
    private fun toClick() {
        when (currentSelectId) {
            R.id.btnOnlineTts -> playOnlineTts()
            R.id.btnStopOnlineTts -> stopTts(online = true)

            R.id.btnOfflineTts -> playOfflineTts()
            R.id.btnStopOfflineTts -> stopTts(online = false)

            R.id.btnOnlineAsr -> startAsr()
            R.id.btnTtsQueueMode -> toggleTtsQueueMode()

            R.id.wfSendTextBtn -> {
                GlassSdk.getGlassMessageService()?.sendTextMessageByP2P(demoString(R.string.demo_p2p_test_message_from_glasses))
                log(demoString(R.string.demo_p2p_test_message_from_glasses))
            }

            R.id.wfSendFileBtn -> {
                val fileToSend1 = File(sdDownload, a1File)
                val fileToSend2 = File(sdDownload, a2File)
                logBuilder.clear()
                log(demoString(R.string.demo_preparing_to_send_file_via_p2p))
                // 连续操作时交替发送两个文件，方便验证不同大小/格式的传输。
                // Alternate between the two files on successive operations to verify transfers of different sizes and formats.
                if (randomFile) {
                    randomFile = false
                    Log.e(TAG, "文件目录: ${fileToSend1.absolutePath}")
                    curFile = fileToSend1
                    mFileOperator?.sendFile("", fileToSend1.absolutePath, p2pFileReceiveListener, mResultCallback)
                } else {
                    randomFile = true
                    Log.e(TAG, "文件目录: ${fileToSend2.absolutePath}")
                    curFile = fileToSend2
                    mFileOperator?.sendFile("", fileToSend2.absolutePath, p2pFileReceiveListener, mResultCallback)
                }
            }

            R.id.btSendTextBtn -> {
                GlassSdk.getGlassMessageService()?.sendTextMessageByClassicBT(demoString(R.string.demo_bluetooth_test_message_from_glasses))
                log(demoString(R.string.demo_bluetooth_test_message_from_glasses))
            }

            R.id.btSendFileBtn -> {
                val fileToSend1 = File(sdDownload, a1File)
                val fileToSend2 = File(sdDownload, a2File)
                logBuilder.clear()
                log(demoString(R.string.demo_preparing_to_send_file_via_bluetooth))
                // 与 P2P 示例保持一致，交替发送两个 Assets 示例文件。
                // As in the P2P example, alternate between the two sample assets.
                if (randomFile) {
                    randomFile = false
                    curFile = fileToSend1
                    Log.e(TAG, "蓝牙发送---->文件目录: ${fileToSend1.absolutePath}")
                    mBTFileOperator?.sendFile("", fileToSend1.absolutePath, bleFileReceiveListener, mResultCallback)
                } else {
                    randomFile = true
                    curFile = fileToSend2
                    Log.e(TAG, "蓝牙发送---->文件目录: ${fileToSend2.absolutePath}")
                    mBTFileOperator?.sendFile("", fileToSend2.absolutePath, bleFileReceiveListener, mResultCallback)
                }
            }

            R.id.btSendAudioStream -> {
                startAudioStreamRemotely()
            }

            R.id.btStopSendAudioStream -> {
                stopAudioStreamLocally(notifyPhone = true)
            }

            R.id.btSendVideo, R.id.btStopSendVideo -> mediaActions.value.perform(currentSelectId)

        }
    }

    private val mMessageListener = object : IMessageListener.Stub() {
        override fun onTextMessage(msg: String) {
            log(msg)
        }

        override fun onAudioStream(buffer: ByteArray) {
            log(demoString(R.string.demo_audio_stream_received_bytes, buffer.size))
            audioTrack.write(buffer, 0, buffer.size)
        }

        override fun onStreamDataReceived(tag: String, data: ByteArray) {
            log(demoString(R.string.demo_onstreamdatareceived_tag_status_26, data.size, tag))
        }
    }

    private fun startAsr() {
        if (onlineAsrSession.id != 0L) {
            val stopped = stopOnlineAsr()
            log(if (stopped) demoString(R.string.demo_online_asr_stop_requested_tap_again_to_start) else demoString(R.string.demo_failed_to_stop_online_asr_tap_again_to))
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            startAsrAfterPermissionGranted = true
            log(demoString(R.string.demo_microphone_permission_is_required_for_speech_recognition))
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO_PERMISSION
            )
            return
        }

        if (!GlassSdk.isReady()) {
            log(demoString(R.string.demo_sdk_initialization_is_incomplete_try_again_later))
            return
        }

        if (!checkSpeechNetwork(demoString(R.string.demo_online_asr))) return

        val asrService = GlassSdk.getGlassAsrService()
        if (asrService == null) {
            log(OnlineAsrStatusMessages.serviceUnavailable)
            return
        }

        try {
            val token = onlineAsrSession.begin()
            binding.btnOnlineAsr.text = demoString(R.string.demo_stop_asr)
            log(OnlineAsrStatusMessages.connecting)
            scheduleOnlineAsrTimeout()
            asrService.startSpeech(speechCallback(token))
        } catch (error: SecurityException) {
            stopOnlineAsr()
            log(demoString(R.string.demo_online_asr_authentication_or_permission_failed_check_lingmou))
        } catch (error: Exception) {
            stopOnlineAsr()
            log(demoString(R.string.demo_failed_to_start_online_asr_check_the_network, error.message.orEmpty()))
        }
    }

    private fun scheduleOnlineAsrTimeout() {
        cancelOnlineAsrTimeout()
        onlineAsrTimeoutJob = lifecycleScope.launch {
            delay(ONLINE_ASR_TIMEOUT_MS)
            onlineAsrTimeoutJob = null
            if (onlineAsrSession.starting) {
                stopOnlineAsr()
                log(OnlineAsrStatusMessages.connectionTimeout)
            }
        }
    }

    private fun cancelOnlineAsrTimeout() {
        onlineAsrTimeoutJob?.cancel()
        onlineAsrTimeoutJob = null
    }

    private fun stopOnlineAsr(): Boolean {
        cancelOnlineAsrTimeout()
        val stopped = onlineAsrSession.cancel {
            runCatching {
                val service = GlassSdk.getGlassAsrService() ?: return@runCatching false
                service.stopSpeech()
                true
            }.getOrDefault(false)
        }
        binding.btnOnlineAsr.text = if (stopped) demoString(R.string.demo_online_asr_2) else demoString(R.string.demo_retry_stopping_asr)
        return stopped
    }

    private fun playOnlineTts() {
        logTtsQueueMode()
        messageTts.speak(online = true, text = ONLINE_TTS_DEMO_TEXT, queueMode = ttsQueueMode)
    }

    private fun playOfflineTts() {
        logTtsQueueMode()
        messageTts.speak(online = false, text = OFFLINE_TTS_DEMO_TEXT, queueMode = ttsQueueMode)
    }

    private fun stopTts(online: Boolean) {
        logTtsQueueMode()
        messageTts.cancelOwned(online)
    }

    private fun toggleTtsQueueMode() {
        ttsQueueMode = if (ttsQueueMode == TtsQueueMode.ADD) TtsQueueMode.FLUSH else TtsQueueMode.ADD
        binding.btnTtsQueueMode.setText(if (ttsQueueMode == TtsQueueMode.ADD)
            R.string.message_tts_mode_add else R.string.message_tts_mode_flush)
        logTtsQueueMode()
    }

    private fun logTtsQueueMode() {
        val append = ttsQueueMode == TtsQueueMode.ADD
        val mode = getString(if (append) R.string.message_tts_mode_add else R.string.message_tts_mode_flush)
        val explanation = getString(if (append) R.string.message_tts_add_explained else R.string.message_tts_flush_explained)
        log("$mode\n$explanation")
    }

    override fun onStart() {
        super.onStart()
        messageTts.onStart()
    }

    override fun onStop() {
        if (mediaActions.isInitialized()) mediaActions.value.stopOwnedOperations()
        stopOnlineAsr()
        messageTts.onStop()
        super.onStop()
    }

    private fun checkSpeechNetwork(feature: String): Boolean {
        val route = runCatching { GlassSdk.getGlassDeviceService()?.getNetworkType() }.getOrNull()
        // 未知的服务/网络状态不能当作已确认离线。
        // Unknown service/network state does not confirm an offline route.
        val localInternet: Boolean? = if (route == 1) runCatching {
            val cm = getSystemService(ConnectivityManager::class.java)
            val network = cm?.activeNetwork
            if (cm == null) null
            else if (network == null) false
            else cm.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrNull() else null
        val policy = com.rokid.glass.speech.SpeechNetworkPolicy
        log(demoString(R.string.demo__status_27, feature, policy.hint(route)))
        if (!policy.canAttempt(route, localInternet)) {
            log(demoString(R.string.demo_no_network_available_in_direct_mode_connect_glasses, feature))
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO_PERMISSION) {
            return
        }

        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (granted && startAsrAfterPermissionGranted) {
            startAsrAfterPermissionGranted = false
            startAsr()
        } else {
            startAsrAfterPermissionGranted = false
            log(demoString(R.string.demo_microphone_permission_denied_speech_recognition_is_unavailable))
        }
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        when (keyEvent) {
            GlassKeyEvent.KEYCODE_FRONT -> {
                moveSelect(1)
            }

            GlassKeyEvent.KEYCODE_BEHIND -> {
                moveSelect(-1)
            }

            GlassKeyEvent.KEYCODE_CLICK -> {
                toClick()
            }

            GlassKeyEvent.KEYCODE_BACK, GlassKeyEvent.KEYCODE_DOUBLE_CLICK -> finish()
            else -> return super.onGlassKeyEvent(keyEvent)
        }
        return true
    }

    private fun menuButtons(): List<AppCompatTextView> {
        return listOf(
            binding.btnTtsQueueMode,
            binding.btnOnlineAsr,
            binding.btnOnlineTts,
            binding.btnStopOnlineTts,
            binding.btnOfflineTts,
            binding.btnStopOfflineTts,
            binding.wfSendTextBtn,
            binding.wfSendFileBtn,
            binding.btSendTextBtn,
            binding.btSendFileBtn,
            binding.btSendAudioStream,
            binding.btStopSendAudioStream,
            binding.btSendVideo, binding.btStopSendVideo
        )
    }

    private fun moveSelect(step: Int) {
        val buttons = menuButtons()
        if (buttons.isEmpty()) {
            return
        }
        val oldSelectIndex = selectBtnStatus.coerceIn(0, buttons.lastIndex)
        unSelectBtn(buttons[oldSelectIndex])
        selectBtnStatus = (oldSelectIndex + step + buttons.size) % buttons.size
        selectBtn(buttons[selectBtnStatus])
    }

    /**
     * 清除按钮焦点并恢复未选中的视觉状态。
     * Clear button focus and restore its unselected appearance.
     */
    private fun unSelectBtn(textView: AppCompatTextView) {
        textView.isSelected = false
        textView.isFocusable = false
        textView.isFocusableInTouchMode = false
        textView.clearFocus()
        textView.setTextColor(ContextCompat.getColor(this, R.color.green_70))
        textView.setBackgroundResource(R.drawable.round_unselect_bg)
    }

    /**
     * 记录并聚焦当前按钮，同时更新眼镜菜单的选中样式。
     * Record and focus the current button, and update the glasses menu selection style.
     */
    private fun selectBtn(textView: AppCompatTextView) {
        textView.isSelected = true
        textView.isClickable = true
        textView.isFocusable = true
        textView.isFocusableInTouchMode = true
        textView.setTextColor(ContextCompat.getColor(this, R.color.green))
        currentSelectId = textView.id
        textView.requestFocus()
        textView.setTextColor(ContextCompat.getColor(this, R.color.green))
        textView.setBackgroundResource(R.drawable.round_select_bg)
        textView.post {
            textView.requestRectangleOnScreen(Rect(0, 0, textView.width, textView.height), true)
        }
    }

    /**
     * 消息或文件发送请求提交到服务端后的结果回调。
     * Result callback after a message or file transfer request is submitted to the server.
     */
    private val mResultCallback = object : IResultCallback.Stub() {
        override fun onSuccess(result: Boolean) {
            Log.d(TAG, "mResultCallback onSuccess() result: $result")
        }

        override fun onFailed(code: Int, errormsg: String?) {
            Log.d(TAG, "mResultCallback onFailed(), code: $code, errormsg: $errormsg")
        }
    }

    private val logBuilder = StringBuilder(4000)

    /**
     * 将最新日志插入顶部，并同步显示到页面日志区域。
     * Insert the latest log at the top and update the log area on the page.
     */
    private fun log(msg: String) {
        Log.d(TAG, msg)
        speechHandler.post {
            if (isDestroyed) return@post
            if (logBuilder.length > 4000) logBuilder.clear()
            logBuilder.insert(0, "$msg\n")
            binding.tvLog.text = logBuilder.toString()
        }
    }

    /**
     * 将 Assets 中的演示文件复制到公共 Download 目录，供文件发送接口读取。
     * Copy demo assets to the public Download directory so the file transfer API can read them.
     */
    fun copyFileFromAssetsToExternalStorage(context: Context, fileName: String) {
        var inputStream: InputStream? = null
        var outputStream: OutputStream? = null
        try {
            inputStream = context.assets.open(fileName)
            val outputFile = File(sdDownload, fileName)
            outputStream = FileOutputStream(outputFile)
            inputStream.copyTo(outputStream)
            outputStream.flush()
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            inputStream?.close()
            outputStream?.close()
        }
    }

    override fun onDestroy() {
        if (mediaActions.isInitialized()) mediaActions.value.stopOwnedOperations()
        stopAudioStreamLocally(notifyPhone = true)
        cancelOnlineAsrTimeout()
        super.onDestroy()
        // 页面退出时解除监听并停止仍在运行的语音任务，避免回调持有 Activity。
        // Unregister listeners and stop ongoing speech tasks when leaving the page to avoid callbacks retaining the Activity.
        lifecycleScope.cancel()
        GlassSdk.getGlassMessageService()?.removeMessageListener(mMessageListener)
        mFileOperator?.removeFileReceiveListener(bleFileReceiveListener)
        mBTFileOperator?.removeFileReceiveListener(bleFileReceiveListener)
        stopOnlineAsr()
        messageTts.close()
        speechHandler.removeCallbacksAndMessages(null)
        if (::huoVoiceAction.isInitialized) {
            GlassSdk.getGlassOfflineCmdService()?.remove(huoVoiceAction)
        }
    }

    private fun stopAudioStreamLocally(notifyPhone: Boolean) {
        if (!audioStreamSession.markStopped()) {
            Log.d(TAG, "stopAudioStreamLocally: audio stream is not active")
            return
        }
        val messageService = GlassSdk.getGlassMessageService()
        messageService?.stopAudioStreamData()
        if (notifyPhone) {
            messageService?.sendTextMessageByClassicBT(
                AudioStreamControl.encode(gson, AudioStreamControl.Action.STOP)
            )
        }
        log(demoString(R.string.demo_glasses_stopped_sending_the_audio_stream))
    }

    private fun startAudioStreamRemotely() {
        val messageService = GlassSdk.getGlassMessageService() ?: run {
            log(demoString(R.string.demo_message_service_is_not_initialized_cannot_request_an))
            return
        }
        if (!audioStreamSession.markStarted()) {
            log(demoString(R.string.demo_audio_stream_already_requested))
            return
        }
        messageService.sendTextMessageByClassicBT(
            AudioStreamControl.encode(gson, AudioStreamControl.Action.START)
        )
        log(demoString(R.string.demo_requested_phone_to_start_receiving_audio))
    }

}
