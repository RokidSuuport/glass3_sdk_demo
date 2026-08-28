package com.rokid.glass


import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.rokid.glass.base.BaseActivity
import com.rokid.glass.utils.FileSizeUtil
import com.rokid.glass.utils.TimeUtils
import com.rokid.glesse.databinding.ActivityFaceTrackBinding
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.sdk.base.data.recog.offline.bean.FaceModel
import com.rokid.security.glass3.sdk.base.data.recog.offline.bean.LPRModel
import com.rokid.security.sdk.base.common.out.AIRecgMode.Companion.MODE_FACE
import com.rokid.security.system.server.media.callback.VideoCallback
import com.rokid.security.system.server.recog.online.listener.IGlassDetectionListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class GlassFaceTrackActivity : BaseActivity() {
    private val TAG = "GlassTrackActivity"
    private lateinit var binding: ActivityFaceTrackBinding
    private var lastTrackId: Long = -1L
    private var oldBitmap: Bitmap? = null
    private var lastTime = 0L
    private val logBuilder = StringBuilder()

    /**
     * 离线和在线人脸检测功能
     */
    private val mAbsGlassOnlineRecService by lazy {
        GlassSdk.getGlassOnlineRecService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFaceTrackBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initView()
    }

    private fun initView() {
//        val min = 20
//        val enableAudio = true
//        val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath + "/video"
//        Log.d(TAG, "onCreate: path = $path")
//        val recordConfig = RecordConfig(
//            path, min, enableAudio,
//            width = PreviewResolution.ResolutionInfo_1080P_Land.width,
//            height = PreviewResolution.ResolutionInfo_1080P_Land.height
//        )
//        // 开始视频录制
//        GlassSdk.getGlassMediaService()?.startRecord(videoCallback, recordConfig)
        // 设置人脸检测监听
        mAbsGlassOnlineRecService?.setGlassOnlineRecListener(mIGlassDetectionListener)
        // 开始人脸检测
        mAbsGlassOnlineRecService?.startDetection(MODE_FACE)
        lastTime = System.currentTimeMillis()
    }

    /**
     * 视频录制回调
     */
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

    override fun onDestroy() {
        super.onDestroy()
        // 停止视频录制
//        GlassSdk.getGlassMediaService()?.stopRecord()
        // 停止人脸检测
        mAbsGlassOnlineRecService?.stopDetection()
        // 移除检测监听
        mAbsGlassOnlineRecService?.removeGlassOnlineRecListener(mIGlassDetectionListener)
        if (oldBitmap?.isRecycled == false) {
            oldBitmap?.recycle()
            oldBitmap = null
        }
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        super.onGlassKeyEvent(keyEvent)
        if (keyEvent == com.rokid.glass.base.GlassKeyEvent.KEYCODE_FRONT) {
//            workHomeAdapter?.toNextItem()
        } else if (keyEvent == com.rokid.glass.base.GlassKeyEvent.KEYCODE_BEHIND) {
//            workHomeAdapter?.toPreviousItem()
        }
        return false
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }


    /**
     * 将 Bitmap 转换为 PNG 格式的 byte[]
     * @param bitmap 输入的 Bitmap 对象
     * @return 转换后的 byte[]，如果转换失败则返回 null
     */
//    fun bitmapToPngByteArray(bitmap: Bitmap): ByteArray? {
//        val stream = ByteArrayOutputStream()
//        // quality 参数对 PNG 无效，可以传入任意值
//        val success = bitmap.compress(CompressFormat.PNG, 100, stream)
//        return if (success) {
//            stream.toByteArray()
//        } else {
//            null
//        }
//    }
//    private val simpleDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    /**
     * 人脸检测监听
     */
    private val mIGlassDetectionListener = object : IGlassDetectionListener.Stub() {

        /**
         * 检测模式发送改变的回调
         * @param mode 检测模式
         */
        override fun onModeChange(mode: Int) {
            Log.e(TAG, "onModeChange: $mode")
        }

        /**
         * 人脸检测的回调(可用于更新人脸追踪框)
         * @param faceModels 人脸模型列表
         */
        override fun onFaceTrack(faceModels: List<FaceModel>) {
        }

        /**
         * 车牌识别的回调
         * @param lprModel 车牌模型
         */
        override fun onLPRTrack(lprModel: LPRModel) {
        }

        /**
         * 筛选最优的人脸图像 (可用于在线人脸识别接口的调用)
         * @param processedFaceModels 处理后的人脸模型列表
         */
        @SuppressLint("SetTextI18n")
        override fun onProcessedFaceModels(processedFaceModels: List<FaceModel>) {
            if (processedFaceModels.isEmpty()) {
                log("没有人脸数据")
                return
            }
            val faceModel = processedFaceModels.maxByOrNull { it.rect.width() * it.rect.height() }
            if (faceModel == null) {
                log("未获取到符合要求人脸数据")
                return
            }
            var duration = System.currentTimeMillis() - lastTime
            // 图像质量低于35人脸检测不到
            if (faceModel.iqaScore < 40) {
                lastTime = System.currentTimeMillis()
                val faeInfo =
                    StringBuilder(
                        "时长:${TimeUtils.formatDuration(duration)} 图像质量偏低:${"%.1f".format(faceModel.iqaScore)},人脸置信度:${
                            "%.1f".format(
                                faceModel.faceScore
                            )
                        },人脸跟踪:${faceModel.trackId}"
                    )
                log(faeInfo.toString())
                return
            }
            val faeInfo =
                StringBuilder(
                    "时长:${TimeUtils.formatDuration(duration)} 图像质量:${"%.1f".format(faceModel.iqaScore)},人脸置信度:${
                        "%.1f".format(
                            faceModel.faceScore
                        )
                    },人脸跟踪:${faceModel.trackId}"
                )
            if (faceModel.trackId == lastTrackId) {
                lastTime = System.currentTimeMillis()
                faeInfo.append("人脸图片相同")
                log(faeInfo.toString())
                return
            }
//            lastTrackId = faceModel.trackId
            // 先更新人脸抓拍图
            val smallBitmap = mAbsGlassOnlineRecService?.getFaceSamllBitmap(faceModel.trackId)
            duration = System.currentTimeMillis() - lastTime
            lastTime = System.currentTimeMillis()
            if (smallBitmap == null) {
                log("时长:${TimeUtils.formatDuration(duration)} 未获取到人脸图片")
                return
            }

            // 将抓拍图片保存到系统公共 Pictures 目录。
//            val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
//            val albumDir = File(baseDir, "album")
//            if (!albumDir.exists()) {
//                albumDir.mkdirs() // 自动创建多级目录（DCIM 已存在，仅创建 album）
//            }
//            // 3. 保持原有逻辑：生成时间戳文件名
//            val timeStamp = simpleDateFormat.format(Date())
//            val outputFile = File(albumDir, "IMG_$timeStamp.jpg")
//            FileOutputStream(outputFile).use { fos ->
//                smallBitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
//            }
            if (smallBitmap.isRecycled) {
                return
            }
            // 回收上一张图片
            if (oldBitmap?.isRecycled == false) {
                Log.d(TAG, "回收上一张人脸图片: ${getBitmapSizeInfo(oldBitmap)}")
                oldBitmap?.recycle()
                oldBitmap = null
            }
            oldBitmap = smallBitmap
            val oldBitmapSizeInfo = getBitmapSizeInfo(smallBitmap)
            lifecycleScope.launch(Dispatchers.Main) {
                binding.ivCapture.setImageBitmap(smallBitmap)
                // float 类型保留1位
                var result =
                    "时长:${TimeUtils.formatDuration(duration)} 质量:${
                        "%.1f".format(faceModel.iqaScore)
                    } 人脸跟踪:${faceModel.trackId} 图片大小:$oldBitmapSizeInfo"
                log(result)
            }
        }
    }

    private fun getBitmapSizeInfo(bitmap: Bitmap?): String {
        if (bitmap == null || bitmap.isRecycled) {
            return "0 B"
        }
        val sizeText = FileSizeUtil.formatFileSize(bitmap.allocationByteCount.toDouble())
        return "${bitmap.width}x${bitmap.height}, $sizeText, ${bitmap.config}"
    }

    private fun log(msg: String) {
        if (logBuilder.length > 5000) {
            logBuilder.clear()
        }
        logBuilder.insert(0, "$msg\n")
        lifecycleScope.launch(Dispatchers.Main) {
            binding.tvLog.text = logBuilder.toString()
        }
        Log.d(TAG, "----msg=${msg}")
    }

}
