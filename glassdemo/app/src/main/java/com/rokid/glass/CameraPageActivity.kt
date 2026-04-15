package com.rokid.glass

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.drawable.AnimationDrawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.rokid.glass.base.BaseActivity
import com.rokid.glass.base.GlassKeyEvent
import com.rokid.glass.camera.QuickCameraManager
import com.rokid.glass.utils.AnimationUtil
import com.rokid.glass.utils.FileSizeUtil
import com.rokid.glass.utils.GlassSdkUtils
import com.rokid.glass.utils.SpriteToastUtil
import com.rokid.glesse.R
import com.rokid.glesse.databinding.ActivityPageCameraBinding
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.system.server.media.callback.PhotoFileCallback
import com.rokid.security.system.server.message.callback.IResultCallback
import com.rokid.security.system.server.message.file.listener.FileReceiveListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import java.util.Queue
import java.util.TimeZone


/**
 * 拍照录像界面
 */
class CameraPageActivity : BaseActivity() {

    private lateinit var viewBinding: ActivityPageCameraBinding
    private var dateFormat: SimpleDateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var recordSignAnimator: ObjectAnimator? = null
    private var startVideoTime = 0L
    private var isInitialize = false
    private var TAG = "CameraPageActivity"
    private var curIndex = 0

    companion object {
        const val TIPS_AUTO_DISMISS_TIME: Long = 2500L
    }

