package com.rokid.glass.camera

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.rokid.glass.view.glsurface.view.BackgroundGLSurfaceView
import com.rokid.glesse.R
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.camera.CameraShareHelper
import com.rokid.security.glass3.sdk.base.data.media.CameraShareConfig

/**
 * NV21 导出模式 Fragment
 * 显示纯相机 NV21 数据（不含屏幕叠加）
 * 支持用户自定义分辨率、FPS、Zoom、防抖等参数
 */
class Nv21ExportFragment : Fragment() {

    companion object {
        private const val TAG = "Nv21ExportFragment"
        
        fun newInstance(): Nv21ExportFragment {
            return Nv21ExportFragment()
        }
        
        // 支持的 FPS 选项
        private val FPS_OPTIONS = listOf(15, 20, 24, 30)
        
        /**
         * 获取 Camera 支持的分辨率列表（对调宽高以适配竖屏显示）
         * 
         * 通过 AIDL 接口从 system-server 动态获取 Camera API 支持的分辨率列表
         * 过滤掉高于 2268x3024 的分辨率
         * 
         * @return List<Triple<displayText, width, height>>
         */
        fun getSupportedResolutions(): List<Pair<String, Triple<Int, Int, Boolean>>> {
            return try {
                Log.d(TAG, "getSupportedResolutions: calling CameraShareHelper.getSupportedPreviewSizes()...")
                val cameraSizes = CameraShareHelper().getSupportedPreviewSizes()
                if (cameraSizes.isNotEmpty()) {
                    Log.d(TAG, "getSupportedResolutions: successfully got ${cameraSizes.size} sizes from AIDL")
                    
                    // 过滤掉高于 2268x3024 的分辨率
                    val filteredSizes = cameraSizes.filter { size ->
                        val maxDimension = maxOf(size.first, size.second)
                        val minDimension = minOf(size.first, size.second)
                        // 过滤条件：最大维度 <= 3024 且 最小维度 <= 2268
                        maxDimension <= 3024 && minDimension <= 2268
                    }
                    
                    Log.d(TAG, "getSupportedResolutions: filtered from ${cameraSizes.size} to ${filteredSizes.size} sizes (max 2268x3024)")
                    
                    if (filteredSizes.isNotEmpty()) {
                        val result = filteredSizes.map { size ->
                            val displayText = "${size.first}x${size.second} (${if (size.third) "竖屏" else "横屏"})"
                            Log.d(TAG, "getSupportedResolutions: ${displayText}")
                            displayText to size
                        }.sortedByDescending { it.second.first * it.second.second } // 按分辨率从高到低排序
                        Log.d(TAG, "getSupportedResolutions: returning ${result.size} resolutions")
                        result
                    } else {
                        Log.w(TAG, "getSupportedResolutions: all sizes filtered out, using fallback")
                        getFallbackResolutions()
                    }
                } else {
                    Log.w(TAG, "getSupportedResolutions: AIDL returned empty list, using fallback")
                    getFallbackResolutions()
                }
            } catch (e: Exception) {
                Log.e(TAG, "getSupportedResolutions: failed to get Camera sizes via AIDL, using fallback: ${e.message}", e)
                getFallbackResolutions()
            }
        }
        
        /**
         * Fallback 分辨率列表（当 Camera API 不可用时使用）
         * 注意：这里的分辨率是视觉效果分辨率（已对调宽高）
         * - Camera 原始分辨率是横屏的（width > height），例如 1280x720
         * - 对调后变成竖屏视觉效果，例如 720x1280
         */
        private fun getFallbackResolutions(): List<Pair<String, Triple<Int, Int, Boolean>>> {
            return listOf(
                "640x480 (横屏)" to Triple(640, 480, false),
                "480x640 (竖屏)" to Triple(480, 640, true),
                "1280x720 (横屏)" to Triple(1280, 720, false),
                "720x1280 (竖屏)" to Triple(720, 1280, true),
                "1920x1080 (横屏)" to Triple(1920, 1080, false),
                "1080x1920 (竖屏)" to Triple(1080, 1920, true),
            )
        }
    }

