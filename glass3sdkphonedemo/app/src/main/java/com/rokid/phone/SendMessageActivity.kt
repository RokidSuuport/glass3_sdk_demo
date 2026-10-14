package com.rokid.phone

import com.rokid.phone.utils.demoString
import com.rokid.phone.R

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.rokid.phone.data.GlobalData
import com.rokid.phone.databinding.ActivitySendmessageBinding
import com.rokid.phone.utils.FileSizeUtil
import com.rokid.phone.utils.FileUtil
import com.rokid.phone.utils.WavStreamSender
import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.security.phone.sdk.api.msg.listener.FileReceiveListener
import com.rokid.security.sdk.base.common.apk.TransferProgressListener
import com.rokid.security.sdk.base.common.notifacation.NotificationMessage
import com.rokid.utils.ToastUtils
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream


class SendMessageActivity : ComponentActivity() {
    private var randomFile = false
    private val TAG = "SendMessageActivity"
    private lateinit var binding: ActivitySendmessageBinding
    private var startTime = 0L
    private lateinit var sdDownload: File
    private var mCount = 0
    private var mMsgCount = 0

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
            log(demoString(R.string.demo_file_transfer_progress, progress))
        }

        override fun onProgressChanged(progress: Float, transferredBytes: Long, totalBytes: Long) {
            if (totalBytes <= 0L) {
                onProgressChanged(progress)
                return
            }
            val now = android.os.SystemClock.elapsedRealtime()
            if (transferredBytes < totalBytes && now - lastByteProgressTime < 500L) return
            lastByteProgressTime = now
            log(demoString(
                R.string.sdk222_phone_file_progress_bytes, progress,
                FileSizeUtil.formatFileSize(transferredBytes.coerceAtLeast(0L).toDouble()),
                FileSizeUtil.formatFileSize(totalBytes.toDouble())
            ))
        }

        /**
         * 眼镜端发送文件路径
         * Path of the file sent from the glasses.
         */
        override fun onComplete(filePath: String) {
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

    private val logBuilder = StringBuilder()
    private fun log(msg: String) {
        Log.e(TAG, msg)
        logBuilder.insert(0, "$msg\n")
        lifecycleScope.launch {
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

    override fun onDestroy() {
        super.onDestroy()
        mFileOperator?.removeFileReceiveListener(fileReceiveListener)
        mBTFileOperator?.removeFileReceiveListener(fileReceiveListener)
    }

}
