package com.rokid.glass

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.KeyEvent
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.common.Barcode
import com.rokid.glass.base.BaseActivity
import com.rokid.glass.base.GlassKeyEvent
import com.rokid.glass.utils.FileSizeUtil
import com.rokid.glass.utils.FileUtils
import com.rokid.glesse.R
import com.rokid.glesse.databinding.ActivitySendmessageBinding
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.uitls.log.L
import com.rokid.security.glass3.qrcode.api.GlassScanCallback
import com.rokid.security.glass3.qrcode.api.GlassScanner
import com.rokid.security.glass3.qrcode.model.GlassScanConfig
import com.rokid.security.glass3.qrcode.model.ScanType
import com.rokid.security.glass3.sdk.base.data.device.bean.GlassAppConfig
import com.rokid.security.glass3.sdk.base.data.device.bean.GlassAppType
import com.rokid.security.glass3.sdk.base.data.device.bean.ThirdPartyApp
import com.rokid.security.glass3.sdk.base.data.device.wifi.WifiConnectRequest
import com.rokid.security.glass3.sdk.base.data.device.wifi.WifiNetworkInfo
import com.rokid.security.glass3.sdk.base.data.device.wifi.WifiOperationCode
import com.rokid.security.glass3.sdk.base.data.device.wifi.WifiOperationStage
import com.rokid.security.glass3.sdk.base.data.device.wifi.WifiRemoveRequest
import com.rokid.security.glass3.sdk.base.data.offlineCmd.bean.VoiceAction
import com.rokid.security.glass3.sdk.base.data.offlineCmd.listener.IVoiceCallback
import com.rokid.security.system.server.asr.listener.SpeechCallback
import com.rokid.security.system.server.device.listener.IAppVisibilityListener
import com.rokid.security.system.server.device.listener.IWifiOperationCallback
import com.rokid.security.system.server.message.callback.IResultCallback
import com.rokid.security.system.server.message.file.listener.FileReceiveListener
import com.rokid.security.system.server.message.listener.IMessageListener
import com.rokid.security.system.server.tts.listener.SpeechCompleteListener
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 眼镜端 SDK 功能演示页面。
 *
 * 集中演示 TTS/ASR、P2P 与蓝牙消息、文件传输、音频流、相机共享、
 * 二维码识别和应用可见性配置。页面通过眼镜前后键切换功能，点击键执行当前功能。
 */
class SendMessageActivity : BaseActivity() {

    companion object {
        private const val REQUEST_RECORD_AUDIO_PERMISSION = 1001
        private const val AUDIO_STREAM_START = "AUDIO_STREAM_START"
        private const val AUDIO_STREAM_STOP = "AUDIO_STREAM_STOP"
        private const val ONLINE_TTS_DEMO_TEXT = "这是在线TTS语音播报"
        private const val OFFLINE_TTS_DEMO_TEXT = "这是离线TTS语音播报"
        private const val ONLINE_TTS_TIMEOUT_MS = 15_000L
        private const val DEMO_WIFI_SSID = "EBG-RD"
        private const val DEMO_WIFI_PASSWORD = "Rkd2023/"
        private const val DEMO_WIFI_HIDDEN = false
    }

    // 当前获得焦点的功能按钮 id，点击事件通过该 id 分发到对应 SDK 功能。
    private var currentSelectId: Int = -1

    // 文件发送时在两个示例文件之间交替选择。
    private var randomFile = false
    private val TAG = "SendMessageActivity"
    private lateinit var binding: ActivitySendmessageBinding

    // 记录文件开始发送时间，用于在完成回调中计算耗时。
    private var startTime = 0L

    // 眼镜按键菜单当前位置，对应 menuButtons() 中的下标。
    private var selectBtnStatus = 0