    private lateinit var nv21SurfaceView: BackgroundGLSurfaceView
    private lateinit var tvInfo: TextView
    private lateinit var configPanel: LinearLayout
    private lateinit var btnShowConfig: Button
    private lateinit var btnApply: Button
    private lateinit var btnToggleConfig: Button
    private lateinit var spinnerResolution: Spinner
    private lateinit var spinnerFps: Spinner
    private lateinit var seekbarZoom: SeekBar
    private lateinit var tvZoomValue: TextView
    private lateinit var switchEis: Switch
    
    private val nv21Helper = CameraShareHelper()
    private var frameCount = 0L
    private var lastFpsTime = 0L
    private var currentWidth = 0
    private var currentHeight = 0
    private var currentFps = 0 // 实际应用的 FPS
    private var currentEisEnabled = false // 实际应用的 EIS 状态
    private var currentZoomLevel = 1 // 实际应用的 Zoom
    private var lastFrameTimestamp = 0L // 用于调试帧间隔
    private var frameIntervalSum = 0L
    private var frameIntervalCount = 0
    
    // 当前配置
    private var currentResolutionIndex = 0 // 默认选择 1920x1080（会在初始化时查找）
    private var currentFpsIndex = 0 // 默认 15fps
    private var currentZoom = 1 // 默认 zoom = 1
    private var currentEis = false // 默认关闭防抖
    private var currentConfigText: String = ""
    
    // 动态获取的分辨率列表
    private lateinit var resolutionOptions: List<Pair<String, Triple<Int, Int, Boolean>>>

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_nv21_export, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 动态获取 Camera 支持的分辨率列表（已过滤高于 2268x3024 的分辨率）
        resolutionOptions = getSupportedResolutions()
        
        // 查找 1920x1080 的索引作为默认选项
        currentResolutionIndex = resolutionOptions.indexOfFirst { 
            it.second.first == 1920 && it.second.second == 1080 
        }.takeIf { it >= 0 } ?: 0 // 如果找不到 1920x1080，使用第一个（最高分辨率）
        
        Log.d(TAG, "Default resolution index: $currentResolutionIndex, resolution: ${resolutionOptions[currentResolutionIndex].first}")
        
        nv21SurfaceView = view.findViewById(R.id.nv21_surface_view)
        tvInfo = view.findViewById(R.id.tv_info)
        configPanel = view.findViewById(R.id.config_panel)
        btnShowConfig = view.findViewById(R.id.btn_show_config)
        btnApply = view.findViewById(R.id.btn_apply)
        btnToggleConfig = view.findViewById(R.id.btn_toggle_config)
        spinnerResolution = view.findViewById(R.id.spinner_resolution)
        spinnerFps = view.findViewById(R.id.spinner_fps)
        seekbarZoom = view.findViewById(R.id.seekbar_zoom)
        tvZoomValue = view.findViewById(R.id.tv_zoom_value)
        switchEis = view.findViewById(R.id.switch_eis)
        
