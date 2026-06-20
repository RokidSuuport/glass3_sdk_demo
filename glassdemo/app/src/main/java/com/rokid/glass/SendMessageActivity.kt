package com.rokid.glass

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
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
import com.rokid.glass.camera.QuickCameraManager
import com.rokid.glass.data.GlobalData
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
import com.rokid.security.glass3.sdk.base.data.media.PhotoResolution
import com.rokid.security.glass3.sdk.base.data.offlineCmd.bean.VoiceAction
import com.rokid.security.glass3.sdk.base.data.offlineCmd.listener.IVoiceCallback
import com.rokid.security.system.server.asr.listener.SpeechCallback
import com.rokid.security.system.server.device.listener.IAppVisibilityListener
import com.rokid.security.system.server.media.callback.AudioCallback
import com.rokid.security.system.server.media.callback.PhotoFileCallback
import com.rokid.security.system.server.message.callback.IResultCallback
import com.rokid.security.system.server.message.file.listener.FileReceiveListener
import com.rokid.security.system.server.message.listener.IMessageListener
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.resume

/**
 * 眼镜端 SDK 功能演示页面。
 *
 * 集中演示 TTS/ASR、P2P 与蓝牙消息、文件传输、音频流、相机共享、
 * 二维码识别和应用可见性配置。页面通过眼镜前后键切换功能，点击键执行当前功能。
 */
class SendMessageActivity : BaseActivity() {

    companion object {
        private const val REQUEST_RECORD_AUDIO_PERMISSION = 1001
    }

    // 当前获得焦点的功能按钮 id，点击事件通过该 id 分发到对应 SDK 功能。
    private var currentSelectId: Int = -1

    // 文件发送时在两个示例文件之间交替选择。
    private var randomFile = false
    private val TAG = "SendMessageActivity"
    private lateinit var binding: ActivitySendmessageBinding

    // 记录文件开始发送时间，用于在完成回调中计算耗时。
    private var startTime = 0L

    // 眼镜按键菜单当前位置，范围为 0..12。
    private var selectBtnStatus = 0