    // Assets 中的示例文件会复制到公共 Download 目录，再用于传输测试。
    private val sdDownload = File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS)

    private lateinit var huoVoiceAction: VoiceAction
    private var startAsrAfterPermissionGranted = false
    private var onlineTtsPending = false
    private var onlineTtsWaitingForConnection = false
    private var onlineTtsServiceConnected = false
    private var onlineTtsRequestId = 0

    private val ttsCompleteListener = object : SpeechCompleteListener.Stub() {
        override fun onComplete() {
            runOnUiThread {
                if (onlineTtsPending) {
                    onlineTtsPending = false
                    log("在线 TTS 播放完成")
                }
            }
        }

        override fun onServiceConnectState(connected: Boolean) {
            runOnUiThread {
                onlineTtsServiceConnected = connected
                if (connected && onlineTtsWaitingForConnection) {
                    log("在线 TTS 服务连接成功，开始播放")
                    dispatchOnlineTts()
                } else if (!connected && onlineTtsPending) {
                    onlineTtsPending = false
                    log("在线 TTS 服务连接失败：请检查网络及灵眸账号鉴权状态，SDK 未返回具体错误码")
                }
            }
        }
    }

    private val audioTrack = AudioTrack(
        AudioManager.STREAM_MUSIC,                // 音频流类型
        16000,                                    // 采样率（必须一致）
        AudioFormat.CHANNEL_OUT_MONO,             // 单声道（与录音一致）
        AudioFormat.ENCODING_PCM_16BIT,           // 编码格式（必须一致）
        AudioTrack.getMinBufferSize(              // 合理的缓冲区大小
            16000,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ),
        AudioTrack.MODE_STREAM                    // 流式模式（适合实时播放）
    )

    private val speechCallback = object : SpeechCallback.Stub() {
        override fun onStart() {
            log("语音转文本开始，请开始说话")
        }

        override fun onIntermediateVad(content: String) {
            log(content)
        }

        override fun onAsrComplete(content: String?) {
            val result = content.orEmpty()
            Log.i(TAG, "语音转文本完成: $result")
            log(if (result.isBlank()) "语音转文本完成，但未识别到内容" else result)
        }

        override fun onAsrCompleteWithIntent(content: String?, intent: Int, intentJson: String) {
            log("识别的内容=${content.orEmpty()},意图index=$intent,意图json=$intentJson")
        }

        override fun onError(code: Int) {
            log("语音转文本失败: code=$code")
        }

        override fun onServiceConnectState(connect: Boolean) {
            log("语音连接状态: $connect")
        }
    }

    // 标记下一次操作是隐藏指定应用，还是恢复默认应用可见性。
    private var isHide = true

    /**
     * 获取P2P发送文件的管理器
     * @return IGlassFileOperate
     */
    private val mFileOperator by lazy {
        GlassSdk.getGlassMessageService()?.glassFileOperater
    }

    /**
     * 获取蓝牙发送文件的管理器
     * @return IGlassFileOperate
     */
    private val mBTFileOperator by lazy {
        GlassSdk.getGlassMessageService()?.glassBtFileOperater
    }

    /**
     * 蓝牙文件发送状态回调。
     *
     * FileReceiveListener 名称虽然是 ReceiveListener，但发送方也通过它接收
     * 开始、进度、完成、失败和取消等传输状态。
     */
    private val bleFileReceiveListener = object : FileReceiveListener.Stub() {
        override fun onStart() {
            startTime = System.currentTimeMillis()
            Log.i(TAG, "onStart: 蓝牙本端开始发送文件")
            log("onStart: 蓝牙本端开始发送文件")
        }

        override fun onProgressChanged(progress: Float) {
            Log.i(TAG, "onProgressChanged: 蓝牙本端发送文件的进度 $progress")
            log("onProgressChanged: 蓝牙本端发送文件的进度 $progress")
        }

        /**
         * 眼睛端发送文件路径
         */
        override fun onComplete(filePath: String) {
            //手机端接收文件的路径 /storage/emulated/0/Android/data/com.rokid.phone/files/receiver/gonglu.png
            Log.i(TAG, "onComplete: 蓝牙本端发送文件完成,----$filePath")
            var duration = System.currentTimeMillis() - startTime
            val fileSize = FileSizeUtil.formatFileSize(FileSizeUtil.getFileSizeBytes(curFile).toDouble())
            if (duration > 1000) {
                duration = duration / 1000
                log("onComplete: 蓝牙本端发送文件完成，${duration}秒,${fileSize}")
            } else {
                log("onComplete: 蓝牙本端发送文件完成，${duration}毫秒,${fileSize}")
            }
        }

        override fun onFail() {
            Log.i(TAG, "onFail: 蓝牙本端发送文件失败")
            log("onFail: 蓝牙本端发送文件失败")
        }

        override fun onCancel() {
            Log.i(TAG, "onCancel: 蓝牙对方取消了发送文件")
            log("onCancel: 蓝牙对方取消了发送文件")
        }
    }

    /**
     * P2P 文件发送状态回调，负责输出进度并统计发送耗时和文件大小。
     */
    private val p2pFileReceiveListener = object : FileReceiveListener.Stub() {
        override fun onStart() {
            startTime = System.currentTimeMillis()
            Log.i(TAG, "onStart: p2p本端开始发送文件")
            log("onStart: p2p本端开始发送文件")
        }

        override fun onProgressChanged(progress: Float) {
            Log.i(TAG, "onProgressChanged: p2p本端发送文件的进度 $progress")
            log("onProgressChanged: p2p本端发送文件的进度 $progress")
        }

        /**
         * 眼睛端发送文件路径
         */
        override fun onComplete(filePath: String) {
            //手机端接收文件的路径 /storage/emulated/0/Android/data/com.rokid.phone/files/receiver/gonglu.png
            Log.i(TAG, "onComplete: p2p本端发送文件完成,----$filePath")
            var duration = System.currentTimeMillis() - startTime
            val fileSize = FileSizeUtil.formatFileSize(FileSizeUtil.getFileSizeBytes(curFile).toDouble())
            if (duration > 1000) {
                duration = duration / 1000
                log("onComplete: p2p本端发送文件完成，${duration}秒,${fileSize}")
            } else {
                log("onComplete: p2p本端发送文件完成，${duration}毫秒,${fileSize}")
            }
        }

        override fun onFail() {
            Log.i(TAG, "onFail: p2p本端发送文件失败")
            log("onFail: p2p本端发送文件失败")
        }

        override fun onCancel() {
            Log.i(TAG, "onCancel: p2p对方取消了发送文件")
            log("onCancel: p2p对方取消了发送文件")
        }
    }

    val a1File = "xiyi.jpg"
    val a2File = "gonglu.png"

    // 当前正在发送的文件，完成回调通过它统计源文件大小。
    var curFile = File("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.e(TAG, "初始化")
        binding = ActivitySendmessageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 提前准备文件传输演示使用的两个本地文件。
        copyFileFromAssetsToExternalStorage(this, a1File)
        copyFileFromAssetsToExternalStorage(this, a2File)

        // 默认选中第一个功能，确保眼镜点击键进入页面后可以直接操作。
        selectBtn(binding.btnOnlineTts)

        // 分别注册经典蓝牙和 P2P 文件传输状态监听。
        mBTFileOperator?.setFileReceiveListener(bleFileReceiveListener)
        mFileOperator?.setFileReceiveListener(p2pFileReceiveListener)

        // 注册一条离线语音命令，页面销毁时会解除注册。
        huoVoiceAction = VoiceAction("火箭人", "huo jian ren", object : IVoiceCallback.Stub() {
            override fun onVoiceTriggered() {
                Log.e(TAG, "火箭人")
            }
        })
        GlassSdk.getGlassOfflineCmdService()?.add(huoVoiceAction)

        // 同时兼容键盘和眼镜触控板映射的 Enter/左右方向键。
        binding.keyMark.setOnKeyListener { view, keyCode, event ->
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_ENTER -> {
                            Log.d(TAG, "setOnKeyListener 键盘回车事件或者触摸板点击事件")
                            toClick()
                        }

                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            Log.d(TAG, "setOnKeyListener 键盘往前键")
                            onGlassKeyEvent(GlassKeyEvent.KEYCODE_FRONT)
                        }

                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            Log.d(TAG, "setOnKeyListener 键盘往后键")
                            onGlassKeyEvent(GlassKeyEvent.KEYCODE_BEHIND)
                        }

                        KeyEvent.KEYCODE_BACK -> {
                            Log.d(TAG, "setOnKeyListener 鼠标右击返回事件")
                            finish()
                        }
                    }
                }
            }
            true
        }


        GlassSdk.getGlassMessageService()?.setMessageListener(mMessageListener)

    }

    /**
     * 执行当前选中菜单项。
     *
     * 该方法只负责按按钮 id 分发功能；眼镜点击键和键盘 Enter 最终都会进入这里。
     */
    private fun toClick() {
        when (currentSelectId) {
            R.id.btnOnlineTts -> playOnlineTts()

            R.id.btnOfflineTts -> playOfflineTts()

            R.id.btnOnlineAsr -> startAsr()

            R.id.wfSendTextBtn -> {
                GlassSdk.getGlassMessageService()?.sendTextMessageByP2P("眼睛端发送P2P测试的消息")
                log("眼睛端发送P2P测试的消息")
            }

            R.id.wfSendFileBtn -> {
                val fileToSend1 = File(sdDownload, a1File)
                val fileToSend2 = File(sdDownload, a2File)
                logBuilder.clear()
                log("p2p准备发送文件...")
                // 连续操作时交替发送两个文件，方便验证不同大小/格式的传输。
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
                GlassSdk.getGlassMessageService()?.sendTextMessageByClassicBT("眼睛端发送蓝牙测试消息")
                log("眼睛端发送蓝牙测试消息")
            }

            R.id.btSendFileBtn -> {
                val fileToSend1 = File(sdDownload, a1File)
                val fileToSend2 = File(sdDownload, a2File)
                logBuilder.clear()
                log("蓝牙准备发送文件...")
                // 与 P2P 测试保持一致，交替发送两个 Assets 示例文件。
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
                GlassSdk.getGlassMessageService()?.sendTextMessageByClassicBT(AUDIO_STREAM_START)
                log("请求手机端开始接收音频流")
            }

            R.id.btStopSendAudioStream -> {
                stopAudioStreamLocally(notifyPhone = true)
            }

            R.id.btCameraShare -> {
                val intent = Intent(this, CameraShareSelectActivity::class.java)
                startActivity(intent)
            }

            R.id.btQRCodeImage -> {
                val bitmap = FileUtils.loadBitmapFromAssets(this, "qrcode.jpg") ?: run {
                    log("加载二维码图片失败")
                    return
                }
                GlassScanner.scanFromBitmap(this, bitmap = bitmap, callback = object : GlassScanCallback {
                    override fun onScanFailure(error: String) {
                        log("扫描失败：${error}")
                    }

                    override fun onScanSuccess(content: String?, barcode: Barcode) {
                        log("扫描成功,内容为：${content},barcode=${barcode.rawValue}")
                    }
                })
            }

            R.id.btQRCode -> {
                // cameraZoomLevel 新增相机缩放参数默认值1，最大值10,参数越大越容易识别到二维码
                GlassScanner.launch(
                    this,
                    config = GlassScanConfig(enableAutoClose = true, scanType = ScanType.QR_CODE_ONLY, cameraZoomLevel = 5),
                    scanCallback = object : GlassScanCallback {
                        override fun onScanSuccess(content: String?, barcode: Barcode) {
                            log("扫描成功：${content}")
                        }

                        override fun onScanFailure(error: String) {
                            log("扫描失败：${error}")
                        }
                    })
            }

            R.id.btQRCodeXml -> {
                startActivity(Intent(this, ActivityQRView::class.java))
            }

            R.id.btConnectWifi -> {
                connectWifi()
            }

            R.id.btDisconnectWifi -> {
                disconnectWifi()
            }

            R.id.btConfigureAppVisibility -> {
                // 第一次点击隐藏指定系统应用并把三方应用排到最前面
                // 再次点击传入空配置，恢复设备默认的应用可见性。
                if (isHide) {
                    val appList = mutableListOf(
                        GlassAppType.AI_WORK_ASSISTANT, GlassAppType.AI_CHAT,
                        GlassAppType.AI_INSPECTION, GlassAppType.OFFLINE_FACE,
                        GlassAppType.TAKE_PHOTO, GlassAppType.XPERT,
                        GlassAppType.OFFLINE_PLATE, GlassAppType.HG_IDENTIFICATION
                    )
                    // 只要写了三方应用就会排到最前面
                    val thirdList = mutableListOf(ThirdPartyApp("com.rokid.glesse", "glassdemo"))
                    GlassSdk.getGlassDeviceService()
                        ?.configureAppVisibility(GlassAppConfig(appList, thirdList), object : IAppVisibilityListener.Stub() {
                            override fun onResult(success: Boolean) {
                                L.d(TAG, "--------setAppVisibility=${success}")
                            }
                        })
                } else {
                    GlassSdk.getGlassDeviceService()
                        ?.configureAppVisibility(GlassAppConfig(), object : IAppVisibilityListener.Stub() {
                            override fun onResult(success: Boolean) {
                                L.d(TAG, "--------setAppVisibility=${success}")
                            }
                        })
                }
                isHide = !isHide
            }
        }
    }

    /**
     * 连接指定 Wi-Fi。
     *
     * 修改 companion object 中的 DEMO_WIFI_SSID / DEMO_WIFI_PASSWORD 即可切换测试热点。
     * 空密码会按开放网络处理；非空密码默认使用自动 PSK 模式。
     */
    private fun connectWifi() {
        if (!checkDeviceServiceReady()) {
            return
        }

        val ssid = DEMO_WIFI_SSID.trim()
        if (ssid.isBlank()) {
            log("请先配置要连接的 Wi-Fi 名称")
            return
        }

        val currentWifiInfo = GlassSdk.getCurrentWifiInfo()
        if (currentWifiInfo?.status == WifiNetworkInfo.STATUS_CURRENT &&
            !currentWifiInfo.ssid.isNullOrBlank() && currentWifiInfo.ssid == ssid
        ) {
            log("当前已连接 Wi-Fi: ${currentWifiInfo.ssid}，不再重复连接")
            return
        }

        val password = DEMO_WIFI_PASSWORD.takeIf { it.isNotBlank() }
        val request = WifiConnectRequest(
            ssid = ssid,
            securityType = if (password == null) {
                WifiConnectRequest.SECURITY_OPEN
            } else {
                WifiConnectRequest.SECURITY_AUTO_PSK
            },
            password = password,
            hiddenSsid = DEMO_WIFI_HIDDEN
        )
        log("开始连接 Wi-Fi: $ssid")
        GlassSdk.connectWifi(request, createWifiOperationCallback("连接 Wi-Fi"))
    }

    /**
     * 断开当前 Wi-Fi。
     *
     * SDK 当前提供的是 removeWifi 接口，这里传 disconnectIfCurrent=true，
     * 会先断开当前连接，再移除对应的已保存配置。
     */
    private fun disconnectWifi() {
        if (!checkDeviceServiceReady()) {
            return
        }

        val currentWifiInfo = GlassSdk.getCurrentWifiInfo()
        if (currentWifiInfo?.ssid.isNullOrBlank()) {
            log("当前没有已连接或正在连接的 Wi-Fi")
            return
        }

        val request = WifiRemoveRequest(
            ssid = currentWifiInfo.ssid,
            bssid = currentWifiInfo.bssid,
            networkId = currentWifiInfo.networkId,
            disconnectIfCurrent = true
        )
        log("开始断开并移除 Wi-Fi: ${currentWifiInfo.ssid}")
        GlassSdk.removeWifi(request, createWifiOperationCallback("断开 Wi-Fi"))
    }

    private fun checkDeviceServiceReady(): Boolean {
        if (!GlassSdk.isReady()) {
            log("SDK 尚未初始化完成，请稍后重试")
            return false
        }
        if (GlassSdk.getGlassDeviceService() == null) {
            log("设备服务不可用，请检查眼镜系统服务和开发版授权")
            return false
        }
        return true
    }

    private fun createWifiOperationCallback(operationName: String) = object : IWifiOperationCallback.Stub() {
        override fun onProgress(stage: Int, message: String?) {
            val stageName = wifiStageName(stage)
            val detail = message.orEmpty().takeIf { it.isNotBlank() && it != stageName }
            log(if (detail == null) "$operationName 进度: $stageName" else "$operationName 进度: $stageName $detail")
        }

        override fun onResult(code: Int, message: String?, networkId: Int, ssid: String?) {
            if (code == WifiOperationCode.SUCCESS) {
                log("$operationName 成功: ssid=${ssid.orEmpty()}, networkId=$networkId")
            } else {
                log("$operationName 失败: code=$code(${wifiCodeName(code)}), msg=${message.orEmpty()}")
            }
        }
    }

    private fun wifiStageName(stage: Int): String {
        return when (stage) {
            WifiOperationStage.VALIDATING -> "校验参数"
            WifiOperationStage.CONFIGURING -> "配置网络"
            WifiOperationStage.CONNECTING -> "等待连接"
            WifiOperationStage.VERIFYING -> "校验连接"
            WifiOperationStage.REMOVING -> "移除配置"
            else -> "未知阶段"
        }
    }

    private fun wifiCodeName(code: Int): String {
        return when (code) {
            WifiOperationCode.SUCCESS -> "成功"
            WifiOperationCode.ERROR_INVALID_ARGUMENT -> "参数错误"
            WifiOperationCode.ERROR_PERMISSION_DENIED -> "权限不足"
            WifiOperationCode.ERROR_UNSUPPORTED_SECURITY -> "不支持的安全类型"
            WifiOperationCode.ERROR_WIFI_DISABLED -> "Wi-Fi 未开启"
            WifiOperationCode.ERROR_ADD_OR_UPDATE_FAILED -> "配置失败"
            WifiOperationCode.ERROR_AUTH_FAILED -> "认证失败"
            WifiOperationCode.ERROR_TIMEOUT -> "连接超时"
            WifiOperationCode.ERROR_NOT_FOUND -> "未找到配置"
            WifiOperationCode.ERROR_INTERNAL -> "内部错误"
            else -> "未知错误"
        }
    }

    private val mMessageListener = object : IMessageListener.Stub() {
        override fun onTextMessage(msg: String) {
            log(msg)
        }

        override fun onAudioStream(buffer: ByteArray) {
            log("收到音频流，大小=${buffer.size}")
            audioTrack.write(buffer, 0, buffer.size)
        }

        override fun onStreamDataReceived(tag: String, data: ByteArray) {
            log("onStreamDataReceived=${data.size},tag=${tag}")
        }
    }

    private fun startAsr() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            startAsrAfterPermissionGranted = true
            log("语音转文本需要麦克风权限")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO_PERMISSION
            )
            return
        }

        if (!GlassSdk.isReady()) {
            log("SDK 尚未初始化完成，请稍后重试")
            return
        }

        val asrService = GlassSdk.getGlassAsrService()
        if (asrService == null) {
            log("ASR 服务不可用，请检查眼镜系统服务和开发版授权")
            return
        }

        asrService.stopSpeech()
        log("正在启动语音转文本...")
        asrService.startSpeech(speechCallback)
    }

    private fun playOnlineTts() {
        if (!GlassSdk.isReady()) {
            return log("SDK 尚未初始化完成，请稍后重试")
        }
        if (!isNetworkAvailable()) {
            return log("在线 TTS 播放失败：当前无可用网络")
        }
        val service = GlassSdk.getGlassTtsService()
            ?: return log("在线 TTS 服务不可用")
        if (onlineTtsWaitingForConnection || onlineTtsPending) {
            return log("在线 TTS 请求处理中，请稍候")
        }
        val requestId = ++onlineTtsRequestId
        onlineTtsWaitingForConnection = true
        try {
            service.setSpeechCompleteListener(ttsCompleteListener)
            if (onlineTtsServiceConnected) {
                dispatchOnlineTts()
            } else {
                log("正在连接在线 TTS 服务，请稍候...")
            }
            lifecycleScope.launch {
                delay(ONLINE_TTS_TIMEOUT_MS)
                if (requestId != onlineTtsRequestId) {
                    return@launch
                }
                if (onlineTtsWaitingForConnection) {
                    onlineTtsWaitingForConnection = false
                    log("在线 TTS 服务连接超时：请检查网络及灵眸账号鉴权状态")
                } else if (onlineTtsPending) {
                    onlineTtsPending = false
                    log("在线 TTS 未收到完成回调：请检查网络、灵眸账号鉴权状态及声音路由")
                }
            }
        } catch (error: SecurityException) {
            onlineTtsWaitingForConnection = false
            onlineTtsPending = false
            log("在线 TTS 鉴权或权限失败：请检查灵眸账号登录和系统授权状态")
        } catch (error: Exception) {
            onlineTtsWaitingForConnection = false
            onlineTtsPending = false
            log(describeOnlineTtsFailure(error))
        }
    }

    private fun dispatchOnlineTts() {
        if (!onlineTtsWaitingForConnection) {
            return
        }
        val service = GlassSdk.getGlassTtsService() ?: run {
            onlineTtsWaitingForConnection = false
            log("在线 TTS 服务不可用")
            return
        }
        try {
            onlineTtsWaitingForConnection = false
            onlineTtsPending = true
            service.doSpeechTts(ONLINE_TTS_DEMO_TEXT)
            log("在线 TTS 请求已发送：$ONLINE_TTS_DEMO_TEXT")
        } catch (error: SecurityException) {
            onlineTtsPending = false
            log("在线 TTS 鉴权或权限失败：请检查灵眸账号登录和系统授权状态")
        } catch (error: Exception) {
            onlineTtsPending = false
            log(describeOnlineTtsFailure(error))
        }
    }

    private fun playOfflineTts() {
        if (!GlassSdk.isReady()) {
            return log("SDK 尚未初始化完成，请稍后重试")
        }
        val service = GlassSdk.getGlassOfflineTtsService()
            ?: return log("离线 TTS 服务不可用")
        try {
            service.playTtsMsg(OFFLINE_TTS_DEMO_TEXT)
            log("离线 TTS 请求已发送：$OFFLINE_TTS_DEMO_TEXT")
        } catch (error: SecurityException) {
            log("离线 TTS 权限失败：请检查系统授权状态")
        } catch (error: Exception) {
            log("离线 TTS 播放失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun describeOnlineTtsFailure(error: Exception): String {
        val message = error.message.orEmpty()
        val normalized = message.lowercase()
        return when {
            listOf("401", "403", "auth", "token", "api key", "apikey", "unauthorized", "forbidden")
                .any(normalized::contains) ->
                "在线 TTS 鉴权或权限失败：请检查灵眸账号登录和系统授权状态"
            listOf("network", "timeout", "connect", "socket", "host", "dns")
                .any(normalized::contains) ->
                "在线 TTS 网络异常：${message.ifBlank { error.javaClass.simpleName }}"
            else -> "在线 TTS 播放失败：${message.ifBlank { error.javaClass.simpleName }}"
        }
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
            log("麦克风权限被拒绝，无法使用语音转文本")
        }
    }

    /**
     * 先给 keyMark 处理
     * 如果没处理
     * 系统继续处理
     *
     *
     * dispatchKeyEvent	事件分发
     * onKeyDown	事件处理
     * onKeyUp	按键释放
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (binding.keyMark.dispatchKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
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
                // 点击键执行当前焦点对应的功能。
                toClick()
            }
        }
        return super.onGlassKeyEvent(keyEvent)
    }

    private fun menuButtons(): List<AppCompatTextView> {
        return listOf(
            binding.btnOnlineTts,
            binding.btnOfflineTts,
            binding.btnOnlineAsr,
            binding.wfSendTextBtn,
            binding.wfSendFileBtn,
            binding.btSendTextBtn,
            binding.btSendFileBtn,
            binding.btSendAudioStream,
            binding.btStopSendAudioStream,
            binding.btCameraShare,
            binding.btQRCodeImage,
            binding.btQRCode,
            binding.btQRCodeXml,
            binding.btConnectWifi,
            binding.btDisconnectWifi,
            binding.btConfigureAppVisibility
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
     */
    private fun unSelectBtn(textView: AppCompatTextView) {
        textView.isFocusable = false
        textView.isFocusableInTouchMode = false
        textView.clearFocus()
        textView.setTextColor(ContextCompat.getColor(this, R.color.green_70))
        textView.setBackgroundResource(R.drawable.round_unselect_bg)
    }

    /**
     * 记录并聚焦当前按钮，同时更新眼镜菜单的选中样式。
     */
    private fun selectBtn(textView: AppCompatTextView) {
        textView.isClickable = true
        textView.isFocusable = true
        textView.isFocusableInTouchMode = true
        textView.setTextColor(ContextCompat.getColor(this, R.color.green))
        currentSelectId = textView.id
        textView.requestFocus()
        textView.setTextColor(ContextCompat.getColor(this, R.color.green))
        textView.setBackgroundResource(R.drawable.round_select_bg)
    }

    /**
     * 消息或文件发送请求提交到服务端后的结果回调。
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
     */
    private fun log(msg: String) {
        if (logBuilder.length > 4000) {
            logBuilder.delete(0, logBuilder.length)
        }
        logBuilder.insert(0, "$msg\n")
        lifecycleScope.launch {
            binding.tvLog.text = logBuilder.toString()
        }
        Log.d(TAG, msg)
    }

    /**
     * 将 Assets 中的演示文件复制到公共 Download 目录，供文件发送接口读取。
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
            // 文件写入成功
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            inputStream?.close()
            outputStream?.close()
        }
    }


    override fun onDestroy() {
        stopAudioStreamLocally(notifyPhone = true)
        super.onDestroy()
        // 页面退出时解除监听并停止仍在运行的语音任务，避免回调持有 Activity。
        lifecycleScope.cancel()
        GlassSdk.getGlassMessageService()?.removeMessageListener(mMessageListener)
        mFileOperator?.removeFileReceiveListener(bleFileReceiveListener)
        mBTFileOperator?.removeFileReceiveListener(bleFileReceiveListener)
        GlassSdk.getGlassAsrService()?.stopSpeech()
        onlineTtsPending = false
        onlineTtsWaitingForConnection = false
        onlineTtsServiceConnected = false
        onlineTtsRequestId++
        runCatching { GlassSdk.getGlassTtsService()?.removeSpeechCompleteListener() }
        if (::huoVoiceAction.isInitialized) {
            GlassSdk.getGlassOfflineCmdService()?.remove(huoVoiceAction)
        }
    }

    private fun stopAudioStreamLocally(notifyPhone: Boolean) {
        val messageService = GlassSdk.getGlassMessageService()
        messageService?.stopAudioStreamData()
        if (notifyPhone) {
            messageService?.sendTextMessageByClassicBT(AUDIO_STREAM_STOP)
        }
        log("眼镜端已主动停止发送音频流")
    }


}
