package com.rokid.glass.camera

import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.rokid.glass.base.GlassKeyEvent
import com.rokid.glass.utils.demoString
import com.rokid.glass.view.glsurface.view.BackgroundGLSurfaceView
import com.rokid.glesse.R
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.camera.CameraShareHelper
import com.rokid.security.glass3.open.sdk.camera.CameraZoomSession
import com.rokid.security.glass3.open.sdk.uitls.log.L
import com.rokid.security.glass3.sdk.base.data.media.CameraShareConfigV2
import com.rokid.security.glass3.sdk.base.data.media.CameraZoomConfig
import com.rokid.security.glass3.sdk.base.data.media.CameraZoomState
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * NV21 导出模式 Fragment
 * Fragment for NV21 export mode.
 * 显示纯相机 NV21 数据（不含屏幕叠加）
 * Display raw camera NV21 data without screen overlays.
 * 支持用户自定义分辨率、FPS、Zoom、防抖等参数
 * Allow custom resolution, FPS, zoom, stabilization, and other parameters.
 *
 * 适用场景：算法/识别库明确接收 NV21（Y + 交错 VU），或需要兼容既有 NV21 数据处理代码。
 * 仅做 GPU 显示/合成优先 Surface；下游需要独立 Y/U/V 平面和异步租约时参考 I420ExportFragment。
 * Buffer 回调的数据仅在回调内借用，ByteArray 兼容回调也会复用数组；异步消费必须先复制。
 * Buffer 入口本身也走共享内存；I420 的主要差别是独立 Y/U/V 平面，以及可持有至 close 的帧租约。
 * 本页能看到画面，是因为额外接入 BackgroundGLSurfaceView：回调复制导出帧，GL shader 转 RGB。
 * NV21 导出本身不负责显示；I420 页同样通过自己的三平面渲染组件显示。Surface 文字水印不会
 * 自动附带到纯相机 NV21 数据。Options 与旧 Config 都保留真实调用入口，便于兼容性对照。
 */
class Nv21ExportFragment : Fragment(), CameraShareGestureHandler {

    companion object {
        private const val TAG = "Nv21ExportFragment"

        fun newInstance(): Nv21ExportFragment {
            return Nv21ExportFragment()
        }

        // 支持的 FPS 选项
        // Supported FPS options.
        private val FPS_OPTIONS = listOf(15, 20, 24, 30)

        /**
         * 获取 Camera 支持的分辨率列表（对调宽高以适配竖屏显示）
         * Get camera-supported resolutions, swapping width and height for portrait display.
         *
         * 通过 AIDL 接口从 system-server 动态获取 Camera API 支持的分辨率列表
         * Dynamically obtain the resolutions supported by the Camera API from system-server through AIDL.
         * 过滤掉高于 2268x3024 的分辨率
         * Filter out resolutions above 2268 x 3024.
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
                    // Filter out resolutions above 2268 x 3024.
                    val filteredSizes = cameraSizes.filter { size ->
                        val maxDimension = maxOf(size.first, size.second)
                        val minDimension = minOf(size.first, size.second)
                        // 过滤条件：最大维度 <= 3024 且 最小维度 <= 2268
                        // Filter condition: the larger dimension is at most 3024 and the smaller dimension is at most 2268.
                        maxDimension <= 3024 && minDimension <= 2268
                    }

                    Log.d(TAG, "getSupportedResolutions: filtered from ${cameraSizes.size} to ${filteredSizes.size} sizes (max 2268x3024)")

                    if (filteredSizes.isNotEmpty()) {
                        // 按分辨率从高到低排序
                        // Sort resolutions from highest to lowest.
                        val result = filteredSizes.map { size ->
                            val displayText = demoString(R.string.demo_x, size.first, size.second, if (size.third) demoString(R.string.demo_portrait) else demoString(R.string.demo_landscape))
                            Log.d(TAG, "getSupportedResolutions: ${displayText}")
                            displayText to size
                        }.sortedByDescending { it.second.first * it.second.second }
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
         * Fallback resolution list for when the Camera API is unavailable.
         * 注意：这里的分辨率是视觉效果分辨率（已对调宽高）
         * Note: these are visual display resolutions, with width and height already swapped.
         * - Camera 原始分辨率是横屏的（width > height），例如 1280x720
         * - The camera's original resolution is landscape (width > height), for example 1280 x 720.
         * - 对调后变成竖屏视觉效果，例如 720x1280
         * - Swapping the dimensions produces a portrait display, for example 720 x 1280.
         */
        private fun getFallbackResolutions(): List<Pair<String, Triple<Int, Int, Boolean>>> {
            return listOf(
                demoString(R.string.demo_640x480_landscape) to Triple(640, 480, false),
                demoString(R.string.demo_480x640_portrait) to Triple(480, 640, true),
                demoString(R.string.demo_1280x720_landscape) to Triple(1280, 720, false),
                demoString(R.string.demo_720x1280_portrait) to Triple(720, 1280, true),
                demoString(R.string.demo_1920x1080_landscape) to Triple(1920, 1080, false),
                demoString(R.string.demo_1080x1920_portrait) to Triple(1080, 1920, true),
            )
        }
    }

