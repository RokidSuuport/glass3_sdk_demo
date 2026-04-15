//package com.rokid.glass
//
//import android.content.Context
//import android.os.Bundle
//import android.util.Log
//import androidx.activity.ComponentActivity
//import androidx.lifecycle.lifecycleScope
//import com.rokid.glesse.databinding.ActivityOfflineRecFaceTrackBinding
//import com.rokid.security.glass3.open.sdk.GlassSdk
//import com.rokid.security.glass3.sdk.api.GlassSDK
//import com.rokid.security.glass3.sdk.api.recog.offline.bean.FaceModel
//import com.rokid.security.glass3.sdk.api.recog.offline.bean.FaceRecgResult
//import com.rokid.security.glass3.sdk.api.recog.offline.bean.LPRModel
//import com.rokid.security.glass3.sdk.api.recog.offline.listener.IGlassRecListener
//import com.rokid.security.glass3.sdk.api.recog.online.listener.IGlassDetectionListener
//import com.rokid.security.sdk.base.common.out.AIRecgMode.Companion.MODE_FACE
//
//import kotlinx.coroutines.launch
//import java.io.File
//import java.io.FileOutputStream
//import java.io.IOException
//
//
//class GlassOfflineRecFaceTrackActivity : ComponentActivity() {
//    private val TAG = "GlassOfflineRecFaceTrackActivity::"
//    private lateinit var binding: ActivityOfflineRecFaceTrackBinding
//    private val mAbsGlassTrackService by lazy {
//        GlassSdk.getGlassOnlineRecService()
//    }
//
//
//    private val mAbsGlassOfflineFeatureRecService by lazy {
//        GlassSdk.getGlassOfflineFeatureRecService()
//
//    }
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        binding = ActivityOfflineRecFaceTrackBinding.inflate(layoutInflater)
//        setContentView(binding.root)
//        initView()
//    }
//
//    private fun initView() {
//        binding.btStartTrack.setOnClickListener {
//
//            GlassSdk.getGlassOfflineFeatureRecService()?.startRecognition(MODE_FACE,mRecListener)
////            mAbsGlassTrackService?.startDetection(MODE_FACE)
//        }
//
//        binding.btStopTrack.setOnClickListener {
////            mAbsGlassTrackService?.stopDetection()
//            GlassSdk.getGlassOfflineFeatureRecService()?.stopRecognition(mRecListener)
//        }
//        binding.btAddTrack.setOnClickListener {
//            Log.e(TAG, "开始加载")
//            var sdCardFilesDir = File(this.getExternalFilesDir(null), "demoimage.bin").absolutePath
//            GlassSdk.getGlassOfflineFeatureRecService()
//                ?.addFaceFeatureFile("demoimage.bin", sdCardFilesDir.toString())
//        }
//
//        mAbsGlassTrackService?.setGlassOnlineRecListener(mTrackListener)
////        mAbsGlassOfflineFeatureRecService.addFaceFeatureFile()
//
//        val assetFileName = "demoimage.bin"
//        val externalFilesDir =
//            getExternalFilesDir(null)?.absolutePath // Android/data/your_package/files
//
//        externalFilesDir?.let { copyAssetToSdCard(this, assetFileName, it) }
//
//    }
//
//    private val mRecListener = object : IGlassRecListener {
//        override fun onFaceRecognize(result: FaceRecgResult) {
////            TODO("Not yet implemented")
////            binding.tvLog.text=result.label.toString()
//            Log.e(TAG,"RecListenerFaceRecgResult:$result")
//            Log.e(TAG,"RecListenerFaceRecgResultlabel:${result.label}")
//            Log.e(TAG,"RecListenerFaceRecgResultregScore:${result.regScore}")
////            Log.e(TAG,"RecListenerFaceRecgResult:${result}")
//        }
//
//        override fun onFaceTrack(faceModels: List<FaceModel>) {
////            TODO("Not yet implemented")
//            Log.e(TAG,"RecListenerfaceModels:$faceModels")
//        }
//
//        override fun onLPRTrack(lprModel: LPRModel) {
////            TODO("Not yet implemented")
//            Log.e(TAG,"lprModel:$lprModel")
//        }
//
//
//    }
//
//    private val mTrackListener = object : IGlassDetectionListener {
//        override fun onFaceTrack(faceModels: List<FaceModel>) {
////            TODO("Not yet implemented")
//            Log.e(TAG, "faceModels:$faceModels")
//        }
//
//        override fun onLPRTrack(lprModel: LPRModel) {
////            TODO("Not yet implemented")
//            Log.e(TAG, "lprModelcolor:${lprModel.color}")
//            Log.e(TAG, "lprModelplateNo:${lprModel.plateNo}")
//            Log.e(TAG, "lprModel:$lprModel")
//        }
//
//        override fun onProcessedFaceModels(processedFaceModels: List<FaceModel>) {
////            TODO("Not yet implemented")
//            Log.e(TAG, "processedFaceModels:$processedFaceModels")
//        }
//
//    }
//
//
////    private val logBuilder = StringBuilder()
////    private fun log(msg: String) {
//////        logBuilder.append(msg).append("\n")
////        logBuilder.insert(0, "$msg\n")
////        lifecycleScope.launch {
////            binding.tvLog.text = logBuilder.toString()
////        }
////    }
//
//    override fun onDestroy() {
//        super.onDestroy()
//        mAbsGlassTrackService?.removeGlassOnlineRecListener(mTrackListener)
//    }
//
//    override fun onResume() {
//        super.onResume()
//        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
//    }
//
//    override fun onPause() {
//        super.onPause()
//        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
//    }
//
//    fun copyAssetToSdCard(context: Context, assetFileName: String, destinationPath: String) {
//        try {
//            // 1. 打开 assets 文件输入流
//            val inputStream = context.assets.open(assetFileName)
//
//            // 2. 创建目标文件（确保目录存在）
//            val destFile = File(destinationPath, assetFileName)
//            destFile.parentFile?.mkdirs() // 创建父目录（如果不存在）
//
//            // 3. 创建文件输出流
//            val outputStream = FileOutputStream(destFile)
//
//            // 4. 缓冲读写（提高性能）
//            val buffer = ByteArray(1024)
//            var length: Int
//            while (inputStream.read(buffer).also { length = it } > 0) {
//                outputStream.write(buffer, 0, length)
//            }
//
//            // 5. 关闭流
//            outputStream.flush()
//            outputStream.close()
//            inputStream.close()
//
//            Log.e(TAG, "文件复制成功: ${destFile.absolutePath}")
//        } catch (e: IOException) {
//            e.printStackTrace()
//            Log.e(TAG, "文件复制失败: ${e.message}")
//        }
//    }
//}