    @Volatile
    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dateFormat.timeZone = TimeZone.getTimeZone("GMT+0")
        viewBinding = ActivityPageCameraBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)
        initView()
    }

    override fun onResume() {
        super.onResume()
        viewBinding.ivPhoto.post {
            viewBinding.ivPhoto.requestFocus()
        }
    }


    var animationDrawable: AnimationDrawable? = null
    private fun initView() {
        animationDrawable = viewBinding.cameraBoxBg.background as AnimationDrawable
        QuickCameraManager.initialize { success ->
            isInitialize = success
            Log.d(TAG, "initialize=$success")
        }
        viewBinding.videoTimeLay.visibility = View.INVISIBLE
        viewBinding.videoTimeTv.text = null
        viewBinding.funcTipsTv.visibility = View.VISIBLE
        viewBinding.funcTipsTv.removeCallbacks(dismissStatusTipsRun)
        viewBinding.funcTipsTv.text = "单击\"右触控板\"拍照"

        viewBinding.ivRecord.setOnFocusChangeListener { view, b ->
            if (b) {
                Log.d(TAG, "------录像获取焦点")
                curIndex = 1
            }
        }

        viewBinding.ivPhoto.setOnFocusChangeListener { view, b ->
            if (b) {
                Log.d(TAG, "------拍照获取焦点")
                curIndex = 0
            }
        }

        viewBinding.ivRecord.setOnClickListener {
            Log.d(TAG, "------录像点击监听事件")
            onRecordAnim(!isRecording)
        }

        curIndex = 0
        viewBinding.ivPhoto.setOnClickListener {
            Log.d(TAG, "------拍照点击监听事件")
            takePicture()
//            val fileName = "test_${System.currentTimeMillis()}.png"
//            val publicPicturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
//            val file = File(publicPicturesDir, fileName)
//            GlassSdk.getGlassMediaService()?.takePhoto(PhotoResolution.RESOLUTION_1080P, file.absolutePath)
//            takeAnimation()
        }
//        takePhotoCallback()
    }

    private fun takePhotoCallback() {
        GlassSdk.getGlassMediaService()?.addPhotoCallback(object : PhotoFileCallback.Stub() {
            /**
             * 新版本不建议使用此接口
             */
            override fun onTakePhoto(path: String?) {
            }

            override fun getCallbackId(): String? {
                return "takephoto1"
            }

            /**
             * 新版本建议使用此接口
             * path: 拍照图片保存路径
             * width: 拍照图片宽度
             * height: 拍照图片高度
             */
            override fun onTakePhotoV2(path: String?, width: Int, height: Int) {
                if (path == null) {
                    runOnUiThread {
                        Toast.makeText(this@CameraPageActivity, "拍照失败", Toast.LENGTH_SHORT).show()
                    }
                    return
                }
                runOnUiThread {
                    Toast.makeText(this@CameraPageActivity, "拍照成功", Toast.LENGTH_SHORT).show()
                }
                addFileToQueue(File(path))
                Log.d(TAG, "onTakePhotoV2--> width = $width,height = $height, path:$path")
            }
        })
    }

    var takePictureJob: Job? = null
    private fun takePicture() {
        if (QuickCameraManager.isCameraDoing()) {
            runOnUiThread {
                SpriteToastUtil.showSpriteToast(MyApplication.getContext(), this.getString(R.string.camera_busy), 0, 1500, true)
            }
            return
        }
        if (!isInitialize) {
            runOnUiThread {
//                GlassSDK.getGlassTtsService()?.doSpeechTts(this.getString(R.string.camera_busy))
                SpriteToastUtil.showSpriteToast(MyApplication.getContext(), this.getString(R.string.camera_error), 0, 1500, true)
            }
            return
        }
        QuickCameraManager.takePicture {
            Log.d(TAG, "takePicture " + it?.path)
            if (it == null) {
                runOnUiThread {
                    QuickCameraManager.initialize { success ->
                        isInitialize = success
                        Log.d(TAG, "2-initialize" + success)
                    }
//                        GlassSDK.getGlassTtsService()?.doSpeechTts(this.getString(R.string.camera_error))
                    SpriteToastUtil.showSpriteToast(
                        MyApplication.getContext(),
                        this.getString(R.string.camera_error),
                        0,
                        1500,
                        true
                    )
                }
            } else {
                addFileToQueue(it)
            }
        }
        takeAnimation()
    }

    private fun takeAnimation() {
        takePictureJob?.cancel()
        takePictureJob = lifecycleScope.launch {
            viewBinding.ivPhotoLevel.visibility = View.INVISIBLE
            viewBinding.cameraBoxBg.visibility = View.VISIBLE

            animationDrawable?.stop() // 停止当前可能的播放
            animationDrawable?.selectDrawable(0) // 强制切换到第一帧
            animationDrawable?.isOneShot = true
            animationDrawable?.start()
            delay(850)
            launch {
                viewBinding.cameraBoxBgBig.visibility = View.VISIBLE
                delay(500)
                viewBinding.cameraBoxBgBig.visibility = View.INVISIBLE
                viewBinding.cameraBoxBg.visibility = View.INVISIBLE
                AnimationUtil.fadeInImageView(viewBinding.ivPhotoLevel)
            }
        }
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        if (keyEvent == GlassKeyEvent.KEYCODE_BEHIND) {
            viewBinding.ivPhoto.requestFocus()
            viewBinding.ivRecord.isSelected = false  // 同步选中状态
            viewBinding.ivPhoto.isSelected = true
            curIndex = 0
            viewBinding.ivPhotoLevel.visibility = View.VISIBLE
            viewBinding.funcTipsTv.text = "单击\"右触控板\"拍照"
            if (isRecording) {
                onRecordAnim(false)
            }
        } else if (keyEvent == GlassKeyEvent.KEYCODE_FRONT) {
            viewBinding.ivRecord.requestFocus()
            viewBinding.ivRecord.isSelected = true  // 同步选中状态
            viewBinding.ivPhoto.isSelected = false
            curIndex = 1
            viewBinding.ivPhotoLevel.visibility = View.INVISIBLE
            viewBinding.funcTipsTv.text = "单击\"右触控板\"录像"
        } else if (keyEvent == GlassKeyEvent.KEYCODE_CLICK) {
            Log.d(TAG, "isFocused: " + viewBinding.ivPhoto.isFocused + " " + viewBinding.ivRecord.isFocused)
            if (curIndex == 0) {
                takePicture()
            } else if (curIndex == 1) {
                onRecordAnim(!isRecording)
            }
        }
        return super.onGlassKeyEvent(keyEvent)
    }


