package com.rokid.glass

import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.WindowManager
import com.rokid.glass.base.BaseActivity
import com.rokid.glass.media.AudioData
import com.rokid.glass.media.MediaRecorder
import com.rokid.glass.media.RecordData
import com.rokid.glass.media.VideoData
import com.rokid.glesse.databinding.ActivityMediaRecordBinding
import com.rokid.security.glass3.open.sdk.uitls.log.L
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.launch

class MediaRecordActivity : BaseActivity() {

    private lateinit var binding: ActivityMediaRecordBinding
    private var TAG = "MediaRecordActivity"
    private var mediaRecorder: MediaRecorder? = null
    private var mScope: CoroutineScope
    private var mHandlerThread = HandlerThread("MediaRecord")
    private var mHandler: Handler

    @Volatile
    private var h264recording = false

    init {
        mHandlerThread.start()
        mHandler = Handler(mHandlerThread.looper)
        mScope = CoroutineScope(mHandler.asCoroutineDispatcher() + SupervisorJob())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaRecordBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Log.e(TAG, "初始化")

        mScope.launch {
            val picDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            mediaRecorder = MediaRecorder().also {
                val videoData = VideoData(Size(1280, 720), frameRate = 10, frameInterval = 1, bitRate = 3184_000)
                val recordData = RecordData.newVideoRecordData(picDir, AudioData(bitRate = 16000), videoData)
                it.enableAudio = true
                it.fragmentTime = 1
                it.prepare(recordData, object : MediaRecorder.Callback {
                    override fun onStarted() {
                        L.i(TAG, "startEncodeH264: 开始视频流编码onStarted")
                        h264recording = true
                    }

                    override fun onFinished(t: Throwable?) {
                        L.i(TAG, "startEncodeH264: 结束视频流编码onFinished")
                        h264recording = false
                    }

                    override fun onNewFile(startTime: Long, endTime: Long, path: String, isLast: Boolean) {
                        L.i(TAG, "startEncodeH264: 新文件开始onNewFile: $path")
                    }
                })
                it.start()
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        mediaRecorder?.stop()
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

}

