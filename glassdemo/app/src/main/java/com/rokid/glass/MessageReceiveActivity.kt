package com.rokid.glass

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.rokid.glass.base.BaseActivity
import com.rokid.glass.utils.ImageFileUtils
import com.rokid.glass.utils.demoString
import com.rokid.glesse.R
import com.rokid.glesse.databinding.ActivityMessageReceiveBinding
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.sdk.base.data.notification.bean.NotificationMessage
import com.rokid.security.system.server.message.file.listener.FileReceiveListener
import com.rokid.security.system.server.message.listener.IMessageListener
import com.rokid.security.system.server.notification.listener.NotificationListener
import com.rokid.security.system.server.tts.listener.SpeechCompleteListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class MessageReceiveActivity : BaseActivity() {

    private lateinit var binding: ActivityMessageReceiveBinding
    private val TAG = "MessageActivity"
    private val isTtsPlaying = AtomicBoolean(false)
    private val audioLock = Any()
    private val audioQueue = Channel<ByteArray>(capacity = 16)
    private var audioPlaybackJob: Job? = null
    private var audioPackets = 0L
    private var audioReceivedBytes = 0L
    private var audioPlayedBytes = 0L
    private var audioDroppedPackets = 0L
    private var lastAudioLogMs = 0L
    private var audioTrackReleased = false

    private val audioTrack = AudioTrack(
        // 音频流类型
        // Audio stream type.
        AudioManager.STREAM_MUSIC,
        // 采样率（必须一致）
        // Sample rate, which must match.
        16000,
        // 声道配置（与录音一致）
        // Channel configuration, matching the recording configuration.
        AudioFormat.CHANNEL_OUT_MONO,
        // 编码格式（必须一致）
        // Encoding format, which must match.
        AudioFormat.ENCODING_PCM_16BIT,
        // 为实时链路保留约 200 毫秒播放缓冲，吸收分包到达抖动。
        // Keep about 200 ms of playback buffering for packet arrival jitter.
        maxOf(
            AudioTrack.getMinBufferSize(
                16000,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ),
            16000 * 2 / 5
        ),
        // 流式模式（适合实时播放）
        // Streaming mode, suitable for real-time playback.
        AudioTrack.MODE_STREAM
    )

    private val mMessageListener = object : IMessageListener.Stub() {
        override fun onTextMessage(msg: String) {
            log(msg)
            GlassSdk.getGlassTtsService()?.apply {
                isTtsPlaying.set(true)
                doSpeechTts(msg)
            }
        }

        override fun onAudioStream(buffer: ByteArray) {
            if (buffer.isEmpty() || isTtsPlaying.get()) return
            // Binder 回调只复制并入队，避免实时播放阻塞后续 PCM 包。
            // Copy and enqueue on the Binder callback so playback cannot block incoming PCM.
            val queued = audioQueue.trySend(buffer.copyOf()).isSuccess
            val message = synchronized(audioLock) {
                if (audioTrackReleased) return@synchronized null
                audioPackets++
                audioReceivedBytes += buffer.size
                if (!queued) audioDroppedPackets++
                val now = SystemClock.elapsedRealtime()
                if (audioPackets == 1L || now - lastAudioLogMs >= 1000L) {
                    lastAudioLogMs = now
                    demoString(
                        R.string.demo_audio_stream_playback_stats,
                        audioPackets, audioReceivedBytes, audioPlayedBytes, audioDroppedPackets
                    )
                } else {
                    null
                }
            }
            message?.let(::log)
        }

        override fun onStreamDataReceived(tag: String, data: ByteArray) {
            log(demoString(R.string.demo_stream_data_received_bytes_tag, data.size, tag))
        }
    }

    private val speechCompleteListener = object : SpeechCompleteListener.Stub() {
        override fun onComplete() {
            log(demoString(R.string.demo_tts_playback_complete))
            isTtsPlaying.set(false)
        }

        override fun onServiceConnectState(connected: Boolean) {
            log(demoString(R.string.demo_tts_playback_service_connected, connected))
            if (!connected) {
                isTtsPlaying.set(false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMessageReceiveBinding.inflate(layoutInflater)
        setContentView(binding.root)
        GlassSdk.getGlassTtsService()?.setSpeechCompleteListener(speechCompleteListener)
        GlassSdk.getGlassMessageService()?.setMessageListener(mMessageListener)
        GlassSdk.getGlassMessageService()?.glassFileOperater?.setFileReceiveListener(mFileReceiveListener)
        GlassSdk.getGlassMessageService()?.glassBtFileOperater?.setFileReceiveListener(mBtFileReceiveListener)
        GlassSdk.getGlassNotificationService()?.setNotificationListener(mNotificationListener)
        if (audioTrack.state == AudioTrack.STATE_INITIALIZED) {
            audioTrack.play()
            audioPlaybackJob = lifecycleScope.launch(Dispatchers.IO) {
                for (packet in audioQueue) {
                    // TTS 自行播放；排队中的麦克风包不应与 TTS 重叠。
                    // TTS plays itself; queued microphone packets must not overlap it.
                    if (isTtsPlaying.get()) continue
                    val error = synchronized(audioLock) {
                        if (audioTrackReleased) return@synchronized null
                        var offset = 0
                        while (offset < packet.size) {
                            val written = audioTrack.write(
                                packet, offset, packet.size - offset, AudioTrack.WRITE_BLOCKING
                            )
                            if (written <= 0) return@synchronized written
                            offset += written
                            audioPlayedBytes += written
                        }
                        null
                    }
                    if (error != null) log(demoString(R.string.demo_audio_stream_playback_error, error))
                }
            }
        } else {
            log(demoString(R.string.demo_audio_stream_playback_error, AudioTrack.ERROR_INVALID_OPERATION))
        }
        binding.scrollView.requestFocus()
    }

    override fun onDestroy() {
        super.onDestroy()
        GlassSdk.getGlassTtsService()?.removeSpeechCompleteListener()
        isTtsPlaying.set(false)
        GlassSdk.getGlassTtsService()?.doCancelTts()
        GlassSdk.getGlassMessageService()?.removeMessageListener(mMessageListener)
        GlassSdk.getGlassMessageService()?.glassFileOperater?.removeFileReceiveListener(mFileReceiveListener)
        GlassSdk.getGlassMessageService()?.glassBtFileOperater?.removeFileReceiveListener(mBtFileReceiveListener)
        GlassSdk.getGlassNotificationService()?.removeNotificationListener()
        audioQueue.close()
        audioPlaybackJob?.cancel()
        synchronized(audioLock) {
            audioTrackReleased = true
            if (audioTrack.state == AudioTrack.STATE_INITIALIZED) audioTrack.stop()
            audioTrack.release()
        }
    }

    private var mNotificationListener = object : NotificationListener.Stub() {
        /**
         * 通知到达
         * A notification has arrived.
         * @param notification 通知消息
         * The notification message.
         */
        override fun onNotificationReceived(notification: NotificationMessage?) {
            Log.d(TAG, "收到了通知")
            notification?.apply {
                log("$titleValue $msgValue")
            }
        }

        /**
         * 人脸识别通知
         * Face recognition notification.
         * @param face1
         * @param face2
         * @param message 通知消息
         * The notification message.
         */
        override fun onFaceRecognizeNotification(face1: ByteArray?, face2: ByteArray?, message: String?) {
            Log.d(TAG, "收到了人脸识别通知")
            face1?.let {
                ImageFileUtils.saveJpeg(it)
            }
            face2?.let {
                ImageFileUtils.saveJpeg(it)
            }
        }
    }

    private val logBuilder = StringBuilder()
    private fun log(msg: String) {
        Log.e(TAG, msg)
        lifecycleScope.launch(Dispatchers.Main) {
            if (logBuilder.length > 5000) {
                logBuilder.clear()
            }
            logBuilder.insert(0, "$msg\n")
            binding.tvLog.text = logBuilder.toString()
            binding.scrollView.scrollTo(0, 0)
        }
    }

    private val mFileReceiveListener = object : FileReceiveListener.Stub() {
        override fun onStart() {
            log(demoString(R.string.demo_started_receiving_file))
        }

        override fun onProgressChanged(progress: Float) {
            log(demoString(R.string.demo_file_reception_progress, progress))
        }

        override fun onComplete(filePath: String) {
            log(demoString(R.string.demo_file_received, filePath))
            handleReceivedFile(filePath)
        }

        override fun onFail() {
            log(demoString(R.string.demo_failed_to_receive_file))
        }

        override fun onCancel() {
            log(demoString(R.string.demo_sender_canceled_file_transfer))
        }
    }

    private val mBtFileReceiveListener = object : FileReceiveListener.Stub() {
        override fun onStart() {
            log(demoString(R.string.demo_started_receiving_file_via_bluetooth))
        }

        override fun onProgressChanged(progress: Float) {
            log(demoString(R.string.demo_bluetooth_file_reception_progress, progress))
        }

        override fun onComplete(filePath: String) {
            log(demoString(R.string.demo_file_received_via_bluetooth, filePath))
            handleReceivedFile(filePath)
        }

        override fun onFail() {
            log(demoString(R.string.demo_failed_to_receive_file_via_bluetooth))
        }

        override fun onCancel() {
            log(demoString(R.string.demo_bluetooth_sender_canceled_file_transfer))
        }
    }

    private fun handleReceivedFile(filePath: String) {
    }

}