    // Assets 中的示例文件会复制到公共 Download 目录，再用于传输测试。
    private val sdDownload = File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS)

    private var handlerLprCount = 1
    private lateinit var huoVoiceAction: VoiceAction
    private var startAsrAfterPermissionGranted = false

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
        selectBtn(binding.btTts)

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
     * 将 SDK 的回调式拍照接口包装为挂起函数，便于协程按顺序等待拍照结果。
     */
    private suspend fun takePhotoAndAwait(): String = suspendCancellableCoroutine { continuation ->
        Log.d(TAG, "-------------调用了拍照方法, handlerLprCount:$handlerLprCount")
        val file = QuickCameraManager.createImageFile()
        if (file == null) {
            continuation.resume("创建图片文件失败") // 创建图片文件失败，直接恢复协程
            return@suspendCancellableCoroutine
        }
        val photoFileCallback = object : PhotoFileCallback.Stub() {
            override fun onTakePhoto(path: String) {
                L.d(TAG, "onTakePhoto-->path = $path")
                // 拍照成功，恢复协程并传递结果
                GlassSdk.getGlassMediaService()?.removePhotoCallback(this)
                continuation.resume("ok")
            }

            override fun getCallbackId(): String? {
                return "10002"
            }

            override fun onTakePhotoV2(path: String, width: Int, height: Int) {
//                L.d(TAG, "onTakePhoto--> width = $width,height = $height, path = $path")
//                // 拍照成功（带分辨率信息），恢复协程并传递结果
//                continuation.resume("")
//                GlassSdk.getGlassMediaService()?.removePhotoCallback(this)
            }
        }
        GlassSdk.getGlassMediaService()?.addPhotoCallback(photoFileCallback)
        // 调用拍照接口
        GlassSdk.getGlassMediaService()?.takePhoto(PhotoResolution.RESOLUTION_1080P, file.absolutePath)
    }


    /**
     * 执行当前选中菜单项。
     *
     * 该方法只负责按按钮 id 分发功能；眼镜点击键和键盘 Enter 最终都会进入这里。
     */
    private fun toClick() {
        when (currentSelectId) {
            R.id.btTts -> {
                // TODO 在线tts 文本转语音播放，语音播报的时候记得别开投屏，不然眼镜会把投屏的设备当成音响，声音就跑偏了。
                val str = "秋天不回来，我要去爬山啦"
                GlassSdk.getGlassTtsService()?.doSpeechTts(str)

                // TODO 离线tts 文本转语音播放，语音播报的时候记得别开投屏，不然眼镜会把投屏的设备当成音响，声音就跑偏了。
//                GlassSdk.getGlassOfflineTtsService()?.playTtsMsg(str)
                log(str)
            }

            R.id.btAsr -> {
                startAsr()
            }

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
//                GlassSdk.getGlassMessageService()?.sendAudioStreamData()
                if (!MyApplication.sendAudioStatus) {
                    log("发送音频流数据")
                    GlassSdk.getGlassMediaService()?.startAudioRecord(audioRecord)
                    MyApplication.sendAudioStatus = true
                }
            }

            R.id.btStopSendAudioStream -> {
                MyApplication.sendAudioStatus = false
                log("停止发送音频流数据")
//                GlassSdk.getGlassMessageService()?.stopAudioStreamData()
                GlassSdk.getGlassMediaService()?.stopAudioRecord(audioRecord)
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
                    GlassSdk.getGlassDeviceService()?.configureAppVisibility(GlassAppConfig(appList,thirdList), object : IAppVisibilityListener.Stub() {
                        override fun onResult(success: Boolean) {
                            L.d(TAG, "--------setAppVisibility=${success}")
                        }
                    })
                } else {
                    GlassSdk.getGlassDeviceService()?.configureAppVisibility(GlassAppConfig(), object : IAppVisibilityListener.Stub() {
                        override fun onResult(success: Boolean) {
                            L.d(TAG, "--------setAppVisibility=${success}")
                        }
                    })
                }
                isHide = !isHide
            }
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

        // 释放本页面可能正在进行的录音，再启动 ASR，避免麦克风资源冲突。
        GlassSdk.getGlassMediaService()?.stopAudioRecord(audioRecord)
        MyApplication.sendAudioStatus = false
        asrService.stopSpeech()
        log("正在启动语音转文本...")
        asrService.startSpeech(speechCallback)
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
     * 麦克风 PCM 数据回调。
     *
     * 录音开启后，将每一段有效音频通过消息服务发送给已连接的手机端。
     */
    private val audioRecord = object : AudioCallback.Stub() {
        override fun onAudioStream(buffer: ByteArray?, bufferLen: Int) {
            if (buffer == null || bufferLen == 0) {
                return
            }
            if (!GlobalData.btConnectState.value) {
                return
            }
            /**
             * 发送二进制流数据
             * @param tag 业务标记
             * @param data 音频数据
             * @param clientId 手机客户端id
             * @param callback 发送数据流成功或失败的回调
             */
            GlassSdk.getGlassMessageService()
                ?.sendStreamData("AudioTag1", buffer, "GlassSample", object : IResultCallback.Stub() {
                    override fun onSuccess(result: Boolean) {
//                    Log.i(TAG, "发送带业务标记的音频数据流数成功")
                        log("发送带业务标记的音频数据流数成功")
                    }

                    override fun onFailed(code: Int, errormsg: String?) {
                        Log.i(TAG, "发送带业务标记的音频数据流数失败:code=${code}，errormsg=${errormsg}")
                        log("发送带业务标记的音频数据流数失败:code=${code},errormsg=${errormsg}")
                    }

                })
        }

        override fun getCallbackId(): String? {
            return "SendMessageRecord"
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
                // 向前键移动到下一个功能，末尾自动回到第一个。
                selectBtnStatus++
                selectBtnStatus %= 13
                when (selectBtnStatus) {
                    1 -> {
                        unSelectBtn(binding.btTts)
                        selectBtn(binding.btAsr)
                    }

                    2 -> {
                        unSelectBtn(binding.btAsr)
                        selectBtn(binding.wfSendTextBtn)
                    }

                    3 -> {
                        unSelectBtn(binding.wfSendTextBtn)
                        selectBtn(binding.wfSendFileBtn)
                    }

                    4 -> {
                        unSelectBtn(binding.wfSendFileBtn)
                        selectBtn(binding.btSendTextBtn)
                    }

                    5 -> {
                        unSelectBtn(binding.btSendTextBtn)
                        selectBtn(binding.btSendFileBtn)
                    }

                    6 -> {
                        unSelectBtn(binding.btSendFileBtn)
                        selectBtn(binding.btSendAudioStream)
                    }

                    7 -> {
                        unSelectBtn(binding.btSendAudioStream)
                        selectBtn(binding.btStopSendAudioStream)
                    }

                    8 -> {
                        unSelectBtn(binding.btStopSendAudioStream)
                        selectBtn(binding.btCameraShare)
                    }

                    9 -> {
                        unSelectBtn(binding.btCameraShare)
                        selectBtn(binding.btQRCodeImage)
                    }

                    10 -> {
                        unSelectBtn(binding.btQRCodeImage)
                        selectBtn(binding.btQRCode)
                    }

                    11 -> {
                        unSelectBtn(binding.btQRCode)
                        selectBtn(binding.btQRCodeXml)
                    }

                    12 -> {
                        unSelectBtn(binding.btQRCodeXml)
                        selectBtn(binding.btConfigureAppVisibility)
                    }

                    0 -> {
                        unSelectBtn(binding.btConfigureAppVisibility)
                        selectBtn(binding.btTts)
                    }
                }
            }

            GlassKeyEvent.KEYCODE_BEHIND -> {
                // 向后键移动到上一个功能，在第一个位置继续后退时跳到末尾。
                selectBtnStatus--
                if (selectBtnStatus < 0) {
                    selectBtnStatus = 12
                }
                selectBtnStatus %= 13
                when (selectBtnStatus) {
                    1 -> {
                        unSelectBtn(binding.wfSendTextBtn)
                        selectBtn(binding.btAsr)
                    }

                    2 -> {
                        unSelectBtn(binding.wfSendFileBtn)
                        selectBtn(binding.wfSendTextBtn)
                    }

                    3 -> {
                        unSelectBtn(binding.btSendTextBtn)
                        selectBtn(binding.wfSendFileBtn)
                    }

                    4 -> {
                        unSelectBtn(binding.btSendFileBtn)
                        selectBtn(binding.btSendTextBtn)
                    }

                    5 -> {
                        unSelectBtn(binding.btSendAudioStream)
                        selectBtn(binding.btSendFileBtn)
                    }

                    6 -> {
                        unSelectBtn(binding.btStopSendAudioStream)
                        selectBtn(binding.btSendAudioStream)
                    }

                    7 -> {
                        unSelectBtn(binding.btCameraShare)
                        selectBtn(binding.btStopSendAudioStream)
                    }

                    8 -> {
                        unSelectBtn(binding.btQRCodeImage)
                        selectBtn(binding.btCameraShare)
                    }

                    9 -> {
                        unSelectBtn(binding.btQRCode)
                        selectBtn(binding.btQRCodeImage)
                    }

                    10 -> {
                        unSelectBtn(binding.btQRCodeXml)
                        selectBtn(binding.btQRCode)
                    }

                    11 -> {
                        unSelectBtn(binding.btConfigureAppVisibility)
                        selectBtn(binding.btQRCodeXml)
                    }

                    12 -> {
                        unSelectBtn(binding.btTts)
                        selectBtn(binding.btConfigureAppVisibility)
                    }

                    0 -> {
                        unSelectBtn(binding.btAsr)
                        selectBtn(binding.btTts)
                    }
                }
            }

            GlassKeyEvent.KEYCODE_CLICK -> {
                // 点击键执行当前焦点对应的功能。
                toClick()
            }
        }
        return super.onGlassKeyEvent(keyEvent)
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
        super.onDestroy()
        // 页面退出时解除监听并停止仍在运行的语音、录音任务，避免回调持有 Activity。
        lifecycleScope.cancel()
        GlassSdk.getGlassMessageService()?.removeMessageListener(mMessageListener)
        mFileOperator?.removeFileReceiveListener(bleFileReceiveListener)
        mBTFileOperator?.removeFileReceiveListener(bleFileReceiveListener)
        GlassSdk.getGlassAsrService()?.stopSpeech()
        GlassSdk.getGlassMediaService()?.stopAudioRecord(audioRecord)
        MyApplication.sendAudioStatus = false
        if (::huoVoiceAction.isInitialized) {
            GlassSdk.getGlassOfflineCmdService()?.remove(huoVoiceAction)
        }
    }



}