//    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
//        try {
//            Log.d(TAG, "onKeyUp------>keyCode=${keyCode}")
//            if (KeyEvent.KEYCODE_ENTER == keyCode) {
//                return true
//            }
//        } catch (e: Exception) {
//            e.printStackTrace()
//        }
//        return super.onKeyUp(keyCode, event)
//    }

    private fun onRecordAnim(start: Boolean) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                isRecording = start
                if (start) {
                    viewBinding.root.removeCallbacks(needFinishRun)
                    viewBinding.videoTimeLay.visibility = View.VISIBLE
                    startVideoTime = System.currentTimeMillis()
                    viewBinding.videoTimeLay.post(timeTvRun)
                    viewBinding.videoTimeSign.alpha = 1.0f
                    recordSignAnimator = ObjectAnimator.ofFloat(viewBinding.videoTimeSign, "alpha", 1f, 0f)
                    recordSignAnimator?.let {
                        it.duration = 500
                        it.repeatCount = ValueAnimator.INFINITE
                        it.repeatMode = ValueAnimator.REVERSE
                        it.start()
                    }
                    viewBinding.funcTipsTv.removeCallbacks(dismissStatusTipsRun)
                    viewBinding.funcTipsTv.postDelayed(dismissStatusTipsRun, TIPS_AUTO_DISMISS_TIME)
                    QuickCameraManager.startRecording {
                        Log.d(TAG, "startRecording " + it?.path)
                    }
                    viewBinding.funcTipsTv.text = "单击\"右触控板\"停止录像"
                    viewBinding.funcTipsTv.visibility = View.VISIBLE
                } else {
                    recordSignAnimator?.cancel()
                    viewBinding.videoTimeLay.removeCallbacks(timeTvRun)
                    viewBinding.funcTipsTv.removeCallbacks(dismissStatusTipsRun)
                    viewBinding.funcTipsTv.removeCallbacks(statusTipsRun)
                    viewBinding.videoTimeLay.visibility = View.INVISIBLE
                    withContext(Dispatchers.IO) {
                        QuickCameraManager.stopRecording {
                            Log.d(TAG, "stopRecording " + it?.path)
                            if (it != null) {
                                addFileToQueue(it)
                            }
                        }
                    }
                    SpriteToastUtil.showSpriteToast(MyApplication.getContext(), "录像完成", 0, 1500, true)
                    viewBinding.funcTipsTv.postDelayed(statusTipsRun, 1500)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private var dismissStatusTipsRun = Runnable {
        viewBinding.funcTipsTv.visibility = View.INVISIBLE
    }

    private var statusTipsRun = Runnable {
        viewBinding.funcTipsTv.text = "单击\"右触控板\"录像"
        viewBinding.funcTipsTv.visibility = View.VISIBLE
    }

    override fun finish() {
        try {
            if (isRecording) {
                onRecordAnim(!isRecording)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.finish()
    }

    private var needFinishRun = Runnable {
        try {
            if (!isRecording) {
                finish()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private var timeTvRun = object : Runnable {
        override fun run() {
            try {
                val recordTime = System.currentTimeMillis() - startVideoTime
                viewBinding.videoTimeTv.text = dateFormat.format(Date(recordTime))
                if (isRecording) {
                    viewBinding.videoTimeLay.postDelayed(this, 1000)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private val fileQueue: Queue<File> = LinkedList()
    private var isSending = false

    fun addFileToQueue(file: File) {
        if (!file.exists()) {
            Log.e(TAG, "文件不存在: ${file.path}")
            return
        }
        fileQueue.offer(file)
        Log.d(TAG, "加入队列: ${file.path}")
        if (!isSending) {
            sendNextFile()
        }
    }

    private fun sendNextFile() {
        val file = fileQueue.poll() ?: run {
            Log.d(TAG, "队列空，所有文件已发送完毕")
            isSending = false
            return
        }
        isSending = true
        GlassSdkUtils.mMessageService?.glassFileOperater?.sendFile("", file.path, mFileReceiveListener, mIResultCallback)
        curFile = file
    }

    private var startTime = 0L
    private var curFile = File("")

    private val mFileReceiveListener = object : FileReceiveListener.Stub() {

        override fun onStart() {
            startTime = System.currentTimeMillis()
            Log.e(TAG, "onStart: 本端开始发送文件")
        }

        override fun onProgressChanged(progress: Float) {
            Log.e(TAG, "onProgressChanged: 本端发送文件的进度 $progress")
        }

        override fun onComplete(filePath: String) {
            var duration = System.currentTimeMillis() - startTime
            val fileSize = FileSizeUtil.formatFileSize(FileSizeUtil.getFileSizeBytes(curFile).toDouble())
            if (duration > 1000) {
                duration = duration / 1000
                Log.e(TAG, "onComplete: 本端发送文件完成，${duration}秒,${fileSize},${filePath}")
            } else {
                Log.e(TAG, "onComplete: 本端发送文件完成，${duration}毫秒,${fileSize},${filePath}")
            }
            sendNextFile()
        }

        override fun onFail() {
            Log.e(TAG, "onFail: 本端发送文件失败")
        }

        override fun onCancel() {
            Log.e(TAG, "onCancel: 对方取消了发送文件")
        }
    }
    private val mIResultCallback = object : IResultCallback.Stub() {
        override fun onSuccess(p0: Boolean) {
            Log.e(TAG, "成功")
        }

        override fun onFailed(p0: Int, p1: String?) {
            Log.e(TAG, "失败")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        QuickCameraManager.releaseCamera()
        MyApplication.curIsCameraActivity = false
    }

}