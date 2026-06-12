package com.rokid.glass

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.rokid.glass.base.BaseActivity
import com.rokid.glass.camera.QuickCameraManager
import com.rokid.glesse.databinding.ActivityMessageReceiveBinding
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.sdk.base.data.notification.bean.NotificationMessage
import com.rokid.security.system.server.message.file.listener.FileReceiveListener
import com.rokid.security.system.server.message.listener.IMessageListener
import com.rokid.security.system.server.notification.listener.NotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MessageReceiveActivity : BaseActivity() {

    //    private val mFileOperater by lazy {
//        GlassSdk.getGlassMessageService()?.getGlassFileOperater()
//    }
    private lateinit var binding: ActivityMessageReceiveBinding
    private val TAG = "MessageActivity"

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

    private val mMessageListener = object : IMessageListener.Stub() {
        override fun onTextMessage(msg: String) {
            Log.e(TAG, msg)
            log(msg)
        }

        override fun onAudioStream(buffer: ByteArray) {
            Log.e(TAG, "收到 onAudioStream")
            log("收到音频流，大小=${buffer.size}")
            audioTrack.write(buffer, 0, buffer.size)
        }

        override fun onStreamDataReceived(tag: String, data: ByteArray) {
            Log.e(TAG, "onStreamDataReceived")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMessageReceiveBinding.inflate(layoutInflater)
        setContentView(binding.root)
        GlassSdk.getGlassMessageService()?.setMessageListener(mMessageListener)
        GlassSdk.getGlassMessageService()?.glassFileOperater?.setFileReceiveListener(mFileReceiveListener)
        GlassSdk.getGlassMessageService()?.glassBtFileOperater?.setFileReceiveListener(mBtFileReceiveListener)
        GlassSdk.getGlassNotificationService()?.setNotificationListener(mNotificationListener)
        audioTrack.play()
        binding.scrollView.requestFocus()
    }

    override fun onDestroy() {
        super.onDestroy()
        GlassSdk.getGlassMessageService()?.removeMessageListener(mMessageListener)
        GlassSdk.getGlassMessageService()?.glassFileOperater?.removeFileReceiveListener(mFileReceiveListener)
        GlassSdk.getGlassMessageService()?.glassBtFileOperater?.removeFileReceiveListener(mFileReceiveListener)
        GlassSdk.getGlassNotificationService()?.removeNotificationListener()
        audioTrack.stop()
        audioTrack.release()
    }

    private var mNotificationListener = object : NotificationListener.Stub() {
        /***
         * 通知到达
         * @param notification 通知消息
         * */
        override fun onNotificationReceived(notification: NotificationMessage?) {
            Log.d(TAG, "收到了通知")
            notification?.apply {
                log("$titleValue $msgValue")
            }
        }

        /***
         * 人脸识别通知
         * @param face1
         * @param face2
         * @param message 通知消息
         * */
        override fun onFaceRecognizeNotification(face1: ByteArray?, face2: ByteArray?, message: String?) {
            Log.d(TAG, "收到了人脸识别通知")
            face1?.let {
                QuickCameraManager.saveImage2(it)
            }
            face2?.let {
                QuickCameraManager.saveImage2(it)
            }
        }
    }

    private val logBuilder = StringBuilder()
    private fun log(msg: String) {
        if (logBuilder.length > 5000) {
            logBuilder.clear()
        }
//        logBuilder.append(msg).append("\n")
        logBuilder.insert(0, "$msg\n")
        lifecycleScope.launch(Dispatchers.Main) {
            binding.tvLog.text = logBuilder.toString()
        }
    }

    private val mFileReceiveListener = object : FileReceiveListener.Stub() {
        override fun onStart() {
            Log.e(TAG, "onStart: 本端开始接收文件")
            log("本端开始接收文件")
        }

        override fun onProgressChanged(progress: Float) {
            Log.e(TAG, "onProgressChanged: 本端接收文件的进度 $progress")
            log("接收文件的进度 $progress")
        }

        override fun onComplete(filePath: String) {
            Log.e(TAG, "onComplete: 本端接收文件完成")
            log("接收文件完成 $filePath")
            handleReceivedFile(filePath)
        }

        override fun onFail() {
            Log.e(TAG, "onFail: 接收文件失败")
            log("接收文件失败")
        }

        override fun onCancel() {
            Log.e(TAG, "onCancel: 对方取消了发送文件")
            log("对方取消了发送文件")
        }
    }

    private val mBtFileReceiveListener = object : FileReceiveListener.Stub() {
        override fun onStart() {
            Log.e(TAG, "onStart: 蓝牙本端开始接收文件")
            log("蓝牙本端开始接收文件")
        }

        override fun onProgressChanged(progress: Float) {
            Log.e(TAG, "onProgressChanged: 蓝牙本端接收文件的进度 $progress")
            log("蓝牙接收文件的进度 $progress")
        }

        override fun onComplete(filePath: String) {
            Log.e(TAG, "onComplete: 蓝牙本端接收文件完成")
            log("蓝牙接收文件完成 $filePath")
            handleReceivedFile(filePath)
        }

        override fun onFail() {
            Log.e(TAG, "onFail: 蓝牙接收文件失败")
            log("蓝牙接收文件失败")
        }

        override fun onCancel() {
            Log.e(TAG, "onCancel: 蓝牙对方取消了发送文件")
            log("蓝牙对方取消了发送文件")
        }
    }

    private fun handleReceivedFile(filePath: String) {
//        if (!ApkInstallUtil.isApkFile(filePath)) {
//            return
//        }
//
//        Log.d(TAG, "检测到 APK 文件，准备安装: $filePath")
//        log("检测到 APK，准备安装")
//        val started = ApkInstallUtil.installApk(this, filePath)
//        if (!started) {
//            log("APK 安装启动失败，请检查文件或安装权限")
//        }
    }



}
