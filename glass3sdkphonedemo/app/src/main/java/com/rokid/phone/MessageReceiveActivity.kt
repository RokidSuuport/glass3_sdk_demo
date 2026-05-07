package com.rokid.phone

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.rokid.phone.databinding.ActivityMessageBinding
import com.rokid.phone.utils.ExternalAudioPlayer
import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.security.phone.sdk.api.msg.listener.FileReceiveListener
import com.rokid.security.phone.sdk.api.msg.listener.FileReceiveV2Listener
import com.rokid.security.phone.sdk.api.msg.listener.IMessageListener
import kotlinx.coroutines.launch

class MessageReceiveActivity : ComponentActivity() {

    private val TAG = "MessageReceiveActivity"
    private lateinit var binding: ActivityMessageBinding
    private lateinit var audioPlayer: ExternalAudioPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.e(TAG, "初始化")
        binding = ActivityMessageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioPlayer = ExternalAudioPlayer()
        audioPlayer.start()

        PSecuritySDK.getMessageService()?.addMessageListener(mMessageListener)
        PSecuritySDK.getMessageService()?.getFileOperater()?.addFileReceiveV2Listener(mFileReceiveListener)
        PSecuritySDK.getMessageService()?.getBtFileOperater()?.addFileReceiveV2Listener(mFileReceiveListener)
//        audioTrack.play()
    }

    private val logBuilder = StringBuilder(4000)
    private fun log(msg: String) {
        Log.e(TAG, msg)
        lifecycleScope.launch {
            if (logBuilder.length > 4000) {
                logBuilder.clear()
            }
//        logBuilder.append(msg).append("\n")
            logBuilder.insert(0, "$msg\n")
            binding.tvLog.text = logBuilder.toString()
        }
    }

    private val mFileReceiveListener = object : FileReceiveV2Listener {
        override fun onStart(filePath: String) {
            super.onStart(filePath)
            log("onStart: 本端开始接收文件,filePath=${filePath}")
        }

        override fun onProgressChanged(filePath: String,progress: Float) {
            log("onProgressChanged: 本端接收文件的进度 $progress")
        }

        override fun onComplete(filePath: String) {
            log("onComplete: 本端接收文件完成,filePath=${filePath}")
        }

        override fun onFail() {
            log("onFail: 接收文件失败")
        }

        override fun onCancel(filePath: String) {
            log("onCancel: 对方取消了发送文件,filePath=${filePath}")
        }
    }

//    private val audioTrack = AudioTrack(
//        AudioManager.STREAM_MUSIC,                // 音频流类型
//        16000,                                    // 采样率（必须一致）
//        AudioFormat.CHANNEL_OUT_MONO,             // 声道配置（与录音一致）
//        AudioFormat.ENCODING_PCM_16BIT,           // 编码格式（必须一致）
//        AudioTrack.getMinBufferSize(              // 合理的缓冲区大小
//            16000,
//            AudioFormat.CHANNEL_OUT_MONO,
//            AudioFormat.ENCODING_PCM_16BIT
//        ),
//        AudioTrack.MODE_STREAM                    // 流式模式（适合实时播放）
//    )

    private val mMessageListener = object : IMessageListener {
        @SuppressLint("SetTextI18n")
        override fun onClassicBTTextMessage(msg: String, clientId: String) {
            super.onClassicBTTextMessage(msg, clientId)
            log("onClassicBTTextMessage= $msg  $clientId")
        }

        @SuppressLint("SetTextI18n")
        override fun onP2PTextMessage(msg: String, clientId: String) {
            super.onP2PTextMessage(msg, clientId)
            log("onP2PTextMessage= $msg  $clientId")
        }

        override fun onClassicBTAudioStream(buffer: ByteArray) {
            Log.e(TAG, "接受蓝牙音频数据大小 ${buffer.size}")
//            audioTrack.write(buffer, 0, buffer.size)
            audioPlayer.writeAudioData(buffer)
        }

        override fun onBTStreamDataReceived(tag: String, data: ByteArray, clientId: String) {
            log("tag:${tag}  接收蓝牙音频数据大小 ${data.size}  clientId:$clientId")
//            audioTrack.write(data, 0, data.size)
            audioPlayer.writeAudioData(data)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        PSecuritySDK.getMessageService()?.removeMessageListener(mMessageListener)
        audioPlayer.release()
    }

}