package com.rokid.glass

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.lifecycleScope
import com.rokid.glass.base.BaseGlassActivity
import com.rokid.glesse.databinding.ActivityQrcodeViewBinding
import com.google.mlkit.vision.barcode.common.Barcode
import com.rokid.security.glass3.qrcode.api.GlassScanCallback
import com.rokid.security.glass3.qrcode.model.ScanType
import kotlinx.coroutines.launch

class ActivityQRView : BaseGlassActivity() {

    private lateinit var binding: ActivityQrcodeViewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterFullscreen()
        binding = ActivityQrcodeViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        @SuppressLint("SetTextI18n")
        binding.scannerView.startScan(
            object : GlassScanCallback {
                override fun onScanSuccess(content: String?, barcode: Barcode) {
                    // 扫码成功
                    log(content ?: "没有返回结果")
                }

                override fun onScanFailure(error: String) {
                    // 扫码失败
                    log(error)
                }
            }, scanType = ScanType.QR_CODE_ONLY,
            scanIntervalMs = 400,
            stopOnSuccess = false,
            cameraZoomLevel = 5
        )
        // 设置取景框比例
        binding.scannerView.setViewfinderFrameRatio(0.4f)
    }

    private val logBuilder = StringBuilder(4000)

    private fun enterFullscreen() {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    private fun log(msg: String) {
        if (logBuilder.length > 4000) {
            logBuilder.delete(0, logBuilder.length)
        }
        logBuilder.insert(0, "$msg\n")
        lifecycleScope.launch {
            binding.tvResult.text = logBuilder.toString()
        }
        Log.d("ActivityQRView", msg)
    }


}
