package com.rokid.glass


import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.rokid.glass.base.BaseActivity
import com.rokid.glass.data.GlobalData
import com.rokid.glesse.databinding.ActivityTrackBinding
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.sdk.base.data.media.PreviewResolution
import com.rokid.security.glass3.sdk.base.data.media.RecordConfig
import com.rokid.security.glass3.sdk.base.data.recog.offline.bean.FaceModel
import com.rokid.security.glass3.sdk.base.data.recog.offline.bean.LPRModel
import com.rokid.security.sdk.base.common.out.AIRecgMode.Companion.MODE_LPR
import com.rokid.security.system.server.media.callback.VideoCallback
import com.rokid.security.system.server.recog.online.listener.IGlassDetectionListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GlassLprTrackActivity : BaseActivity() {
    private val TAG = "GlassLprTrackActivity"
    private lateinit var binding: ActivityTrackBinding

    private val mAbsGlassTrackService by lazy {
        GlassSdk.getGlassOnlineRecService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrackBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 添加车牌识别回调
        mAbsGlassTrackService?.setGlassOnlineRecListener(mTrackListener)
        // 离线车牌识别模式
        mAbsGlassTrackService?.startDetection(MODE_LPR)

//        val min = 30
//        val enableAudio = true
//        val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath + "/video"
//        val recordConfig = RecordConfig(
//            path, min, enableAudio,
//            width = PreviewResolution.ResolutionInfo_1080P_Land.width,
//            height = PreviewResolution.ResolutionInfo_1080P_Land.height
//        )
//        // 开始视频录制
//        GlassSdk.getGlassMediaService()?.startRecord(videoCallback, recordConfig)
    }

    private val videoCallback = object : VideoCallback.Stub() {
        override fun onError() {
            Log.d(TAG, "onError: ")
        }

        override fun onFinish() {
            Log.d(TAG, "onFinish: ")
        }

        override fun onNewFile(startTime: Long, endTime: Long, path: String, isLast: Boolean) {
            Log.d(TAG, "onNewFile: $path")
        }

        override fun onErrorWithDetail(code: Int, errorMsg: String) {
            Log.d(TAG, "onError: code = $code, errorMsg = $errorMsg")
        }

        override fun onStart() {
            Log.d(TAG, "onStart: ")
        }

    }

    /**
     *  车牌识别回调
     */
    private val mTrackListener = object : IGlassDetectionListener.Stub() {
        /**
         * 检测模式发送改变的回调
         * @param mode
         */
        override fun onModeChange(mode: Int) {
            // 还有在扫描的情况下更改状态MODE_NONE，onModeChange这个方法也没有执行
        }

        /**
         * 人脸检测的回调(可用与更新人脸追踪框)
         * @param faceModels
         */
        override fun onFaceTrack(faceModels: List<FaceModel>) {
        }

        /**
         * 车牌识别的回调
         * @param lprModel
         */
        @SuppressLint("DefaultLocale")
        override fun onLPRTrack(lprModel: LPRModel) {
            lifecycleScope.launch(Dispatchers.Main) {
                Log.e(TAG, "车牌号:${lprModel.plateNo}")
                val msg = String.format("车牌颜色:%s 车牌号:%s 分数:%.2f", lprModel.color, lprModel.plateNo, lprModel.score)
                log(msg)
                if (GlobalData.btConnectState.value) {
                    GlassSdk.getGlassMessageService()?.sendTextMessageByClassicBT(msg)
                }
            }
        }

        /**
         * 筛选最优的人脸图像 (可用于在线人脸识别接口的调用)
         */
        override fun onProcessedFaceModels(processedFaceModels: List<FaceModel>) {
        }
    }

    private val logBuilder = StringBuilder()
    private fun log(msg: String) {
        if (logBuilder.length > 5000) {
            logBuilder.clear()
        }
        logBuilder.insert(0, "$msg\n")
        binding.tvLog.text = logBuilder.toString()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 停止车牌识别
        mAbsGlassTrackService?.stopDetection()
        // 移除车牌识别回调
        mAbsGlassTrackService?.removeGlassOnlineRecListener(mTrackListener)
        // 停止视频录制
//        GlassSdk.getGlassMediaService()?.stopRecord()
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

}