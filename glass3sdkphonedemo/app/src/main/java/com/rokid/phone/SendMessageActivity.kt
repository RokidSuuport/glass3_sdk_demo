package com.rokid.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.rokid.phone.R
import com.rokid.phone.data.GlobalData
import com.rokid.phone.databinding.ActivitySendmessageBinding
import com.rokid.phone.utils.FileSizeUtil
import com.rokid.phone.utils.FileUtil
import com.rokid.phone.utils.demoString
import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.security.phone.sdk.api.msg.api.IFileOperate
import com.rokid.security.phone.sdk.api.msg.listener.FileReceiveListener
import com.rokid.security.sdk.base.common.apk.TransferProgressListener
import com.rokid.security.sdk.base.common.notifacation.NotificationMessage
import com.rokid.utils.ToastUtils
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException


class SendMessageActivity : ComponentActivity() {
    private enum class AudioTransport { P2P, CLASSIC_BT }

    private companion object {
        const val AUDIO_SAMPLE_RATE = 16_000
        const val AUDIO_PACKET_SAMPLES = 320
    }

    private var randomFile = false
    private val TAG = "SendMessageActivity"
    private lateinit var binding: ActivitySendmessageBinding
    private var startTime = 0L
    private lateinit var sdDownload: File
    private var mCount = 0
    private var mMsgCount = 0
    private var audioStreamJob: Job? = null
    private var pendingAudioTransport: AudioTransport? = null
    @Volatile
    private var activeFileOperator: IFileOperate? = null
    @Volatile
    private var stoppedFileOperator: IFileOperate? = null
    @Volatile
    private var activeRecorder: AudioRecord? = null
    private val microphonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val transport = pendingAudioTransport
        pendingAudioTransport = null
        updateAudioButtons()
        if (granted && transport != null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            startMicrophoneStream(transport)
        } else if (!granted && transport != null) {
            log(demoString(R.string.demo_microphone_permission_required))
        }
    }

    /**
     * P2P发送文件管理器
     * Manager for sending files over P2P.
     */
    private val mFileOperator by lazy {
        PSecuritySDK.getMessageService()?.getFileOperater()
    }

    /**
     * 蓝牙发送文件管理器
     * Manager for sending files over Bluetooth.
     */
    private val mBTFileOperator by lazy {
        PSecuritySDK.getMessageService()?.getBtFileOperater()
    }

    private val fileReceiveListener = object : FileReceiveListener {
        private var lastByteProgressTime = 0L

        override fun onStart() {
            startTime = System.currentTimeMillis()
            lastByteProgressTime = 0L
            log(demoString(R.string.demo_started_sending_file))
        }

        override fun onProgressChanged(progress: Float) {
            val message = if (stoppedFileOperator != null) {
                demoString(R.string.demo_file_progress_after_stop, progress)
            } else {
                demoString(R.string.demo_file_transfer_progress, progress)
            }
            log(message)
        }

        override fun onProgressChanged(progress: Float, transferredBytes: Long, totalBytes: Long) {
            if (totalBytes <= 0L) {
                onProgressChanged(progress)
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (transferredBytes < totalBytes && now - lastByteProgressTime < 500L) return
            lastByteProgressTime = now
            val messageRes = if (stoppedFileOperator != null) {
                R.string.demo_file_progress_bytes_after_stop
            } else {
                R.string.file_receiver_progress_bytes
            }
            log(demoString(
                messageRes, progress,
                FileSizeUtil.formatFileSize(transferredBytes.coerceAtLeast(0L).toDouble()),
                FileSizeUtil.formatFileSize(totalBytes.toDouble())
            ))
        }

        /**
         * 眼镜端发送文件路径
         * Path of the file sent from the glasses.
         */
        override fun onComplete(filePath: String) {
            if (stoppedFileOperator != null) {
                log(demoString(R.string.demo_file_completed_after_stop, filePath))
                return
            }
            // storage/emulated/0/Download/receiver/aaaascene.jpg
            var duration = System.currentTimeMillis() - startTime
            val fileSize = FileSizeUtil.formatFileSize(FileSizeUtil.getFileSizeBytes(curFile).toDouble())
            if (duration > 1000) {
                duration = duration / 1000
                log(demoString(R.string.demo_file_sent_in_seconds, duration, fileSize, filePath))
            } else {
                log(demoString(R.string.demo_file_sent_in_ms, duration, fileSize, filePath))
            }
        }

        override fun onFail() {
            log(demoString(R.string.demo_failed_to_send_file))
        }

        override fun onCancel() {
            log(demoString(R.string.demo_sender_canceled_file_transfer_2))
        }
    }

    val a1File = "scene.jpg"
    val a2File = "girl.png"
    val a3File = "app-release.apk"
    var curFile = File("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.e(TAG, "初始化")
        binding = ActivitySendmessageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        sdDownload = FileUtil.getAppPath(this, "temp")

        copyFileFromAssetsToFileCache(this, a1File)
        copyFileFromAssetsToFileCache(this, a2File)
        copyFileFromAssetsToFileCache(this, a3File)

        //wifi模式发送文本
        // Send text in Wi-Fi mode.
        binding.wfSendTextBtn.setOnClickListener {
            PSecuritySDK.getMessageService()?.sendTextMessageByP2P(demoString(R.string.demo_p2p_test_message), "GlassSample")
            log(demoString(R.string.demo_p2p_test_message))
        }
        binding.wfSendFileBtn.setOnClickListener {
            val fileToSend1 = File(sdDownload, a1File)
            val fileToSend2 = File(sdDownload, a2File)

            activeFileOperator = mFileOperator
            stoppedFileOperator = null
            logBuilder.clear()
            log(demoString(R.string.demo_preparing_to_send_file))
            if (randomFile) {
                randomFile = false
                Log.e(TAG, "文件目录: ${fileToSend1.absolutePath}")
                curFile = fileToSend1
                mFileOperator?.sendFile("", fileToSend1, fileReceiveListener) {}
            } else {
                randomFile = true
                Log.e(TAG, "文件目录: ${fileToSend2.absolutePath}")
                curFile = fileToSend2
                mFileOperator?.sendFile("", fileToSend2, fileReceiveListener) {}
            }
        }
        binding.wfFileStatusBtn.setOnClickListener {
            showFileSendingState(mFileOperator, R.string.demo_p2p_file_channel)
        }
        binding.wfStopFileBtn.setOnClickListener {
            stopFileSending(mFileOperator, R.string.demo_p2p_file_channel)
        }
        binding.btSendTextBtn.setOnClickListener {
            mMsgCount++
            var msg = demoString(R.string.demo_autumn_is_not_coming_back_i_am_going)
            if (mMsgCount % 2 == 0) {
                msg = demoString(R.string.demo_bluetooth_test_message)
            }
            PSecuritySDK.getMessageService()?.sendTextMessageByClassicBT(msg, "GlassSample")
            log(demoString(R.string.demo_bluetooth_test_message))
        }
        binding.btSendFileBtn.setOnClickListener {
            val fileToSend1 = File(sdDownload, a1File)
            val fileToSend2 = File(sdDownload, a2File)
            activeFileOperator = mBTFileOperator
            stoppedFileOperator = null
            logBuilder.clear()
            log(demoString(R.string.demo_preparing_to_send_file))
            if (randomFile) {
                randomFile = false
                curFile = fileToSend1
                log(demoString(R.string.demo_sending_via_bluetooth_file_path, fileToSend1.absolutePath))
                mBTFileOperator?.sendFile(null, fileToSend1, fileReceiveListener) {}
            } else {
                randomFile = true
                curFile = fileToSend2
                log(demoString(R.string.demo_sending_via_bluetooth_file_path, fileToSend2.absolutePath))
                // /storage/emulated/0/Download/receiver/test1girl.png 眼镜端接收文件目录
                // /storage/emulated/0/Download/receiver/test1girl.png is the receiving file path on the glasses.
                // 眼镜端文件名的前面增加了一段内容,原本的文件名前面增加了前缀，dir 可以写空字符串 或为 null
                // A prefix is added to the original file name on the glasses; dir may be an empty string or null.
                mBTFileOperator?.sendFile(null, fileToSend2, fileReceiveListener) {}
            }
        }
        binding.btFileStatusBtn.setOnClickListener {
            showFileSendingState(mBTFileOperator, R.string.demo_bluetooth_file_channel)
        }
        binding.btStopFileBtn.setOnClickListener {
            stopFileSending(mBTFileOperator, R.string.demo_bluetooth_file_channel)
        }
        binding.wfSendAudioBtn.setOnClickListener { startMicrophoneStream(AudioTransport.P2P) }
        binding.btSendAudioBtn.setOnClickListener { startMicrophoneStream(AudioTransport.CLASSIC_BT) }
        binding.stopSendAudioBtn.setOnClickListener { stopMicrophoneStream() }
        updateAudioButtons()
        binding.btnSendNotify.setOnClickListener {
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            val appLabel = packageManager.getApplicationLabel(applicationInfo).toString()
            mCount++
            val messageBean = NotificationMessage(
                packageName, appLabel,
                demoString(R.string.demo_notification_title_snow, mCount), demoString(R.string.demo_notification_degrees_below_zero, mCount), System.currentTimeMillis()
            )
            PSecuritySDK.getAbsNotificationService()?.sendNotification(messageBean)
            log(demoString(R.string.demo_notification_title_snow_r_nnotification_3_degrees_below))
        }
        binding.installApkBtn.setOnClickListener {
            if (!GlobalData.p2pConnectState.value) {
                ToastUtils.makeText(this, demoString(R.string.demo_connect_p2p_wi_fi_direct_first))
                return@setOnClickListener
            }
            val tesApk = File(sdDownload, a3File)
            PSecuritySDK.getMessageService()?.getApkFileOperator()?.sendFile(tesApk, object : TransferProgressListener {
                override fun onProgress(transferred: Long, total: Long) {
                    val progress = transferred.toFloat() / total
                    log(demoString(R.string.demo_apk_transfer_progress, String.format("%.2f", progress)))
                }

                override fun onComplete() {
                    log(demoString(R.string.demo_apk_installed))
                }

                override fun onError(message: String) {
                    log(demoString(R.string.demo_apk_installation_failed, message))
                }


                override fun onCanceled() {
                    log(demoString(R.string.demo_apk_installation_canceled))
                }
            })
        }
        mBTFileOperator?.addFileReceiveListener(fileReceiveListener)
        mFileOperator?.addFileReceiveListener(fileReceiveListener)
    }

    private fun showFileSendingState(operator: IFileOperate?, channelRes: Int) {
        val channel = demoString(channelRes)
        if (operator == null) {
            log(demoString(R.string.demo_file_operator_unavailable, channel))
            return
        }
        runCatching { operator.isSendingFile() }
            .onSuccess { sending ->
                val state = if (sending) R.string.demo_file_sending else R.string.demo_file_not_sending
                log(demoString(R.string.demo_file_sending_state, channel, demoString(state)))
                if (!sending && operator === stoppedFileOperator) {
                    log(demoString(R.string.demo_file_local_state_only))
                }
            }
            .onFailure { error ->
                log(demoString(R.string.demo_file_operator_error, channel, error.message ?: error.javaClass.simpleName))
            }
    }

    private fun stopFileSending(operator: IFileOperate?, channelRes: Int) {
        val channel = demoString(channelRes)
        if (operator == null) {
            log(demoString(R.string.demo_file_operator_unavailable, channel))
            return
        }
        if (operator === activeFileOperator) stoppedFileOperator = operator
        runCatching { operator.stopSendFile() }
            .onSuccess {
                log(demoString(R.string.demo_file_stop_requested, channel))
            }
            .onFailure { error ->
                if (operator === stoppedFileOperator) stoppedFileOperator = null
                log(demoString(R.string.demo_file_operator_error, channel, error.message ?: error.javaClass.simpleName))
            }
    }

    private fun startMicrophoneStream(transport: AudioTransport) {
        if (pendingAudioTransport != null || audioStreamJob?.isCompleted == false) {
            log(demoString(R.string.demo_audio_stream_already_sending))
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingAudioTransport = transport
            updateAudioButtons()
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val messageService = PSecuritySDK.getMessageService() ?: run {
            log(demoString(R.string.demo_audio_stream_service_unavailable))
            return
        }
        if (transport == AudioTransport.CLASSIC_BT) {
            if (!GlobalData.btConnectState.value) {
                log(demoString(R.string.demo_audio_stream_bluetooth_disconnected))
                return
            }
            launchMicrophoneStream(transport) { data ->
                messageService.sendAudioStreamDataByClassicBT(data)
            }
            return
        }

        val p2pService = PSecuritySDK.getWifiP2PClientService() ?: run {
            log(demoString(R.string.demo_audio_stream_p2p_disconnected))
            return
        }
        pendingAudioTransport = transport
        updateAudioButtons()
        runCatching {
            p2pService.isConnect { connected ->
                runOnUiThread {
                    if (pendingAudioTransport != transport) return@runOnUiThread
                    pendingAudioTransport = null
                    updateAudioButtons()
                    if (isFinishing || isDestroyed || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        return@runOnUiThread
                    }
                    GlobalData.setP2pConnectState(connected)
                    if (!connected) {
                        log(demoString(R.string.demo_audio_stream_p2p_disconnected))
                    } else {
                        launchMicrophoneStream(transport) { data ->
                            messageService.sendAudioStreamDataByP2P(data)
                        }
                    }
                }
            }
        }.onFailure {
            pendingAudioTransport = null
            updateAudioButtons()
            log(demoString(R.string.demo_audio_stream_failed, audioLabel(transport), it.message ?: it.javaClass.simpleName))
        }
    }

    private fun audioLabel(transport: AudioTransport): String = if (transport == AudioTransport.P2P) {
        demoString(R.string.demo_send_p2p_audio_stream)
    } else {
        demoString(R.string.demo_send_bluetooth_audio_stream)
    }

    private fun updateAudioButtons() {
        val busy = pendingAudioTransport != null || audioStreamJob?.isCompleted == false
        binding.wfSendAudioBtn.isEnabled = !busy
        binding.btSendAudioBtn.isEnabled = !busy
        binding.stopSendAudioBtn.isEnabled = busy
    }

    private fun launchMicrophoneStream(transport: AudioTransport, sendPacket: (ByteArray) -> Unit) {
        val label = audioLabel(transport)
        audioStreamJob = lifecycleScope.launch {
            var started = false
            var sentBytes = 0L
            try {
                withContext(Dispatchers.IO) {
                    val minBuffer = AudioRecord.getMinBufferSize(
                        AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                    )
                    check(minBuffer > 0) { "AudioRecord buffer size: $minBuffer" }
                    val recorder = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        AUDIO_SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(minBuffer, AUDIO_PACKET_SAMPLES * 2 * 4)
                    )
                    try {
                        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" }
                        activeRecorder = recorder
                        recorder.startRecording()
                        check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
                        started = true
                        log(demoString(R.string.demo_audio_stream_sending, label))
                        val samples = ShortArray(AUDIO_PACKET_SAMPLES)
                        var lastLogMs = SystemClock.elapsedRealtime()
                        while (currentCoroutineContext().isActive) {
                            val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                            if (!currentCoroutineContext().isActive) break
                            check(count >= 0) { "AudioRecord.read: $count" }
                            if (count == 0) continue
                            // 传输裸 PCM16 小端数据；阻塞读取自然按采集速度发送。
                            // Send raw little-endian PCM16; blocking reads pace the stream.
                            val packet = ByteArray(count * 2)
                            for (i in 0 until count) {
                                packet[i * 2] = samples[i].toByte()
                                packet[i * 2 + 1] = (samples[i].toInt() shr 8).toByte()
                            }
                            sendPacket(packet)
                            sentBytes += packet.size
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastLogMs >= 1000L) {
                                lastLogMs = now
                                log(demoString(R.string.demo_audio_stream_submitted, label, sentBytes))
                            }
                        }
                    } finally {
                        activeRecorder = null
                        runCatching { recorder.stop() }
                        recorder.release()
                    }
                }
            } catch (e: CancellationException) {
                // 用户停止或离开页面时结束采集。
                // Stop capture when the user stops it or leaves the page.
            } catch (e: Exception) {
                log(demoString(R.string.demo_audio_stream_failed, label, e.message ?: e.javaClass.simpleName))
            } finally {
                if (started) log(demoString(R.string.demo_microphone_stream_stopped, label, sentBytes))
                audioStreamJob = null
                updateAudioButtons()
            }
        }
        updateAudioButtons()
    }

    private fun stopMicrophoneStream() {
        pendingAudioTransport = null
        audioStreamJob?.cancel()
        activeRecorder?.let { runCatching { it.stop() } }
        updateAudioButtons()
    }

    private val logBuilder = StringBuilder()
    private fun log(msg: String) {
        Log.e(TAG, msg)
        lifecycleScope.launch(Dispatchers.Main) {
            logBuilder.insert(0, "$msg\n")
            if (logBuilder.length > 5000) logBuilder.setLength(5000)
            binding.tvLog.text = logBuilder.toString()
        }
    }

    fun copyFileFromAssetsToFileCache(context: Context, fileName: String) {
        // 检查权限
        // Check permissions.
        val inputStream: InputStream? = context.assets.open(fileName)
        if (inputStream == null) {
            ToastUtils.makeText(this, demoString(R.string.demo_file_not_found))
            return
        }
        var outputStream: OutputStream? = null
        try {
            val outputFile = File(sdDownload, fileName)
            if (outputFile.exists()) {
                outputFile.delete()
            }
            outputStream = FileOutputStream(outputFile)
            inputStream.copyTo(outputStream)
            outputStream.flush()
            // 文件写入成功
            // The file was written successfully.
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            inputStream.close()
            outputStream?.close()
        }
    }

    fun playWavFromAssets(context: Context, fileName: String) {
        Thread {
            try {
                val assetFile = context.assets.open(fileName)

                // WAV PCM 常用参数
                // Common WAV PCM parameters.
                val sampleRate = 16000
                val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT

                val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                if (minBufferSize <= 0) {
                    Log.e("AudioTrack", "无效的 buffer size")
                    return@Thread
                }

                val audioTrack = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    minBufferSize,
                    AudioTrack.MODE_STREAM
                )

                // 最大音量
                // Maximum volume.
                audioTrack.setVolume(1.0f)

                audioTrack.play()

                val buffer = ByteArray(minBufferSize)
                var read: Int
                while (assetFile.read(buffer).also { read = it } > 0) {
                    audioTrack.write(buffer, 0, read)
                }

                audioTrack.stop()
                audioTrack.release()
                assetFile.close()
            } catch (e: Exception) {
                e.printStackTrace()
                log(demoString(R.string.demo_playback_failed, e.message))
            }
        }.start()
    }

    override fun onStop() {
        stopMicrophoneStream()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        mFileOperator?.removeFileReceiveListener(fileReceiveListener)
        mBTFileOperator?.removeFileReceiveListener(fileReceiveListener)
    }

}
