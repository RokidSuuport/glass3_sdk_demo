package com.rokid.phone

import com.rokid.phone.utils.demoString
import com.rokid.phone.R

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.rokid.phone.databinding.ActivityMessageBinding
import com.rokid.phone.utils.ExternalAudioPlayer
import com.rokid.phone.video.FrameRateMeter
import com.rokid.phone.video.H264NalUnitInspector
import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.security.phone.sdk.api.msg.listener.FileReceiveListener
import com.rokid.security.phone.sdk.api.msg.listener.FileReceiveV2Listener
import com.rokid.security.phone.sdk.api.msg.listener.IMessageListener
import java.nio.ByteBuffer
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
    }

    private val logBuilder = StringBuilder(4000)
    private fun log(msg: String) {
        Log.e(TAG, msg)
        lifecycleScope.launch {
            if (logBuilder.length > 4000) {
                logBuilder.clear()
            }
            logBuilder.insert(0, "$msg\n")
            binding.tvLog.text = logBuilder.toString()
        }
    }

    private val mFileReceiveListener = object : FileReceiveV2Listener {
        override fun onStart(filePath: String) {
            super.onStart(filePath)
            log(demoString(R.string.demo_started_receiving_file, filePath))
        }

        override fun onProgressChanged(filePath: String,progress: Float) {
            log(demoString(R.string.demo_file_reception_progress, progress))
        }

        override fun onComplete(filePath: String) {
            log(demoString(R.string.demo_file_received, filePath))
        }

        override fun onFail() {
            log(demoString(R.string.demo_failed_to_receive_file))
        }

        override fun onCancel(filePath: String) {
            log(demoString(R.string.demo_sender_canceled_file_transfer, filePath))
        }
    }

    private val mMessageListener = object : IMessageListener {
        @SuppressLint("SetTextI18n")
        override fun onClassicBTTextMessage(msg: String, clientId: String) {
            super.onClassicBTTextMessage(msg, clientId)
            log(demoString(R.string.demo_onclassicbttextmessage_status_32, msg, clientId))
        }

        @SuppressLint("SetTextI18n")
        override fun onP2PTextMessage(msg: String, clientId: String) {
            super.onP2PTextMessage(msg, clientId)
            log(demoString(R.string.demo_onp2ptextmessage_status_33, msg, clientId))
        }

        override fun onClassicBTAudioStream(buffer: ByteArray) {
            log(demoString(R.string.demo_bluetooth_audio_received_bytes, buffer.size))
            audioPlayer.writeAudioData(buffer)
        }

        /**
         * 视频流只统计不消费：SDK 会把同一份数据广播给所有监听者，这里只读 remaining 与 NAL 类型，
         * 不调用 get()/duplicate 后改写位置，避免影响视频接收页自己的解码。
         * Video frames are counted, never consumed: the SDK broadcasts the same buffer to every
         * listener, so this page only reads the remaining size and NAL type.
         */
        override fun onVideoH264Stream(buffer: ByteBuffer) {
            logH264Frame(buffer)
        }

        override fun onNv21Data(data: ByteArray, width: Int, height: Int) {
            logNv21Frame(data, width, height)
        }
    }

    private val h264FpsMeter = FrameRateMeter()
    private var h264FirstFrameLogged = false
    private var h264TotalFrames = 0L
    private var h264BytesInWindow = 0L
    private var h264LastFrameAtMs = 0L
    private var nv21FirstFrameLogged = false
    private var nv21TotalFrames = 0L

    /** H.264：首帧单独打一条，之后每秒汇总帧率、累计帧数与数据量，避免逐帧刷屏。 */
    private fun logH264Frame(buffer: ByteBuffer) {
        val bytes = buffer.remaining()
        if (bytes <= 0) return
        val frameBoundary = H264NalUnitInspector.containsFrame(buffer.duplicate())
        if (!h264FirstFrameLogged) {
            h264FirstFrameLogged = true
            log(demoString(R.string.demo_video_stream_h264_first_frame, bytes))
        }
        if (frameBoundary) {
            h264TotalFrames++
            h264BytesInWindow += bytes
        }
        h264LastFrameAtMs = System.currentTimeMillis()
        h264FpsMeter.recordFrame(h264LastFrameAtMs)?.let { fps ->
            val bytesPerSecond = h264BytesInWindow
            h264BytesInWindow = 0L
            log(demoString(R.string.demo_video_stream_h264_summary,
                formatFps(fps), h264TotalFrames, formatKilobytes(bytesPerSecond)))
        }
    }

    /** NV21：只报尺寸和累计帧数；像素数据由视频接收页等其它监听者消费。 */
    private fun logNv21Frame(data: ByteArray, width: Int, height: Int) {
        nv21TotalFrames++
        if (nv21FirstFrameLogged) return
        nv21FirstFrameLogged = true
        log(demoString(R.string.demo_video_stream_nv21_first_frame, width, height, data.size))
    }

    private fun formatFps(fps: Float): String = if (fps >= 10f) String.format("%.0f", fps) else String.format("%.1f", fps)

    private fun formatKilobytes(bytes: Long): String = String.format("%.1f KB/s", bytes / 1024f)

    override fun onDestroy() {
        DeviceLinkerManager.stopAudioStream()
        super.onDestroy()
        PSecuritySDK.getMessageService()?.removeMessageListener(mMessageListener)
        audioPlayer.release()
    }

}