    private lateinit var nv21SurfaceView: BackgroundGLSurfaceView
    private lateinit var tvInfo: TextView
    private lateinit var configPanel: View
    private lateinit var btnShowConfig: Button
    private lateinit var btnApply: Button
    private lateinit var btnToggleConfig: Button
    private lateinit var spinnerResolution: Spinner
    private lateinit var spinnerFps: Spinner
    private lateinit var seekbarZoom: SeekBar
    private lateinit var tvZoomValue: TextView
    private lateinit var tvActualZoom: TextView
    private lateinit var switchEis: Switch
    private lateinit var btnApiMode: Button
    private val sessionControls = mutableListOf<Button>()
    private var selectedMode = Nv21ExportMode.OPTIONS_BUFFER
    @Volatile private var activeMode: Nv21ExportMode? = null
    private val zoomOptions get() = selectedMode.zoomOptions
    private lateinit var configControls: List<View>
    private var selectedConfigIndex = 0
    private var queryOverlay: LinearLayout? = null
    private var queryScroll: ScrollView? = null
    private var queryGeneration = 0

    private val nv21Helper = CameraShareHelper()
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val cameraDispatcher = cameraExecutor.asCoroutineDispatcher()
    private var zoomStateText = ""
    private var zoomCommandText = ""
    private val frameEvidenceLock = Any()
    private var confirmedFrameGeneration = 0L

    private fun observeZoom(token: Int) {
        nv21Helper.nv21ZoomSession.listener = object : CameraZoomSession.Listener {
            override fun onStateChanged(state: CameraZoomState) {
                if (token != callbackGeneration) return
                synchronized(frameEvidenceLock) {
                    if (!state.ready || state.frameGeneration != confirmedFrameGeneration) {
                        confirmedFrameGeneration = if (state.ready) state.frameGeneration else 0L
                        nv21SurfaceView.releasePreview() // Discard pending copies from the previous configuration.
                    }
                }
                zoomStateText = cameraApiState(state)
                L.i("CameraDemo", "NV21 state: $zoomStateText")
                updateDisplay()
            }
            override fun onError(code: Int, reason: String) { if (token == callbackGeneration) L.w("CameraDemo", "NV21 session error=$code: $reason") }
        }
    }
    private var configSwitchJob: Job? = null
    @Volatile private var callbackGeneration = 0
    private val previewPreferences get() = requireContext().getSharedPreferences("nv21_preview", 0)
    private var frameCount = 0L
    private var lastFpsTime = 0L
    private var lastRenderedFrameCount = 0L
    private var lastDroppedFrameCount = 0L
    private var currentWidth = 0
    private var currentHeight = 0
    // 实际应用的 FPS
    // The FPS actually applied.
    @Volatile private var currentFps: Int? = null
    // 实际应用的 EIS 状态
    // The EIS state actually applied.
    @Volatile private var currentEisEnabled: Boolean? = null
    // 用于调试帧间隔
    // Used to debug frame intervals.
    private var lastFrameTimestamp = 0L
    private var frameIntervalSum = 0L
    private var frameIntervalCount = 0

    // 当前配置
    // Current configuration.
    // 默认选择 1920x1080（会在初始化时查找）
    // Select 1920 x 1080 by default; it will be located during initialization.
    private var currentResolutionIndex = 0
    // 默认 15fps
    // Default: 15 fps.
    private var currentFpsIndex = 0
    // 默认 zoom = 1
    // Default: zoom = 1.
    private var currentZoom = 1f
    // 默认关闭防抖
    // Disable stabilization by default.
    private var currentEis = false
    private var currentConfigText: String = ""

    // 动态获取的分辨率列表
    // Dynamically obtained resolution list.
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
        // Dynamically obtain the resolutions supported by Camera, excluding resolutions above 2268 x 3024.
        resolutionOptions = getSupportedResolutions()

