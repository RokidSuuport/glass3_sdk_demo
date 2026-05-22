package com.rokid.glass.camera

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.rokid.glass.utils.Nv21VideoRecorder
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.camera.CameraShareHelper
import com.rokid.security.glass3.sdk.base.data.media.CameraShareConfig
import com.rokid.glesse.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mix 录制模式 Fragment
 * 相机+屏幕叠加录制 MP4
 */
class MixRecordFragment : Fragment() {

    companion object {
        private const val TAG = "MixRecordFragment"

        /** AR Mix 导出常用预览参数（与 [Nv21ExportFragment] 默认 1080P 竖屏区分，Mix 走 GPU 叠加管线）。 */
        private val DEFAULT_MIX_CONFIG = CameraShareConfig(
            previewWidth = 720,
            previewHeight = 1280,
            previewTargetFps = 15,
            enableVideoStabilization = false,
            zoomLevel = 1,
        )

        fun newInstance(): MixRecordFragment {
            return MixRecordFragment()
        }
    }

    private lateinit var recordPanel: LinearLayout
    private lateinit var tvRecordTime: TextView
    private lateinit var tvRecordInfo: TextView
    private lateinit var tvOverlayTime: TextView
    
    private var nv21Helper: CameraShareHelper? = null
    private var nv21Recorder: Nv21VideoRecorder? = null
    private var recordStartTime = 0L
    private var frameCount = 0L
    private var lastFpsTime = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val overlayTimeUpdater = object : Runnable {
        override fun run() {
            tvOverlayTime.text = timeFormat.format(Date())
            mainHandler.postDelayed(this, 1000)
        }
    }

    private val recordTimeUpdater = object : Runnable {
        override fun run() {
            val elapsed = (System.currentTimeMillis() - recordStartTime) / 1000
            val min = elapsed / 60
            val sec = elapsed % 60
            tvRecordTime.text = "%02d:%02d".format(min, sec)
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_mix_record, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        recordPanel = view.findViewById(R.id.record_panel)
        tvRecordTime = view.findViewById(R.id.tv_record_time)
        tvRecordInfo = view.findViewById(R.id.tv_record_info)
        tvOverlayTime = view.findViewById(R.id.tv_overlay_time)
        
        tvOverlayTime.text = timeFormat.format(Date())
        mainHandler.post(overlayTimeUpdater)
        
        startMixMode()
    }

    override fun onPause() {
        releaseMixResources("onPause")
        super.onPause()
    }

    override fun onDestroyView() {
        mainHandler.removeCallbacks(overlayTimeUpdater)
        mainHandler.removeCallbacks(recordTimeUpdater)
        releaseMixResources("onDestroyView")
        super.onDestroyView()
    }

    /**
     * 与 [Nv21ExportFragment.releaseNv21Resources] 对齐：Activity 退后台或 Fragment 销毁时释放 NV21/Mix 会话，
     * 避免相机与编码器在后台仍占用。
     */
    private fun releaseMixResources(reason: String) {
        mainHandler.removeCallbacks(recordTimeUpdater)
        val helper = nv21Helper
        if (helper == null && nv21Recorder == null) return
        Log.d(TAG, "$reason: stopping mix record and releasing NV21 export")
        stopMixMode()
    }

    private fun startMixMode() {
        if (!GlassSdk.isReady()) {
            Log.e(TAG, "GlassSdk not ready for mix mode")
            return
        }
        if (nv21Helper?.isNv21Active() == true) {
            Log.w(TAG, "startMixMode: already active, skip")
            return
        }

        val helper = CameraShareHelper()
        nv21Helper = helper
        helper.initNv21ExportWithConfig(
            enableMix = true,
            config = DEFAULT_MIX_CONFIG,
            callback = object : CameraShareHelper.Nv21Callback {
                override fun onCameraOpened(width: Int, height: Int) {
                    Log.d(TAG, "Mix camera opened: ${width}x${height}")
                    nv21Recorder = Nv21VideoRecorder(requireContext(), width, height)
                    val path = nv21Recorder!!.start()
                    recordStartTime = System.currentTimeMillis()
                    activity?.runOnUiThread {
                        tvRecordInfo.text = "录制中: ${width}x${height}\n文件: $path"
                        mainHandler.post(recordTimeUpdater)
                    }
                }

                override fun onNv21Frame(nv21: ByteArray, width: Int, height: Int, timestamp: Long) {
                    nv21Recorder?.encodeFrame(nv21)
                    updateFps(width, height)
                }

                override fun onCameraClosed() {
                    Log.d(TAG, "Mix camera closed")
                }

                override fun onError(code: Int, msg: String) {
                    Log.e(TAG, "Mix error: code=$code, msg=$msg")
                    activity?.runOnUiThread {
                        tvRecordInfo.text = "错误: $code, $msg"
                    }
                }
            },
        )
    }

    private fun stopMixMode() {
        nv21Recorder?.stop { path ->
            Log.d(TAG, "Recording saved: $path")
            activity?.runOnUiThread {
                if (isAdded) {
                    tvRecordInfo.text = "已保存: $path"
                }
            }
        }
        nv21Recorder = null
        nv21Helper?.let { helper ->
            if (helper.isNv21Active()) {
                try {
                    helper.releaseNv21Export()
                    Log.d(TAG, "stopMixMode: NV21 export released")
                } catch (e: Exception) {
                    Log.e(TAG, "stopMixMode: releaseNv21Export failed: ${e.message}", e)
                }
            }
        }
        nv21Helper = null
    }

    private fun updateFps(width: Int, height: Int) {
        frameCount++
        val now = System.currentTimeMillis()
        if (lastFpsTime == 0L) lastFpsTime = now
        val elapsed = now - lastFpsTime
        if (elapsed >= 1000) {
            val fps = frameCount * 1000f / elapsed
            activity?.runOnUiThread {
                tvRecordInfo.text = "录制中: ${width}x${height}\n帧率: %.1f fps".format(fps)
            }
            frameCount = 0
            lastFpsTime = now
        }
    }
}
