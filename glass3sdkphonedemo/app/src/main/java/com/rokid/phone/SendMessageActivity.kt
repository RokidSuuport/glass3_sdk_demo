package com.rokid.phone

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

    /**
     * P2P发送文件管理器
     */
    private val mFileOperator by lazy {
        PSecuritySDK.getMessageService()?.getFileOperater()
    }

    /**
     * 蓝牙发送文件管理器
     */
    private val mBTFileOperator by lazy {
        PSecuritySDK.getMessageService()?.getBtFileOperater()
    }

    private val fileReceiveListener = object : FileReceiveListener {
        override fun onStart() {
            startTime = System.currentTimeMillis()
            log("onStart: 本端开始发送文件")
        }

        override fun onProgressChanged(progress: Float) {
            log("onProgressChanged: 本端发送文件的进度 $progress")
        }

        /**
         * 眼睛端发送文件路径
         */
        override fun onComplete(filePath: String) {
            // storage/emulated/0/Download/receiver/aaaascene.jpg
            var duration = System.currentTimeMillis() - startTime
            val fileSize = FileSizeUtil.formatFileSize(FileSizeUtil.getFileSizeBytes(curFile).toDouble())
            if (duration > 1000) {
                duration = duration / 1000
                log("onComplete: 本端发送文件完成，${duration}秒,${fileSize},$filePath")
            } else {
                log("onComplete: 本端发送文件完成，${duration}毫秒,${fileSize},$filePath")
            }
        }

        override fun onFail() {
            log("onFail: 本端发送文件失败")
        }

        override fun onCancel() {
            log("onCancel: 对方取消了发送文件")
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
        binding.wfSendTextBtn.setOnClickListener {
            PSecuritySDK.getMessageService()?.sendTextMessageByP2P("P2P测试的消息", "GlassSample")
            log("P2P测试的消息")
        }
        binding.wfSendFileBtn.setOnClickListener {
            val fileToSend1 = File(sdDownload, a1File)
            val fileToSend2 = File(sdDownload, a2File)

            logBuilder.clear()
            log("准备发送文件...")
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
            PSecuritySDK.getMessageService()?.sendTextMessageByClassicBT("蓝牙消息测试", "GlassSample")
            log("蓝牙消息测试")
        }
        binding.btSendFileBtn.setOnClickListener {
            val fileToSend1 = File(sdDownload, a1File)
            val fileToSend2 = File(sdDownload, a2File)
            logBuilder.clear()
            log("准备发送文件...")
            if (randomFile) {
                randomFile = false
                curFile = fileToSend1
                log("蓝牙发送---->文件目录: ${fileToSend1.absolutePath}")
                mBTFileOperator?.sendFile(null, fileToSend1, fileReceiveListener) {}
            } else {
                randomFile = true
                curFile = fileToSend2
                log("蓝牙发送---->文件目录: ${fileToSend2.absolutePath}")
                // /storage/emulated/0/Download/receiver/test1girl.png 眼睛端接收文件目录
                // 眼镜端文件名的前面增加了一段内容,原本的文件名前面增加了前缀，dir 可以写空字符串 或为 null
                mBTFileOperator?.sendFile(null, fileToSend2, fileReceiveListener) {}
            }
        }
        binding.btnSendNotify.setOnClickListener {
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            val appLabel = packageManager.getApplicationLabel(applicationInfo).toString()
            mCount++
            val messageBean = NotificationMessage(
                packageName, appLabel,
                "通知标题：下雪了$mCount", "通知内容：零下${mCount}度", System.currentTimeMillis()
            )
            PSecuritySDK.getAbsNotificationService()?.sendNotification(messageBean)
            log("通知标题：下雪了\r\n通知内容：零下3度")
        }
        binding.installApkBtn.setOnClickListener {
            if (!GlobalData.p2pConnectState.value) {
                ToastUtils.makeText(this, "请先连接P2P(WIFI直连)")
                return@setOnClickListener
            }
            val tesApk = File(sdDownload, a3File)
            PSecuritySDK.getMessageService()?.getApkFileOperator()?.sendFile(tesApk, object : TransferProgressListener {
                override fun onProgress(transferred: Long, total: Long) {
                    val progress = transferred.toFloat() / total
                    log("发送 apk进度 ${String.format("%.2f", progress)}")
                }

                override fun onComplete() {
                    log("安装APK成功")
                }

                override fun onError(message: String) {
                    log("安装APK失败 $message")
                }


                override fun onCanceled() {
                    log("安装APK取消")
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
        val inputStream: InputStream? = context.assets.open(fileName)
        if (inputStream == null) {
            ToastUtils.makeText(this, "文件不存在")
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
                log("播放失败: ${e.message}")
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        mFileOperator?.removeFileReceiveListener(fileReceiveListener)
        mBTFileOperator?.removeFileReceiveListener(fileReceiveListener)
    }

}