        val preferences = previewPreferences
        val savedWidth = preferences.getInt("width", 1920)
        val savedHeight = preferences.getInt("height", 1080)
        currentResolutionIndex = resolutionOptions.indexOfFirst {
            it.second.first == savedWidth && it.second.second == savedHeight
        }.takeIf { it >= 0 } ?: resolutionOptions.indexOfFirst {
            it.second.first == 1920 && it.second.second == 1080
        }.coerceAtLeast(0)
        currentFpsIndex = FPS_OPTIONS.indexOf(preferences.getInt("fps", 15)).coerceAtLeast(0)
        val savedZoom = if (preferences.contains("zoom_ratio")) preferences.getFloat("zoom_ratio", 1f) else preferences.getInt("zoom", 1).toFloat()
        selectedMode = Nv21ExportMode.entries.firstOrNull {
            it.name == preferences.getString("api_mode", null)
        } ?: Nv21ExportMode.OPTIONS_BUFFER
        currentZoom = savedZoom.takeIf { it in zoomOptions } ?: 1f
        currentEis = preferences.getBoolean("eis", false)
        Log.d(TAG, "Restored requested resolution: ${resolutionOptions[currentResolutionIndex].first}")

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
        tvActualZoom = view.findViewById(R.id.tv_actual_zoom)
        switchEis = view.findViewById(R.id.switch_eis)
        btnApiMode = view.findViewById(R.id.btn_nv21_api)
        sessionControls.clear()
        configControls = listOf(
            btnApiMode,
            view.findViewById<View>(R.id.row_resolution),
            view.findViewById<View>(R.id.row_fps),
            view.findViewById<View>(R.id.row_zoom),
            view.findViewById<View>(R.id.row_eis),
            btnApply,
            btnToggleConfig,
        )
        val extra = mutableListOf<View>()
        val panelRows = (configPanel as android.widget.ScrollView).getChildAt(0) as LinearLayout
        val footer = btnApply.parent as View
        val details = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        fun compact(button: Button) = button.apply {
            textSize = 13f; isAllCaps = false
            setBackgroundResource(R.drawable.camera_mode_button_bg)
            setTextColor(android.graphics.Color.WHITE)
            val unit = resources.displayMetrics.density
            minHeight = (32 * unit).toInt(); minimumHeight = minHeight
            setPadding((4 * unit).toInt(), (3 * unit).toInt(), (4 * unit).toInt(), (3 * unit).toInt())
        }
        var expanded = false
        val more = compact(Button(requireContext())).apply {
            tag = "nv21_more"
            setText(R.string.camera_zoom_and_status)
            setOnClickListener {
                expanded = !expanded
                details.visibility = if (expanded) View.VISIBLE else View.GONE
                setText(if (expanded) R.string.camera_hide_options else R.string.camera_zoom_and_status)
            }
        }
        panelRows.addView(more, panelRows.indexOfChild(footer))
        panelRows.addView(details, panelRows.indexOfChild(footer))
        fun control(zh: String, en: String, block: () -> Unit): Button {
            val button = compact(Button(requireContext())).apply {
                text = if (resources.configuration.locales[0].language == "zh") zh else en
                setOnClickListener { block() }
            }
            details.addView(button); extra.add(button)
            return button
        }
        sessionControls += control("仅应用倍率（不重启）", "Apply zoom only (no restart)") {
            if (!canControlSession()) return@control
            val ratio = currentZoom
            val token = callbackGeneration
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching { withContext(cameraDispatcher) { nv21Helper.nv21ZoomSession.setZoom(ratio) } }
                    .onSuccess {
                        L.i("CameraDemo", "NV21 setZoom request=$ratio result=$it")
                        if (token == callbackGeneration) {
                            zoomCommandText = cameraZoomResult("NV21 control", it)
                            zoomStateText = cameraApiState(nv21Helper.nv21ZoomSession.state)
                            updateDisplay()
                        }
                    }
                    .onFailure { tvInfo.text = it.javaClass.simpleName }
            }
        }
        sessionControls += control(getString(R.string.camera_zoom_confirm), getString(R.string.camera_zoom_confirm)) {
            if (!canControlSession()) return@control
            val ratio = currentZoom
            val token = callbackGeneration
            val result = beginQueryResult(getString(R.string.nv21_zoom_confirmation))
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val actual = withContext(cameraDispatcher) { nv21Helper.nv21ZoomSession.setZoomAndAwait(ratio, 2000) }
                    val confirmed = cameraZoomAwaitResult(ratio, actual)
                    if (token == callbackGeneration) {
                        zoomCommandText = confirmed
                        updateDisplay()
                    }
                    result(confirmed)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    result(getString(R.string.nv21_query_failed, error.message ?: error.javaClass.simpleName))
                }
            }
        }.apply { tag = "nv21_await_zoom" }
        sessionControls += control("跟随共享倍率", "Follow shared zoom") {
            if (!canControlSession()) return@control
            val token = callbackGeneration
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching { withContext(cameraDispatcher) { nv21Helper.nv21ZoomSession.followShared() } }
                    .onSuccess {
                        L.i("CameraDemo", "NV21 followShared result=$it")
                        if (token == callbackGeneration) {
                            zoomCommandText = cameraZoomResult("NV21 control", it)
                            zoomStateText = cameraApiState(nv21Helper.nv21ZoomSession.state)
                            updateDisplay()
                        }
                    }
                    .onFailure { tvInfo.text = it.javaClass.simpleName }
            }
        }
        control("查询能力和实际状态", "Query capabilities and actual state") {
            val result = beginQueryResult(getString(R.string.nv21_query_title))
            val mode = activeMode
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val (capabilities, state) = withContext(cameraDispatcher) {
                        val service = checkNotNull(GlassSdk.getGlassMediaService()) {
                            getString(R.string.nv21_query_service_unavailable)
                        }
                        val capabilities = service.mediaCapabilities
                        val state = if (mode?.hasZoomSession == true) nv21Helper.nv21ZoomSession.refreshState() else null
                        capabilities to state
                    }
                    result(nv21QueryText(mode, capabilities, state))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    result(getString(R.string.nv21_query_failed, error.message ?: error.javaClass.simpleName))
                }
            }
        }.tag = "nv21_query_state"
        configControls = configControls.take(5) + more + extra + configControls.takeLast(2)
        configControls.forEach { it.isFocusable = false }

        setupConfigUI()
        btnShowConfig.isFocusable = false
    }

    override fun onResume() {
        super.onResume()
        if (::nv21SurfaceView.isInitialized) {
            nv21SurfaceView.onResume()
            startNv21Export()
        }
    }

    override fun onPause() {
        dismissQueryResult()
        releaseNv21Resources("onPause")
        if (::nv21SurfaceView.isInitialized) {
            nv21SurfaceView.onPause()
        }
        super.onPause()
    }

    override fun onDestroyView() {
        dismissQueryResult()
        releaseNv21Resources("onDestroyView")
        if (::nv21SurfaceView.isInitialized) {
            nv21SurfaceView.releasePreview()
        }
        super.onDestroyView()
    }

    private fun releaseNv21Resources(reason: String) {
        callbackGeneration++
        currentFps = null; currentEisEnabled = null
        activeMode = null
        zoomStateText = "State not available"
        if (::tvActualZoom.isInitialized) tvActualZoom.text = cameraActualZoomText(null)
        configSwitchJob?.cancel()
        configSwitchJob = null
        Log.d(TAG, "$reason: releasing NV21 export")
        nv21Helper.nv21ZoomSession.listener = null
        cameraExecutor.execute {
            runCatching { nv21Helper.releaseNv21Export() }
                .onSuccess { L.i("CameraDemo", "NV21 cleanup=$reason") }
                .onFailure { L.w("CameraDemo", "NV21 cleanup error=${it.javaClass.simpleName}") }
        }
    }

    /**
     * 设置配置 UI
     * Set up the configuration UI.
     */
    private fun setupConfigUI() {
        // 分辨率 Spinner - 使用动态获取的分辨率列表
        // Resolution Spinner: use the dynamically obtained resolution list.
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
        seekbarZoom.min = 0
        seekbarZoom.max = zoomOptions.lastIndex
        seekbarZoom.progress = zoomOptions.indexOf(currentZoom).coerceAtLeast(0)
        tvZoomValue.text = demoString(R.string.camera_zoom_value, currentZoom.toString())
        seekbarZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentZoom = zoomOptions[progress.coerceIn(0, zoomOptions.lastIndex)]
                tvZoomValue.text = demoString(R.string.camera_zoom_value, currentZoom.toString())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnApiMode.setOnClickListener {
            selectedMode = Nv21ExportMode.entries[(selectedMode.ordinal + 1) % Nv21ExportMode.entries.size]
            // V1 cannot represent fractional zoom. Explicitly reset it; never round it silently.
            val zoom = currentZoom.takeIf { it in zoomOptions } ?: 1f
            seekbarZoom.max = zoomOptions.lastIndex
            seekbarZoom.progress = zoomOptions.indexOf(zoom)
            currentZoom = zoom
            tvZoomValue.text = demoString(R.string.camera_zoom_value, currentZoom.toString())
            updateModeUi()
            L.i("CameraDemo", "NV21 selected api=$selectedMode zoom=$currentZoom; apply to start")
        }
        updateModeUi()

        // EIS Switch
        switchEis.isChecked = currentEis
        switchEis.setOnCheckedChangeListener { _, isChecked ->
            currentEis = isChecked
        }

        // 显示配置按钮
        // Show-configuration button.
        btnShowConfig.setOnClickListener {
            setConfigPanelVisible(true)
        }

        // 隐藏配置按钮
        // Hide-configuration button.
        btnToggleConfig.setOnClickListener {
            setConfigPanelVisible(false)
        }

        // 应用配置按钮
        // Apply-configuration button.
        btnApply.setOnClickListener {
            applyConfig()
        }
    }

    /**
     * 应用用户配置
     * Apply the user's configuration.
     */
    private fun applyConfig() {
        if (configSwitchJob?.isActive == true) return
        val mode = selectedMode
        val resolutionOption = resolutionOptions[currentResolutionIndex].second
        val width = resolutionOption.first
        val height = resolutionOption.second
        val isPortrait = resolutionOption.third
        val fps = FPS_OPTIONS[currentFpsIndex]

        Log.d(TAG, "Applying config: ${width}x${height}@${fps}fps, zoom=$currentZoom, EIS=$currentEis, isPortrait=$isPortrait")

        val newConfig = CameraShareConfigV2(
            previewWidth = width,
            previewHeight = height,
            previewTargetFps = fps,
            useAsyncCallback = false,
            enableVideoStabilization = currentEis,
            zoom = CameraZoomConfig.explicit(currentZoom),
        )

        Log.d(TAG, "applyConfig: ${width}x${height}@${fps}fps, zoom=$currentZoom")
        // Avoid immediate stop/start reusing the old producer on current firmware.
        // Only release our consumer; never force-close other camera consumers.
        callbackGeneration++
        val token = callbackGeneration
        currentFps = null; currentEisEnabled = null
        activeMode = null
        zoomStateText = "State not available"
        val firstFrame = CompletableDeferred<Unit>()
        configSwitchJob = viewLifecycleOwner.lifecycleScope.launch {
            btnApply.isEnabled = false
            try {
                withContext(cameraDispatcher) { nv21Helper.releaseNv21Export() }
                nv21SurfaceView.releasePreview()
                nv21SurfaceView.requestRender()
                currentWidth = 0
                currentHeight = 0
                frameCount = 0
                lastFpsTime = 0
                lastRenderedFrameCount = 0
                lastDroppedFrameCount = 0
                lastFrameTimestamp = 0
                frameIntervalSum = 0
                frameIntervalCount = 0
                Log.d(TAG, "Config switch: released own consumer; waiting for idle cleanup")
                delay(1500)
                withContext(cameraDispatcher) {
                    if (token == callbackGeneration) {
                        launchNv21Export(mode, token, isPortrait, newConfig, firstFrame)
                    }
                }
                withTimeout(10_000) { firstFrame.await() }
                previewPreferences.edit()
                    .putInt("width", width).putInt("height", height).putInt("fps", fps)
                    .putFloat("zoom_ratio", newConfig.zoom.zoomRatio).putBoolean("eis", newConfig.enableVideoStabilization)
                    .putString("api_mode", mode.name)
                    .apply()
                currentConfigText = demoString(R.string.demo_requested_x_fps_eis_zoom_matching_frames_received, width, height, fps, if (newConfig.enableVideoStabilization) "ON" else "OFF", newConfig.zoom.zoomRatio)
                updateDisplay()
                Log.d(TAG, "Config switch received frame: api=$mode ${width}x${height} frameEvidence=${mode.hasFrameEvidence}")
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                currentConfigText = demoString(R.string.demo_switch_unconfirmed_no_x_frames_within_10_seconds, width, height, currentWidth, currentHeight)
                updateDisplay()
                Log.w(TAG, currentConfigText)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Config switch failed", e)
                tvInfo.text = demoString(R.string.demo_configuration_switch_failed, e.message)
            } finally {
                if (token == callbackGeneration && view != null) {
                    btnApply.isEnabled = true
                    configSwitchJob = null
                    updateModeUi()
                }
            }
        }

        // 更新显示
        // Update the display.
        val orientationText = if (isPortrait) demoString(R.string.demo_portrait) else demoString(R.string.demo_landscape)
        currentConfigText = demoString(R.string.demo_switching_x_fps_waiting_for_frames, width, height, fps, orientationText)
        updateDisplay()
        // The old session may not have closed on the worker yet; do not retain its confirmation.
        tvActualZoom.text = cameraActualZoomText(null)

        // 隐藏配置面板
        // Hide the configuration panel.
        setConfigPanelVisible(false)
    }

    /**
     * 配置面板显示时移除外层按钮，避免遮挡 Spinner 的下拉菜单。
     * Hide the outer button while the configuration panel is visible so it does not obscure the Spinner dropdown.
     */
    private fun setConfigPanelVisible(visible: Boolean) {
        configPanel.visibility = if (visible) View.VISIBLE else View.GONE
        btnShowConfig.visibility = if (visible) View.GONE else View.VISIBLE
        tvInfo.visibility = if (visible) View.GONE else View.VISIBLE
        if (visible) {
            selectedConfigIndex = 0
            focusSelectedConfig()
        } else {
            configControls.forEach { it.isSelected = false }
        }
    }

    override fun handleGlassKeyEvent(keyEvent: Int): Boolean {
        if (queryOverlay != null) {
            when (keyEvent) {
                GlassKeyEvent.KEYCODE_FRONT -> queryScroll?.let { it.scrollBy(0, it.height / 2) }
                GlassKeyEvent.KEYCODE_BEHIND -> queryScroll?.let { it.scrollBy(0, -it.height / 2) }
                GlassKeyEvent.KEYCODE_CLICK, GlassKeyEvent.KEYCODE_BACK, GlassKeyEvent.KEYCODE_DOUBLE_CLICK -> dismissQueryResult()
                else -> return false
            }
            return true
        }
        if (configPanel.visibility != View.VISIBLE) {
            if (keyEvent == GlassKeyEvent.KEYCODE_CLICK && btnShowConfig.visibility == View.VISIBLE) {
                btnShowConfig.performClick()
                return true
            }
            return false
        }
        when (keyEvent) {
            GlassKeyEvent.KEYCODE_FRONT -> moveConfigSelection(1)
            GlassKeyEvent.KEYCODE_BEHIND -> moveConfigSelection(-1)
            GlassKeyEvent.KEYCODE_CLICK -> activateSelectedConfig()
            else -> return false
        }
        return true
    }

    // 查询结果独立显示，避免被配置面板隐藏或被实时帧率、倍率回调覆盖。
    // Keep query results separate from preview text hidden by settings and updated by live callbacks.
    private fun beginQueryResult(title: String): (String) -> Unit {
        dismissQueryResult()
        val queryToken = queryGeneration
        val outputToken = callbackGeneration
        val context = requireContext()
        val inset = (12 * resources.displayMetrics.density).toInt()
        val resultText = TextView(context).apply {
            tag = "nv21_query_result"
            setTextColor(Color.WHITE)
            textSize = 14f
            text = getString(R.string.nv21_query_loading)
            setPadding(0, inset, 0, inset)
        }
        val scroll = ScrollView(context).apply { addView(resultText) }
        val overlay = LinearLayout(context).apply {
            tag = "nv21_query_overlay"
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(inset, inset, inset, inset)
            isClickable = true
            isFocusableInTouchMode = true
            addView(TextView(context).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 17f
            }, LinearLayout.LayoutParams(-1, -2))
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(Button(context).apply {
                setText(R.string.nv21_query_back)
                textSize = 13f
                isAllCaps = false
                setOnClickListener { dismissQueryResult() }
            }, LinearLayout.LayoutParams(-1, -2))
            setOnKeyListener { _, code, event ->
                if (code == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) dismissQueryResult()
                    true
                } else false
            }
        }
        queryOverlay = overlay
        queryScroll = scroll
        (requireView() as FrameLayout).addView(overlay, FrameLayout.LayoutParams(-1, -1))
        overlay.requestFocus()
        return { text ->
            // 关闭结果页或离开页面后忽略迟到结果；输出切换时不展示旧输出的状态。
            // Ignore late replies after dismissal or navigation; never show a previous output's state.
            if (queryToken == queryGeneration && queryOverlay === overlay) {
                resultText.text = if (outputToken == callbackGeneration) text else getString(R.string.nv21_query_output_changed)
                scroll.scrollTo(0, 0)
            }
        }
    }

    private fun dismissQueryResult() {
        queryGeneration++
        queryOverlay?.let { overlay ->
            overlay.clearFocus()
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }
        queryOverlay = null
        queryScroll = null
    }

    private fun nv21QueryText(mode: Nv21ExportMode?, capabilities: Int, state: CameraZoomState?): String = buildString {
        val unknown = getString(R.string.nv21_query_unknown)
        appendLine(getString(R.string.nv21_query_output,
            mode?.let { getString(it.label) } ?: getString(R.string.nv21_query_inactive),
            getString(if (capabilities and CameraZoomSession.CAPABILITY_SHARE != 0) R.string.nv21_query_supported else R.string.nv21_query_unsupported),
            if (mode != null && currentWidth > 0 && currentHeight > 0) "$currentWidth×$currentHeight" else unknown,
            currentFps?.toString() ?: unknown,
            currentEisEnabled?.let { getString(if (it) R.string.nv21_query_enabled else R.string.nv21_query_disabled) } ?: unknown))
        appendLine()
        if (mode?.hasZoomSession == true) {
            appendLine(cameraActualZoomText(state))
            if (state != null) {
                appendLine(getString(R.string.nv21_query_zoom_state, state.requestedZoom.toString(), state.frameGeneration,
                    getString(if (state.limitedByCapability) R.string.nv21_query_yes else R.string.nv21_query_no)))
            }
        } else if (mode != null) {
            appendLine(getString(R.string.nv21_zoom_v1))
        }
        if (mode == Nv21ExportMode.OPTIONS_ARRAY) appendLine(getString(R.string.nv21_frame_unconfirmed))
    }

    private fun moveConfigSelection(offset: Int) {
        val available = configControls.indices.filter { configControls[it].isShown && configControls[it].isEnabled }
        if (available.isEmpty()) return
        val current = available.indexOf(selectedConfigIndex).coerceAtLeast(0)
        selectedConfigIndex = available[(current + offset + available.size) % available.size]
        focusSelectedConfig()
    }

    private fun focusSelectedConfig() {
        updateConfigHighlight()
        val selected = configControls[selectedConfigIndex]
        selected.post {
            if (configPanel.visibility == View.VISIBLE) {
                val inset = (12 * resources.displayMetrics.density).toInt()
                selected.requestRectangleOnScreen(
                    Rect(0, -inset, selected.width, selected.height + inset), true,
                )
            }
        }
    }

    private fun updateConfigHighlight() {
        configControls.forEachIndexed { index, view ->
            view.isSelected = index == selectedConfigIndex
        }
    }

    /**
     * 单击直接轮换配置值，避免打开 Spinner 后眼镜腿方向键被弹窗消费。
     * Cycle configuration values directly on a click so opening the Spinner does not let its popup consume the temple direction keys.
     */
    private fun activateSelectedConfig() {
        if (!configControls[selectedConfigIndex].isShown || !configControls[selectedConfigIndex].isEnabled) return
        when (configControls[selectedConfigIndex].id) {
            R.id.row_resolution -> spinnerResolution.setSelection(
                (spinnerResolution.selectedItemPosition + 1) % spinnerResolution.count,
            )
            R.id.row_fps -> spinnerFps.setSelection(
                (spinnerFps.selectedItemPosition + 1) % spinnerFps.count,
            )
            R.id.row_zoom -> {
                seekbarZoom.progress = (seekbarZoom.progress + 1) % zoomOptions.size
                tvZoomValue.text = demoString(R.string.camera_zoom_value, currentZoom.toString())
            }
            R.id.row_eis -> switchEis.isChecked = !switchEis.isChecked
            else -> configControls[selectedConfigIndex].performClick()
        }
    }

    /** Shared lifecycle and preview sink; each mode retains its own evidence rules. */
    private inner class Nv21DemoCallback(
        private val mode: Nv21ExportMode,
        private val generation: Int,
        private val isPortrait: Boolean,
        private val expected: CameraShareConfigV2,
        private val firstFrame: CompletableDeferred<Unit>?,
    ) : CameraShareHelper.Nv21BufferCallback, CameraShareHelper.Nv21Callback {
        private var frameStorage = ByteArray(0)
        private var loggedFirstFrame = false
        override fun onCameraOpened(width: Int, height: Int) {
            if (generation != callbackGeneration) return
            Log.d(TAG, "NV21 camera opened: ${width}x${height}, isPortrait=$isPortrait")
            currentWidth = width
            currentHeight = height
            if (currentConfigText.isEmpty()) {
                val orientationText = if (isPortrait) demoString(R.string.demo_portrait) else demoString(R.string.demo_landscape)
                currentConfigText = demoString(R.string.demo_configuration_x_eis_zoom, width, height, orientationText, if (currentEis) "ON" else "OFF", currentZoom)
            }
            activity?.runOnUiThread {
                if (generation == callbackGeneration && view != null) updateDisplay()
            }
        }

        override fun onNv21Frame(buffer: ByteBuffer, width: Int, height: Int, timestamp: Long) {
            if (mode != Nv21ExportMode.CONFIG_BUFFER || generation != callbackGeneration) return
            // V1 has no configuration generation or Options session. Validate size only.
            copyBorrowedFrame(buffer, width, height, timestamp)
        }

        fun configured(): CameraShareHelper.ConfiguredNv21BufferCallback =
            object : CameraShareHelper.ConfiguredNv21BufferCallback,
                CameraShareHelper.Nv21BufferCallback by this {
                override fun onConfiguredNv21Frame(buffer: ByteBuffer, width: Int, height: Int, timestamp: Long,
                    sensorTimestampNs: Long, configurationGeneration: Long) {
                    if (generation != callbackGeneration) return
                    synchronized(frameEvidenceLock) {
                        val state = nv21Helper.nv21ZoomSession.state ?: return
                        if (!CameraFrameEvidence.accepts(state.ready, state.appliedZoom, configurationGeneration,
                                state.frameGeneration, width, height, buffer.remaining())) return
                        copyBorrowedFrame(buffer, width, height, timestamp)
                    }
                }
            }

        private fun copyBorrowedFrame(buffer: ByteBuffer, width: Int, height: Int, timestamp: Long) {
            if (!validNv21Size(width, height, buffer.remaining())) return
            val bytes = (width.toLong() * height * 3 / 2).toInt()
            if (frameStorage.size != bytes) frameStorage = ByteArray(bytes)
            val originalPosition = buffer.position()
            try {
                buffer.get(frameStorage, 0, bytes)
            } finally {
                buffer.position(originalPosition)
            }
            // Consume synchronously. Never cache this borrowed shared-memory view or pass it
            // to another thread. The reusable copy here is needed by the Demo's byte[] renderer.
            consumeNv21Frame(frameStorage, width, height, timestamp)
        }

        override fun onNv21Frame(nv21: ByteArray, width: Int, height: Int, timestamp: Long) {
            if (mode != Nv21ExportMode.OPTIONS_ARRAY || generation != callbackGeneration) return
            if (!validNv21Size(width, height, nv21.size)) return
            // Example for an existing algorithm accepting only byte[]: consume it here,
            // synchronously. The SDK reuses this array; do not queue or retain the reference.
            consumeNv21Frame(nv21, width, height, timestamp)
        }

        private fun consumeNv21Frame(nv21: ByteArray, width: Int, height: Int, timestamp: Long) {
            if (generation != callbackGeneration) return
            currentWidth = width
            currentHeight = height
            if (!loggedFirstFrame) {
                loggedFirstFrame = true
                L.i("CameraDemo", "NV21 first frame api=$mode method=${mode.method} size=${width}x${height} " +
                    "timestampMs=$timestamp frameEvidence=${if (mode.hasFrameEvidence) "generation-matched" else "unavailable"}")
            }
            if (width == expected.previewWidth && height == expected.previewHeight) {
                firstFrame?.complete(Unit)
            }
            // 记录帧间隔，便于观察采集帧率。
            // Record frame intervals to observe the capture frame rate.
            val now = System.currentTimeMillis()
            if (lastFrameTimestamp > 0) {
                val interval = now - lastFrameTimestamp
                frameIntervalSum += interval
                frameIntervalCount++
                if (frameIntervalCount % 30 == 0) {
                    val avgInterval = frameIntervalSum / frameIntervalCount
                    Log.d(TAG, "Frame interval stats: avg=${avgInterval}ms, target=${1000/FPS_OPTIONS[currentFpsIndex]}ms (${FPS_OPTIONS[currentFpsIndex]}fps), actual fps=${if (avgInterval > 0) 1000f/avgInterval else 0f}")
                    frameIntervalSum = 0
                    frameIntervalCount = 0
                }
            }
            lastFrameTimestamp = now

            // 回调帧率与预览渲染解耦；预览较慢时只保留最新帧，不阻塞后续回调。
            // Decouple callback frame rate from preview rendering; keep only the latest frame when preview rendering is slower, without blocking later callbacks.
            updateFps(width, height, generation)
            // The renderer copies into a bounded mailbox before returning; it never retains
            // either our reused array or the SDK-owned array across subsequent callbacks.
            nv21SurfaceView.setPreviewData(nv21, width, height)
        }

        override fun onCameraClosed() {
            if (generation != callbackGeneration) return
            Log.d(TAG, "NV21 camera closed")
        }

        override fun onError(code: Int, msg: String) {
            if (generation != callbackGeneration) return
            L.w("CameraDemo", "NV21 export error=$code")
            firstFrame?.completeExceptionally(IllegalStateException("$code: $msg"))
            Log.e(TAG, "NV21 error: code=$code, msg=$msg")
            activity?.runOnUiThread {
                if (generation == callbackGeneration && view != null) tvInfo.text = demoString(R.string.demo_error, code, msg)
            }
        }

        override fun onNv21ExportResolutionChanged(width: Int, height: Int, appliedPreviewFps: Int) {
            if (generation != callbackGeneration) return
            Log.d(TAG, "onNv21ExportResolutionChanged: ${width}x${height}, fps=$appliedPreviewFps")
            currentWidth = width
            currentHeight = height
            currentFps = appliedPreviewFps
            activity?.runOnUiThread {
                if (generation == callbackGeneration && view != null) updateDisplay()
            }
        }

        override fun onNv21ExportRuntimeParamsChanged(appliedPreviewFps: Int, videoStabilizationEnabled: Boolean) {
            if (generation != callbackGeneration) return
            currentFps = appliedPreviewFps
            currentEisEnabled = videoStabilizationEnabled
            L.i("CameraDemo", "NV21 actual fps=$appliedPreviewFps EIS=$videoStabilizationEnabled; requested EIS=$currentEis")
            activity?.runOnUiThread {
                if (generation == callbackGeneration && view != null) updateDisplay()
            }
        }

        override fun onZoomLevelChanged(zoomLevel: Int) {
            if (generation != callbackGeneration) return
            Log.d(TAG, "Zoom level changed: $zoomLevel")
        }
    }

    private fun validNv21Size(width: Int, height: Int, available: Int): Boolean =
        width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0 &&
            width.toLong() * height <= available.toLong() * 2 / 3

    private fun launchNv21Export(mode: Nv21ExportMode, token: Int, isPortrait: Boolean,
        config: CameraShareConfigV2, firstFrame: CompletableDeferred<Unit>? = null) {
        check(GlassSdk.isReady() && GlassSdk.getGlassMediaService() != null) { "Camera service is not connected" }
        activeMode = mode
        zoomCommandText = ""
        zoomStateText = ""
        confirmedFrameGeneration = 0L
        if (mode.hasZoomSession) observeZoom(token) else nv21Helper.nv21ZoomSession.listener = null
        val callback = Nv21DemoCallback(mode, token, isPortrait, config, firstFrame)
        L.i("CameraDemo", "NV21 start api=$mode method=${mode.method} " +
            "${config.previewWidth}x${config.previewHeight}@${config.previewTargetFps} " +
            "zoom=${config.zoom.zoomRatio} EIS=${config.enableVideoStabilization}")
        // All three are public, ordinary-app export APIs, with enableMix=false (camera only).
        when (mode) {
            Nv21ExportMode.OPTIONS_BUFFER -> nv21Helper.initNv21BufferExportWithOptions(false, config, callback.configured())
            Nv21ExportMode.OPTIONS_ARRAY -> nv21Helper.initNv21ExportWithOptions(false, config, callback)
            Nv21ExportMode.CONFIG_BUFFER -> nv21Helper.initNv21BufferExportWithConfig(false, nv21IntegerConfig(config), callback)
        }
        updateDisplay()
    }

    private fun canControlSession(): Boolean {
        if (configSwitchJob?.isActive == true || activeMode != selectedMode || !selectedMode.hasZoomSession) {
            val reason = if (!selectedMode.hasZoomSession) getString(R.string.nv21_zoom_v1)
                else getString(R.string.nv21_apply_mode_first)
            L.i("CameraDemo", reason)
            beginQueryResult(getString(R.string.nv21_zoom_confirmation))(reason)
            return false
        }
        return true
    }

    private fun updateModeUi() {
        btnApiMode.setText(selectedMode.label)
        sessionControls.forEach {
            it.isEnabled = selectedMode.hasZoomSession
            it.alpha = if (selectedMode.hasZoomSession) 1f else 0.45f
        }
    }

    private fun startNv21Export() {
        if (!GlassSdk.isReady()) {
            Log.e(TAG, "GlassSdk not ready")
            return
        }

        if (configSwitchJob?.isActive == true) return
        // Restore the last successfully applied configuration and data API.
        val resolutionOption = resolutionOptions[currentResolutionIndex.coerceIn(0, resolutionOptions.size - 1)].second
        val initialConfig = CameraShareConfigV2(
            previewWidth = resolutionOption.first,
            previewHeight = resolutionOption.second,
            previewTargetFps = FPS_OPTIONS[currentFpsIndex],
            useAsyncCallback = false,
            enableVideoStabilization = currentEis,
            zoom = CameraZoomConfig.explicit(currentZoom),
        )

        val isPortrait = resolutionOption.third
        val mode = selectedMode

        val token = callbackGeneration
        currentFps = null; currentEisEnabled = null
        val orientationText = if (isPortrait) demoString(R.string.demo_portrait) else demoString(R.string.demo_landscape)
        currentConfigText = demoString(R.string.demo_configuration_x_fps_eis_zoom, initialConfig.previewWidth, initialConfig.previewHeight, initialConfig.previewTargetFps, orientationText, if (currentEis) "ON" else "OFF", initialConfig.zoom.zoomRatio)
        val firstFrame = CompletableDeferred<Unit>()
        configSwitchJob = viewLifecycleOwner.lifecycleScope.launch {
            btnApply.isEnabled = false
            try {
                withContext(cameraDispatcher) {
                    if (token == callbackGeneration) {
                        launchNv21Export(mode, token, isPortrait, initialConfig, firstFrame)
                    }
                }
                // V1 Buffer does not reliably report start errors on every SDK/service version.
                // Require an actual frame on initial entry as well as on configuration changes.
                withTimeout(10_000) { firstFrame.await() }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                currentConfigText = demoString(R.string.demo_switch_unconfirmed_no_x_frames_within_10_seconds,
                    initialConfig.previewWidth, initialConfig.previewHeight, currentWidth, currentHeight)
                L.w("CameraDemo", "NV21 start timeout api=$mode; $currentConfigText")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                currentConfigText = demoString(R.string.demo_configuration_switch_failed, e.message)
                L.w("CameraDemo", "NV21 start api=$mode error=${e.javaClass.simpleName}")
            } finally {
                if (token == callbackGeneration && view != null) {
                    btnApply.isEnabled = true
                    configSwitchJob = null
                    updateDisplay()
                }
            }
        }
    }

    private fun updateFps(width: Int, height: Int, generation: Int) {
        frameCount++
        val now = System.currentTimeMillis()
        if (lastFpsTime == 0L) lastFpsTime = now
        val elapsed = now - lastFpsTime
        if (elapsed >= 1000) {
            val callbackFps = frameCount * 1000f / elapsed
            val renderedTotal = nv21SurfaceView.renderedFrameCount
            val droppedTotal = nv21SurfaceView.droppedFrameCount
            val renderedFps = (renderedTotal - lastRenderedFrameCount) * 1000f / elapsed
            val droppedFrames = droppedTotal - lastDroppedFrameCount
            activity?.runOnUiThread {
                if (generation == callbackGeneration && view != null) updateDisplay(callbackFps, renderedFps, droppedFrames)
            }
            L.i("CameraDemo", "NV21 api=$activeMode frames=${frameCount} fps=$callbackFps rendered=$renderedFps dropped=$droppedFrames size=${width}x${height}")
            frameCount = 0
            lastFpsTime = now
            lastRenderedFrameCount = renderedTotal
            lastDroppedFrameCount = droppedTotal
        }
    }

    private fun updateDisplay(
        callbackFps: Float? = null,
        renderedFps: Float? = null,
        droppedFrames: Long = 0,
    ) {
        val token = callbackGeneration
        val resolutionText = demoString(R.string.demo_connected_x, currentWidth, currentHeight)
        val fpsText = callbackFps?.let {
            demoString(R.string.nv21_measured_rates).format(
                it,
                renderedFps ?: 0f,
                droppedFrames,
            )
        } ?: ""
        activity?.runOnUiThread {
            if (token != callbackGeneration || view == null) return@runOnUiThread
            updateModeUi()
            tvActualZoom.text = if (activeMode == Nv21ExportMode.CONFIG_BUFFER) {
                demoString(R.string.nv21_zoom_v1)
            } else cameraActualZoomText(if (activeMode != null) nv21Helper.nv21ZoomSession.state else null)
            val apiText = activeMode?.let { demoString(it.label) }.orEmpty()
            // 请求参数和实测吞吐率分别展示；服务上报参数保留在状态查询中。
            // Separate requested settings from measured throughput; keep service-reported parameters in the state query.
            tvInfo.text = listOf(
                apiText,
                resolutionText,
                getString(R.string.nv21_requested_config, currentConfigText),
                zoomStateText,
                zoomCommandText,
                fpsText,
            ).filter { it.isNotBlank() }.joinToString("\n")
        }
    }
    override fun onDestroy() { cameraExecutor.shutdown(); super.onDestroy() }

}
