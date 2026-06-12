package com.rokid.glass

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.rokid.glass.base.BaseGlassActivity
import com.rokid.glass.base.GlassKeyEvent
import com.rokid.glass.media.WorkAudioEncoder
import com.rokid.glass.utils.TimeUtils
import com.rokid.glesse.R
import com.rokid.glesse.databinding.ActivityTestMediaBinding
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.uitls.log.L
import com.rokid.security.glass3.sdk.base.data.media.PhotoResolution
import com.rokid.security.glass3.sdk.base.data.media.PreviewResolution
import com.rokid.security.glass3.sdk.base.data.media.RecordConfig
import com.rokid.security.system.server.aichat.listener.AiChatListener
import com.rokid.security.system.server.media.callback.AudioCallback
import com.rokid.security.system.server.media.callback.PhotoFileCallback
import com.rokid.security.system.server.media.callback.VideoCallback
import com.rokid.security.system.server.media.listener.IMediaStateLister
import com.rokid.security.system.server.message.callback.IResultCallback
import kotlinx.coroutines.launch
import java.io.File

/**
 * sdk 拍照 录像 录制音频文件 发送视频流等
 */
class SdkMediaActivity : BaseGlassActivity() {

    private lateinit var binding: ActivityTestMediaBinding
    private var TAG = "TestMediaActivity"
    private var zoom = 1
    private var selectBtnStatus = 0
    private var audioEncoder: WorkAudioEncoder? = null

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTestMediaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        GlassSdk.getGlassMediaService()?.addPhotoCallback(mPhotoFileCallback)
        GlassSdk.getGlassMediaService()?.setMediaStateLister(mICameraStateLister)
        unAllSelectState(binding.clMedia)
        selectBtn(binding.btPhoto480P)
        initView()
    }

    @SuppressLint("SetTextI18n")
    private fun initView() {
        binding.btPhoto480P.setOnClickListener {
            log("----开始拍照480P")
            val fileName = "test_${System.currentTimeMillis()}.png"
            val publicPicturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val file = File(publicPicturesDir, fileName)
            GlassSdk.getGlassMediaService()?.takePhoto(PhotoResolution.RESOLUTION_480P, file.absolutePath)

            unAllSelectState(binding.clMedia)
            selectBtn(it as AppCompatButton)
        }

        binding.btPhoto720P.setOnClickListener {
            log("----开始拍照720P")
            // 720P拍的照片是横屏
            val fileName = "test_${System.currentTimeMillis()}.png"
            val publicPicturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val file = File(publicPicturesDir, fileName)
            GlassSdk.getGlassMediaService()?.takePhoto(PhotoResolution.RESOLUTION_720P, file.absolutePath)

            unAllSelectState(binding.clMedia)
            selectBtn(it as AppCompatButton)
        }

        binding.btPhoto1080P.setOnClickListener {
            log("----开始拍照1080P")
            // 1080P拍的照片是竖屏
            val fileName = "test_${System.currentTimeMillis()}.png"
            val publicPicturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val file = File(publicPicturesDir, fileName)
            GlassSdk.getGlassMediaService()?.takePhoto(PhotoResolution.RESOLUTION_1080P, file.absolutePath)

            unAllSelectState(binding.clMedia)
            selectBtn(it as AppCompatButton)
        }

        binding.btPhoto1080PLand.setOnClickListener {
            log("----开始拍照1080P横屏")
            // 1080P拍的照片是竖屏
            val fileName = "test_${System.currentTimeMillis()}.png"
            val publicPicturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val file = File(publicPicturesDir, fileName)
            GlassSdk.getGlassMediaService()?.takePhoto(PhotoResolution.RESOLUTION_1080P_Land, file.absolutePath)

            unAllSelectState(binding.clMedia)
            selectBtn(it as AppCompatButton)
        }

        binding.btPhoto4K.setOnClickListener {
            log("----开始拍照4K")
            // 4K 拍的视频是横屏
            val fileName = "test_${System.currentTimeMillis()}.png"
            val publicPicturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val file = File(publicPicturesDir, fileName)
            GlassSdk.getGlassMediaService()?.takePhoto(PhotoResolution.RESOLUTION_4K, file.absolutePath)
            unAllSelectState(binding.clMedia)
            selectBtn(it as AppCompatButton)
        }

        binding.btStartRecord720P.setOnClickListener {
            log("----开始视频刻录720P")
            // 720P 录的视频是横屏,分辨率：1280*720
            // 取值为1则每间隔 1分钟自动生成新文件，后续音视频数据将写入新文件。
            val min = 1
            val enableAudio = true
            val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath + "/video"
            Log.d(TAG, "onCreate: path = $path")
            val recordConfig = RecordConfig(
                path,
                min,
                enableAudio,
                width = PreviewResolution.ResolutionInfo_720P.width,
                height = PreviewResolution.ResolutionInfo_720P.height,
                isArRMixEnabled = true
            )
            GlassSdk.getGlassMediaService()?.startRecord(videoCallback, recordConfig)
            unAllSelectState(binding.clMedia)
            selectBtn(it as AppCompatButton)
        }

        binding.btStartRecord1080P.setOnClickListener {
            // 取值为1则每间隔 1分钟自动生成新文件，后续音视频数据将写入新文件。
            // 1080P 录的视频是竖屏,分辨率：1080 * 1920
            val min = 1
            val enableAudio = true
            val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath + "/video"
            Log.d(TAG, "onCreate: path = $path")
            val recordConfig = RecordConfig(
                path,
                min,
                enableAudio,
                width = PreviewResolution.ResolutionInfo_1080P.width,
                height = PreviewResolution.ResolutionInfo_1080P.height,
                fps = 20
            )
            // 1080P 目前拍的视频是竖屏
            GlassSdk.getGlassMediaService()?.startRecord(videoCallback, recordConfig)
            unAllSelectState(binding.clMedia)
            selectBtn(it as AppCompatButton)
            log("开始视频刻录1080P")
        }

        binding.btStartRecord1080PLand.setOnClickListener {
            // 取值为1则每间隔 1分钟自动生成新文件，后续音视频数据将写入新文件。
            // 1080P 录的视频是竖屏,分辨率：1080 * 1920
            val min = 1
            val enableAudio = true
            val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath + "/video"
            Log.d(TAG, "onCreate: path = $path")
            val recordConfig = RecordConfig(
                path,
                min,
                enableAudio,
                width = PreviewResolution.ResolutionInfo_1080P_Land.width,
                height = PreviewResolution.ResolutionInfo_1080P_Land.height,
                fps = 20
            )
            // 1080P 目前拍的视频是竖屏
            GlassSdk.getGlassMediaService()?.startRecord(videoCallback, recordConfig)
            unAllSelectState(binding.clMedia)
            selectBtn(it as AppCompatButton)
            log("开始视频刻录1080P_Land")
        }

        binding.btStopRecord.setOnClickListener {
            log("----停止视频刻录")
            GlassSdk.getGlassMediaService()?.stopRecord()
            unAllSelectState(binding.clMedia)
            selectBtn(it as AppCompatButton)
        }

        binding.btSendVideo.setOnClickListener {
            log("----开始发送视频")
            GlassSdk.getGlassMessageService()?.sendVideoStreamDataV2(object : IResultCallback.Default() {
                override fun onSuccess(result: Boolean) {
                    super.onSuccess(result)
                    log("sendVideoStreamData -- onSuccess: ")
                }

                override fun onFailed(code: Int, errormsg: String?) {
                    super.onFailed(code, errormsg)
                    log("sendVideoStreamData -- onFailed:  code = $code, errormsg = $errormsg")
                }
            })
            unAllSelectState(binding.clMedia)
            selectBtn(it as AppCompatButton)
        }

        binding.btStopSendVideo.setOnClickListener {
            log("----停止发送视频")
            GlassSdk.getGlassMessageService()?.stopVideoStreamData()
            unAllSelectState(binding.clMedia)
            selectBtn(it as AppCompatButton)
        }
        binding.btnSetZoom.setOnClickListener {
            binding.btnSetZoom.text = "zoom: $zoom"
            // sdk的数字变焦方法，只有视频录像才会起作用，照片是不起作用的
            GlassSdk.getGlassMediaService()?.zoomCamera(zoom)
            zoom++
            if (zoom > 3) {
                zoom = 1
            }
            unAllSelectState(binding.clMedia)
            selectBtn(it as AppCompatButton)
            log("----获取相机缩放：${GlassSdk.getGlassMediaService()?.zoomLevel}")
        }
        binding.btnRecordAudio.setOnClickListener {
            log("----开始录音")
            stopAudioWrite()
            val fileName = TimeUtils.timestampToDateTime(System.currentTimeMillis(), "yyyy-MM-dd-HH-mm-ss") + ".aac"
            val picDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val path = File(picDir, fileName).absolutePath
            audioEncoder = WorkAudioEncoder()
            audioEncoder?.start(path)
            GlassSdk.getGlassMediaService()?.startAudioRecord(mAudioCallback)
            unAllSelectState(binding.clMedia)
            selectBtn(it as AppCompatButton)
        }
        binding.btnStopRecordAudio.setOnClickListener {
            log("----停止录音")
            GlassSdk.getGlassMediaService()?.stopAudioRecord(mAudioCallback)
            stopAudioWrite()
            unAllSelectState(binding.clMedia)
            selectBtn(it as AppCompatButton)
        }
        binding.btnAiChat.setOnClickListener {
            log("----开始AI问答")
            GlassSdk.getGlassAiChatService()?.startAiChat(false)
            unAllSelectState(binding.clMedia)
            selectBtn(it as AppCompatButton)
            GlassSdk.getGlassAiChatService()?.toAiChat("杭州明天下雨吗", mAIChaLister)
        }
        binding.btnStopAiChat.setOnClickListener {
            log("----停止AI问答")
            GlassSdk.getGlassAiChatService()?.endAiChat()
            unAllSelectState(binding.clMedia)
            selectBtn(it as AppCompatButton)
        }
    }

    private fun stopAudioWrite() {
        audioEncoder?.stop()
        audioEncoder = null
    }

    private fun unSelectBtn(textView: AppCompatButton) {
        textView.clearFocus()
        textView.isSelected = false
        textView.isClickable = false
        textView.isFocusable = false
        textView.setTextColor(ContextCompat.getColor(this, R.color.green_70))
        textView.setBackgroundResource(R.drawable.round_unselect_bg)
    }

    private fun selectBtn(textView: AppCompatButton) {
        textView.setTextColor(ContextCompat.getColor(this, R.color.green))
        textView.setBackgroundResource(R.drawable.round_select_bg)
        textView.isSelected = true
        textView.isClickable = true
        textView.isFocusable = true
        textView.requestFocus()
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        when (keyEvent) {
            GlassKeyEvent.KEYCODE_FRONT -> {
                selectBtnStatus++
                selectBtnStatus %= 16
                when (selectBtnStatus) {
                    1 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btPhoto720P)
                    }

                    2 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btPhoto1080P)
                    }

                    3 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btPhoto1080PLand)
                    }

                    4 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btPhoto4K)
                    }

                    5 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btStartRecord720P)
                    }

                    6 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btStartRecord1080P)
                    }

                    7 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btStartRecord1080PLand)
                    }

                    8 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btStopRecord)
                    }

                    9 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btSendVideo)
                    }

                    10 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btStopSendVideo)
                    }

                    11 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btnSetZoom)
                    }

                    12 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btnRecordAudio)
                    }

                    13 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btnStopRecordAudio)
                    }

                    14 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btnAiChat)
                    }

                    15 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btnStopAiChat)
                    }

                    0 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btPhoto480P)
                    }
                }
            }

            GlassKeyEvent.KEYCODE_BEHIND -> {
                selectBtnStatus--
                if (selectBtnStatus < 0) {
                    selectBtnStatus = 15
                }
                selectBtnStatus %= 16
                when (selectBtnStatus) {
                    1 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btPhoto720P)
                    }

                    2 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btPhoto1080P)
                    }

                    3 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btPhoto1080PLand)
                    }

                    4 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btPhoto4K)
                    }

                    5 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btStartRecord720P)
                    }

                    6 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btStartRecord1080P)
                    }

                    7 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btStartRecord1080PLand)
                    }

                    8 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btStopRecord)
                    }

                    9 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btSendVideo)
                    }

                    10 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btStopSendVideo)
                    }

                    11 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btnSetZoom)
                    }

                    12 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btnRecordAudio)
                    }

                    13 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btnStopRecordAudio)
                    }

                    14 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btnAiChat)
                    }

                    15 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btnStopAiChat)
                    }

                    0 -> {
                        unAllSelectState(binding.clMedia)
                        selectBtn(binding.btPhoto480P)
                    }
                }
            }

            GlassKeyEvent.KEYCODE_CLICK -> {
                for (i in 0 until binding.clMedia.childCount) {
                    if (binding.clMedia.getChildAt(i).isSelected) {
                        binding.clMedia.getChildAt(i).performClick()
                        break;
                    }
                }
            }
        }
        return super.onGlassKeyEvent(keyEvent)
    }

    fun unAllSelectState(viewGroup: ViewGroup) {
        for (i in 0 until viewGroup.childCount) {
            if (viewGroup.getChildAt(i) is ViewGroup) {
                unAllSelectState(viewGroup.getChildAt(i) as ViewGroup)
            } else if (viewGroup.getChildAt(i) is AppCompatButton) {
                unSelectBtn(viewGroup.getChildAt(i) as AppCompatButton)
            }
        }
    }

    private val mAudioCallback = object : AudioCallback.Stub() {
        override fun onAudioStream(buffer: ByteArray?, bufferLen: Int) {
            if (buffer != null && bufferLen > 0) {
                audioEncoder?.encode(buffer, bufferLen)
            }
        }

        override fun getCallbackId(): String {
            return "10001"
        }
    }

    private val mPhotoFileCallback = object : PhotoFileCallback.Stub() {
        override fun onTakePhoto(path: String) {
            log("onTakePhoto-->path = $path")
        }

        override fun getCallbackId(): String {
            return "10002"
        }

        override fun onTakePhotoV2(path: String, width: Int, height: Int) {
            log("onTakePhoto--> width = $width,height = $height, path = $path")
        }
    }

    private val mICameraStateLister = object : IMediaStateLister.Stub() {
        override fun onCameraResolutionChange(width: Int, height: Int) {
            log("onCameraResolutionChange: width = $width, height = $height")
        }

        override fun onCameraError(code: Int, errorMsg: String) {
            log("onCameraError code=${code}, errorMsg = $errorMsg")
        }
    }

    private val mAIChaLister = object : AiChatListener.Stub() {
        override fun onContinuousModeUpdate(continuousMode: Boolean, timeout: Long, keepSessionActive: Boolean) {
            log("onContinuousModeUpdate: continuousMode = $continuousMode, timeout = $timeout, keepSessionActive = $keepSessionActive")
        }

        /**
         * ai回答的答案
         * @param answer 答案，流式的
         * @param isFinish 答案的终止符号
         */
        override fun onAiChatAnswer(answer: String?, isFinish: Boolean, contentType: String?, sessionId: String?) {
            log("onAiChatAnswer: answer = $answer, isFinish = $isFinish, contentType = $contentType, sessionId = $sessionId")
        }

        /**
         * ai问题的错误回调
         * @param code 错误码
         * @param message 错误信息
         */
        override fun onError(code: Int, message: String) {
            log("aiChat onError: code = $code, message = $message")
        }

        override fun onAiTakePhoto(filePath: String) {
            log("onAiTakePhoto: filePath = $filePath")
        }
    }

    private val videoCallback = object : VideoCallback.Stub() {
        override fun onError() {
            log("onError: ")
        }

        override fun onFinish() {
            log("onFinish: ")
        }

        override fun onNewFile(startTime: Long, endTime: Long, path: String, isLast: Boolean) {
            log("onNewFile: $path")
        }

        override fun onErrorWithDetail(code: Int, errorMsg: String) {
            log("onError: code = $code, errorMsg = $errorMsg")
        }

        override fun onStart() {
            log("onStart:")
        }
    }

    private val logBuilder = StringBuilder()
    private fun log(msg: String) {
        if (logBuilder.length > 5000) {
            logBuilder.clear()
        }
        logBuilder.insert(0, "$msg\n")
        lifecycleScope.launch {
            binding.tvLog.text = logBuilder.toString()
        }
        Log.d(TAG,"-----------msg=${msg}")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy: ")
        GlassSdk.getGlassMediaService()?.removePhotoCallback(mPhotoFileCallback)
        GlassSdk.getGlassMediaService()?.removeMediaStateLister(mICameraStateLister)
        GlassSdk.getGlassMediaService()?.stopAudioRecord(mAudioCallback)
        GlassSdk.getGlassMediaService()?.stopRecord()
        stopAudioWrite()
    }

}