        setupConfigUI()
        startNv21Export()
    }

    override fun onResume() {
        super.onResume()
        if (::nv21SurfaceView.isInitialized) {
            nv21SurfaceView.onResume()
        }
    }

    override fun onPause() {
        releaseNv21Resources("onPause")
        if (::nv21SurfaceView.isInitialized) {
            nv21SurfaceView.onPause()
        }
        super.onPause()
    }

    override fun onDestroyView() {
        releaseNv21Resources("onDestroyView")
        if (::nv21SurfaceView.isInitialized) {
            nv21SurfaceView.releasePreview()
        }
        super.onDestroyView()
    }

    private fun releaseNv21Resources(reason: String) {
        if (!nv21Helper.isNv21Active()) return
        Log.d(TAG, "$reason: releasing NV21 export")
        try {
            nv21Helper.releaseNv21Export()
            Log.d(TAG, "$reason: NV21 export released")
        } catch (e: Exception) {
            Log.e(TAG, "$reason: failed to release NV21 export: ${e.message}", e)
        }
    }

    /**
     * 设置配置 UI
     */
    private fun setupConfigUI() {
        // 分辨率 Spinner - 使用动态获取的分辨率列表
        val resolutionAdapter = ArrayAdapter(
            requireContext(),
            R.layout.simple_spinner_item,
            resolutionOptions.map { it.first }
        )
        resolutionAdapter.setDropDownViewResource(R.layout.simple_spinner_dropdown_item)
        spinnerResolution.adapter = resolutionAdapter
        spinnerResolution.setSelection(currentResolutionIndex.coerceIn(0, resolutionOptions.size - 1))
        spinnerResolution.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentResolutionIndex = position
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // FPS Spinner
        val fpsAdapter = ArrayAdapter(
            requireContext(),
            R.layout.simple_spinner_item,
            FPS_OPTIONS.map { "${it} fps" }
        )
        fpsAdapter.setDropDownViewResource(R.layout.simple_spinner_dropdown_item)
        spinnerFps.adapter = fpsAdapter
        spinnerFps.setSelection(currentFpsIndex)
        spinnerFps.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentFpsIndex = position
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // Zoom SeekBar
        seekbarZoom.progress = currentZoom
        tvZoomValue.text = currentZoom.toString()
        seekbarZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentZoom = progress.coerceIn(1, 8)
                tvZoomValue.text = currentZoom.toString()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // EIS Switch
        switchEis.isChecked = currentEis
        switchEis.setOnCheckedChangeListener { _, isChecked ->
            currentEis = isChecked
        }
        
        // 显示配置按钮
        btnShowConfig.setOnClickListener {
            configPanel.visibility = if (configPanel.visibility == View.VISIBLE) {
                View.GONE
            } else {
                View.VISIBLE
            }
            btnShowConfig.text = if (configPanel.visibility == View.VISIBLE) "隐藏" else "配置"
        }
        
        // 隐藏配置按钮
        btnToggleConfig.setOnClickListener {
            configPanel.visibility = View.GONE
            btnShowConfig.text = "配置"
        }
        
        // 应用配置按钮
        btnApply.setOnClickListener {
            applyConfig()
        }
    }
    
    /**
     * 应用用户配置
     */
    private fun applyConfig() {
        val resolutionOption = resolutionOptions[currentResolutionIndex].second
        val width = resolutionOption.first
        val height = resolutionOption.second
        val isPortrait = resolutionOption.third
        val fps = FPS_OPTIONS[currentFpsIndex]
        
        Log.d(TAG, "Applying config: ${width}x${height}@${fps}fps, zoom=$currentZoom, EIS=$currentEis, isPortrait=$isPortrait")
        
        val newConfig = CameraShareConfig(
            previewWidth = width,
            previewHeight = height,
            previewTargetFps = fps,
            enableVideoStabilization = currentEis,
            zoomLevel = currentZoom,
        )

        Log.d(TAG, "applyConfig: ${width}x${height}@${fps}fps, zoom=$currentZoom")
        nv21Helper.restartNv21ExportWithConfig(
            enableMix = false,
            config = newConfig,
            callback = createNv21Callback(isPortrait),
        )
        
        // 更新显示
        val orientationText = if (isPortrait) "竖屏" else "横屏"
        currentConfigText = "配置: ${width}x${height}@${fps}fps ($orientationText), EIS=${if (currentEis) "ON" else "OFF"}, zoom=$currentZoom"
        updateDisplay()

        // 隐藏配置面板
        configPanel.visibility = View.GONE
        btnShowConfig.text = "配置"
    }

    /**
     * 创建 NV21 Callback
     * @param isPortrait 是否为竖屏模式
     */
    private fun createNv21Callback(isPortrait: Boolean = false): CameraShareHelper.Nv21Callback {
        return object : CameraShareHelper.Nv21Callback {
            override fun onCameraOpened(width: Int, height: Int) {
                Log.d(TAG, "NV21 camera opened: ${width}x${height}, isPortrait=$isPortrait")
                currentWidth = width
                currentHeight = height
                if (currentConfigText.isEmpty()) {
                    val orientationText = if (isPortrait) "竖屏" else "横屏"
                    currentConfigText = "配置: ${width}x${height} ($orientationText), EIS=${if (currentEis) "ON" else "OFF"}, zoom=$currentZoom"
                }
                activity?.runOnUiThread {
                    updateDisplay()
                }
            }

            override fun onNv21Frame(nv21: ByteArray, width: Int, height: Int, timestamp: Long) {
                // 调试：打印帧间隔
                val now = System.currentTimeMillis()
                if (lastFrameTimestamp > 0) {
                    val interval = now - lastFrameTimestamp
                    frameIntervalSum += interval
                    frameIntervalCount++
                    if (frameIntervalCount % 30 == 0) {
                        val avgInterval = frameIntervalSum / frameIntervalCount
                        Log.d(TAG, "Frame interval stats: avg=${avgInterval}ms, target=${1000/FPS_OPTIONS[currentFpsIndex]}ms (${FPS_OPTIONS[currentFpsIndex]}fps), actual fps=${1000f/avgInterval}")
                        frameIntervalSum = 0
                        frameIntervalCount = 0
                    }
                }
                lastFrameTimestamp = now

                // Camera 本身已支持竖屏分辨率，直接使用原始数据
                nv21SurfaceView.setPreviewData(nv21, width, height)
                updateFps(width, height)
            }

            override fun onCameraClosed() {
                Log.d(TAG, "NV21 camera closed")
            }

            override fun onError(code: Int, msg: String) {
                Log.e(TAG, "NV21 error: code=$code, msg=$msg")
                activity?.runOnUiThread {
                    tvInfo.text = "错误: $code, $msg"
                }
            }

            override fun onNv21ExportResolutionChanged(width: Int, height: Int, appliedPreviewFps: Int) {
                Log.d(TAG, "onNv21ExportResolutionChanged: ${width}x${height}, fps=$appliedPreviewFps")
                currentWidth = width
                currentHeight = height
                activity?.runOnUiThread {
                    updateDisplay()
                }
            }

            override fun onNv21ExportRuntimeParamsChanged(appliedPreviewFps: Int, videoStabilizationEnabled: Boolean) {
                Log.d(TAG, "onNv21ExportRuntimeParamsChanged: fps=$appliedPreviewFps, EIS=$videoStabilizationEnabled")
                activity?.runOnUiThread {
                    updateDisplay()
                }
            }

            override fun onZoomLevelChanged(zoomLevel: Int) {
                Log.d(TAG, "Zoom level changed: $zoomLevel")
            }
        }
    }

    private fun startNv21Export() {
        if (!GlassSdk.isReady()) {
            Log.e(TAG, "GlassSdk not ready")
            return
        }

        // 使用默认配置启动（选择第一个分辨率，即最高分辨率）
        val resolutionOption = resolutionOptions[currentResolutionIndex.coerceIn(0, resolutionOptions.size - 1)].second
        val initialConfig = CameraShareConfig(
            previewWidth = resolutionOption.first,
            previewHeight = resolutionOption.second,
            previewTargetFps = FPS_OPTIONS[currentFpsIndex],
            enableVideoStabilization = currentEis,
            zoomLevel = currentZoom,
        )

        val isPortrait = resolutionOption.third

        if (nv21Helper.isNv21Active()) {
            Log.w(TAG, "startNv21Export: already active, skip")
            return
        }
        nv21Helper.initNv21ExportWithConfig(
            enableMix = false,
            config = initialConfig,
            callback = createNv21Callback(isPortrait),
        )

        val orientationText = if (isPortrait) "竖屏" else "横屏"
        currentConfigText = "配置: ${initialConfig.previewWidth}x${initialConfig.previewHeight}@${initialConfig.previewTargetFps}fps ($orientationText), EIS=${if (currentEis) "ON" else "OFF"}, zoom=${initialConfig.zoomLevel}"
    }

    private fun updateFps(width: Int, height: Int) {
        frameCount++
        val now = System.currentTimeMillis()
        if (lastFpsTime == 0L) lastFpsTime = now
        val elapsed = now - lastFpsTime
        if (elapsed >= 1000) {
            val fps = frameCount * 1000f / elapsed
            activity?.runOnUiThread {
                updateDisplay(fps)
            }
            frameCount = 0
            lastFpsTime = now
        }
    }

    private fun updateDisplay(fps: Float? = null) {
        val resolutionText = "已连接: ${currentWidth}x${currentHeight}"
        val fpsText = fps?.let { "实时帧率: %.1f fps".format(it) } ?: ""
        activity?.runOnUiThread {
            tvInfo.text = if (fpsText.isNotEmpty()) {
                "$resolutionText\n$currentConfigText\n$fpsText"
            } else {
                "$resolutionText\n$currentConfigText"
            }
        }
    }
}
