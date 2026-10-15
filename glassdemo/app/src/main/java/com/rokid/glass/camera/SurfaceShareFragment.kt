package com.rokid.glass.camera

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rokid.security.glass3.sdk.base.data.media.TextWatermarkConfig
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.Button
import android.widget.TextView
import com.rokid.glesse.R
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.camera.CameraZoomSession
import com.rokid.security.glass3.open.sdk.camera.CameraShareHelper
import com.rokid.security.glass3.sdk.base.data.media.CameraSurfaceContract
import com.rokid.security.glass3.sdk.base.data.media.CameraZoomConfig
import com.rokid.security.glass3.sdk.base.data.media.CameraZoomState
import com.rokid.security.glass3.open.sdk.uitls.log.L
import com.rokid.security.system.server.media.IMediaServer
import com.rokid.security.system.server.media.SurfaceShareContractProbe
import com.rokid.security.system.server.media.callback.ICameraSurfaceCallback
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Surface 适用于显示、GPU 合成或接受 Surface 的处理链路，无需在客户端读取 NV21/I420 像素。
 * 本页直接通过 AIDL 提交 TextureView 的 Surface，避免为了显示而把 CPU YUV 数据重新上传。
 * 若识别算法要求 NV21，使用 NV21 页；若下游要 I420 三平面并异步持帧，使用 I420 页。
 * 先协商 Surface 缓冲尺寸再启动；updateTexImage 后的原始相机时间戳用于确认实际倍率。
 * 文字水印属于本 Surface 输出，不能推断其他 NV21/I420 输出有同样水印。
 * 本页只演示开关与固定示例文字；接入方可配置 TextWatermarkItem.text，或使用原 callback Binder
 * 调用 updateCameraShareWatermarkText 更新已有文本项，无需在 Demo 再设置独立修改按钮。
 */
class SurfaceShareFragment : CameraControlsFragment(), TextureView.SurfaceTextureListener {
    companion object { fun newInstance() = SurfaceShareFragment() }
    private lateinit var preview: CameraTextureView
    private var requestedWidth = 1280
    private var requestedHeight = 720
    private var requestedFps = 24
    private var requestedZoom = 1f
    private var requestedEis = false
    private var enableWatermark = false
    private lateinit var watermarkConfig: TextWatermarkConfig
    private var producerWatermark: TextWatermarkConfig? = null
    @Volatile private var liveRequestedZoom = 1f
    @Volatile private var requestedFollowShared = false
    @Volatile private var followingShared = false
    private data class PreviewRequest(val camera: CameraApiRequest, val watermark: TextWatermarkConfig?, val followShared: Boolean = false)
    // Keep applied settings separate from edits that have not been applied yet.
    private var appliedRequest = PreviewRequest(CameraApiRequest(requestedWidth, requestedHeight, requestedFps, requestedZoom, requestedEis), null)
    private var previewWanted = true
    private var requestedTexture: SurfaceTexture? = null
    private var startPending = false
    private val startRequest = AtomicLong()
    private val zoomCommand = AtomicLong()
    @Volatile private var previewStarted = false
    override val previewRequiresStart = true
    override val previewStartsAutomatically get() = previewWanted
    private val zooms = floatArrayOf(1f, 1.25f, 1.575f, 2f, 4f, 8f)
    private var resolutions = listOf(1280 to 720, 1920 to 1080, 1080 to 1920, 640 to 480)
    private lateinit var resolutionButton: Button
    private lateinit var zoomButton: Button
    private lateinit var actualZoom: TextView
    private lateinit var fpsButton: Button
    private lateinit var eisButton: Button
    private lateinit var watermarkButton: Button
    private lateinit var applyButton: Button
    private lateinit var liveZoomButton: Button
    @Volatile private var foreground = false
    @Volatile private var availableTexture: SurfaceTexture? = null
    @Volatile private var zoomSession: CameraZoomSession? = null
    private var producerService: IMediaServer? = null
    private var producerCallback: ICameraSurfaceCallback? = null
    private var producerSurface: Surface? = null
    private var producerTexture: SurfaceTexture? = null
    private val generation = AtomicLong()
    private val observing = AtomicBoolean()
    private var lastObservedAt = 0L
    private var producerStoppedAt = 0L
    private var displayWidth = 640
    private var displayHeight = 480
    private var measuredFrames = 0L
    private var measuredSince = 0L
    @Volatile private var appliedEis: Boolean? = null
    @Volatile private var appliedFps: Int? = null
    @Volatile private var producerRequestedEis = false

    private fun runtimeState() = "EIS requested=$producerRequestedEis; actual=${appliedEis ?: "unknown"}; actual FPS=${appliedFps ?: "unknown"}"

    private fun eisButtonText() = label("电子防抖（EIS）：", "Stabilization (EIS): ") +
        if (requestedEis) label("开启", "On") else label("关闭", "Off")

    private fun watermarkButtonText() = getString(if (enableWatermark)
        R.string.watermark_summary_on else R.string.watermark_summary_off)

    private var zoomDisplay = ZoomDisplayStatus()
    private var displayedSession: CameraZoomSession? = null
    private var zoomDisplayTimeout: kotlinx.coroutines.Job? = null

    private fun updateActualZoom(): Unit = ui {
        zoomDisplayTimeout?.cancel()
        val session = zoomSession
        if (session !== displayedSession) {
            displayedSession = session
            zoomDisplay = ZoomDisplayStatus()
        }
        val state = session?.state
        zoomDisplay.update(state?.desiredRevision, state?.ready == true,
            state?.appliedZoom ?: Float.NaN, SystemClock.elapsedRealtime())
        actualZoom.text = actualZoomText(state)
        // 无帧时也能结束等待；仅安排一次界面超时，不轮询或修改相机。
        // End the UI wait even without frames; schedule a timeout, never poll or change the camera.
        if (foreground && previewStarted && session != null &&
            zoomDisplay.status == ZoomDisplayStatus.Status.LOADING) {
            zoomDisplayTimeout = viewLifecycleOwner.lifecycleScope.launch {
                kotlinx.coroutines.delay(zoomDisplay.remainingMs)
                if (foreground && session === zoomSession) {
                    updateActualZoom()
                    showLiveStatus()
                }
            }
        }
    }

    private fun actualZoomText(state: CameraZoomState?): String =
        if (state?.ready == true && state.appliedZoom.isFinite())
            getString(R.string.surface_zoom_actual, state.appliedZoom.toString())
        else getString(if (zoomDisplay.status == ZoomDisplayStatus.Status.UNAVAILABLE)
            R.string.surface_zoom_unavailable else R.string.surface_zoom_loading)

    private fun showLiveStatus() = ui {
        if (foreground && previewStarted) {
            val intent = if (followingShared) label("跟随共享倍率", "Following shared zoom")
                else label("本页请求倍率：${liveRequestedZoom}×", "Requested zoom: ${liveRequestedZoom}×")
            // Read the latest cache on the UI thread: older Binder callbacks must not paint
            // an outdated ready=false snapshot over the already confirmed state.
            val request = appliedRequest.camera
            showStatus(label("Surface 预览", "Surface preview") + "\n" +
                label("请求：${request.width}×${request.height} · ${request.fps} FPS",
                    "Requested: ${request.width}×${request.height} · ${request.fps} FPS") + "\n" +
                label("当前画面：${displayWidth}×${displayHeight}", "Current image: ${displayWidth}×${displayHeight}") + "\n" +
                intent + "\n" + actualZoomText(zoomSession?.state), showInSettings = false)
        }
    }

    override fun buildControls() {
        note(label("Surface 设置 · 修改后点击应用", "Surface settings · Apply changes below"))
        preview = CameraTextureView(requireContext()).apply { surfaceTextureListener = this@SurfaceShareFragment }
        previewHost.addView(preview, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        resolutionButton = action("${requestedWidth}×$requestedHeight") {
            val current = resolutions.indexOf(requestedWidth to requestedHeight)
            val next = resolutions[(current + 1) % resolutions.size]
            requestedWidth = next.first; requestedHeight = next.second
            resolutionButton.text = "${requestedWidth}×$requestedHeight"
        }
        fpsButton = action("FPS: $requestedFps") {
            val values = listOf(15, 20, 24, 30)
            requestedFps = values[(values.indexOf(requestedFps) + 1) % values.size]
            fpsButton.text = "FPS: $requestedFps"
        }
        settingRow(resolutionButton, fpsButton)
        zoomButton = action(getString(R.string.camera_zoom_selected, requestedZoom.toString())) {
            if (requestedFollowShared) {
                requestedFollowShared = false
                requestedZoom = zooms.first()
            } else if (requestedZoom == zooms.last()) {
                requestedFollowShared = true
            } else {
                requestedZoom = zooms[(zooms.indexOfFirst { it == requestedZoom } + 1) % zooms.size]
            }
            updateSelectedZoomLabel()
        }
        updateSelectedZoomLabel()
        actualZoom = TextView(requireContext()).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(4, 4, 4, 4)
            text = getString(R.string.surface_zoom_loading)
        }
        content.addView(actualZoom)
        settingRow(zoomButton, actualZoom)
        zoomButton.tag = "surface_config_zoom"
        eisButton = action(eisButtonText()) {
            requestedEis = !requestedEis
            eisButton.text = eisButtonText()
        }
        watermarkConfig = cameraApiWatermark(getString(R.string.watermark_default_text))
        watermarkButton = action(watermarkButtonText()) {
            enableWatermark = !enableWatermark
            watermarkButton.text = watermarkButtonText()
        }
        watermarkButton.tag = "surface_watermark"
        settingRow(eisButton, watermarkButton)
        note(getString(R.string.watermark_explanation))
        var expanded = false
        val extras = mutableListOf<View>()
        lateinit var more: Button
        more = action(getString(R.string.camera_zoom_controls)) {
            expanded = !expanded
            extras.forEach { it.visibility = if (expanded) View.VISIBLE else View.GONE }
            more.setText(if (expanded) R.string.camera_hide_options else R.string.camera_zoom_controls)
        }.apply { tag = "surface_more" }
        val query = action(label("能力与状态", "Capabilities / status")) { queryCameraState() }
            .apply { tag = "surface_query_capabilities" }
        settingRow(more, query)
        runningAction(label("跟随共享倍率", "Follow shared zoom")) { followLiveZoom() }.apply { tag = "surface_follow_zoom" }
        val firstExtra = content.childCount
        runningAction(getString(R.string.camera_zoom_confirm)) {
            if (requestedFollowShared) {
                followLiveZoom()
                return@runningAction
            }
            val session = zoomSession ?: return@runningAction
            val requestId = startRequest.get()
            val ratio = requestedZoom
            val command = zoomCommand.incrementAndGet()
            val result = beginQueryResult("Surface setZoomAndAwait")
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val actual = withContext(controlDispatcher) {
                        check(command == zoomCommand.get() && session === zoomSession && requestId == startRequest.get()) { "Zoom request superseded" }
                        // This call submits its own zoom request. Use it instead of setZoom when
                        // subsequent work needs confirmed appliedZoom; 2000 is a timeout, not a delay.
                        session.setZoomAndAwait(ratio, 2000)
                    }
                    if (session === zoomSession && requestId == startRequest.get() && command == zoomCommand.get()) {
                        // The request re-establishes this output's explicit intent, even if it times out.
                        liveRequestedZoom = ratio
                        requestedFollowShared = false
                        followingShared = false
                        appliedRequest = appliedRequest.copy(camera = appliedRequest.camera.copy(zoom = ratio), followShared = false)
                        updateLiveZoomLabel()
                        updateActualZoom()
                        showLiveStatus()
                        result(cameraZoomAwaitResult(ratio, actual))
                    }
                } catch (error: Exception) { result(error.message.orEmpty()) }
            }
        }.apply { tag = "surface_await_zoom" }
        for (index in firstExtra until content.childCount) extras.add(content.getChildAt(index))
        extras.forEach { it.visibility = View.GONE }
        applyButton = action(label("应用并预览", "Apply and preview")) {
            startPreview(PreviewRequest(
                CameraApiRequest(requestedWidth, requestedHeight, requestedFps, requestedZoom, requestedEis),
                if (enableWatermark) watermarkConfig else null,
                followShared = requestedFollowShared,
            ))
        }.apply { tag = "surface_apply" }

        previewAction(label("停止预览", "Stop preview")) {
            previewWanted = false
            invalidateStart()
            setPreviewLoading(false)
            setPreviewActionsEnabled(false)
            cleanup {
                stopProducer()
                showStatus(label("预览已停止，可修改参数后重新开始。", "Preview stopped. Adjust settings and start again."))
            }
        }.tag = "surface_stop"
        liveZoomButton = previewAction("") { changeLiveZoom() }.apply { tag = "surface_live_zoom" }
        updateLiveZoomLabel()
    }

    override fun finishSettings(returnButton: Button) { settingRow(applyButton, returnButton) }

    private fun startPreview(configuration: PreviewRequest, hideSettingsAfterStart: Boolean = true) {
        if (!foreground || startPending) return
        val texture = availableTexture
        if (texture == null) {
            beginQueryResult(label("无法开始预览", "Preview unavailable"))(label("预览窗口尚未就绪，请稍后重试。", "The preview surface is not ready. Please retry."))
            return
        }
        val requestId = startRequest.incrementAndGet()
        previewWanted = true
        requestedTexture = texture
        appliedRequest = configuration
        startPending = true
        setPreviewLoading(true)
        setPreviewActionsEnabled(false)
        applyButton.text = label("正在启动…", "Starting…")
        showStatus(label("正在启动 Surface 预览…", "Starting Surface preview…"))
        io {
            try {
                if (!canStart(texture, requestId)) return@io
                stopProducer()
                val token = startProducer(texture, configuration, requestId)
                if (token != null) ui {
                    if (alive(token) && startRequest.get() == requestId) {
                        liveRequestedZoom = configuration.camera.zoom
                        followingShared = configuration.followShared
                        updateLiveZoomLabel()
                        setPreviewActionsEnabled(true)
                        setPreviewLoading(false)
                        if (hideSettingsAfterStart) setPanelVisible(false)
                        showLiveStatus()
                    }
                }
            } catch (error: Exception) {
                L.w("CameraDemo", "Surface start failed: ${error.javaClass.simpleName}: ${error.message}")
                ui { if (foreground && startRequest.get() == requestId) {
                    previewWanted = false
                    requestedTexture = null
                    setPreviewLoading(false)
                    setPreviewActionsEnabled(false)
                    beginQueryResult(label("预览启动失败", "Preview failed to start"))(error.message ?: error.javaClass.simpleName)
                } }
            } finally {
                ui { if (startRequest.get() == requestId) {
                    startPending = false
                    applyButton.text = label("应用并预览", "Apply and preview")
                } }
            }
        }
    }

    private fun maybeStartPreview() {
        if (foreground && previewWanted && !startPending && availableTexture != null && requestedTexture !== availableTexture) {
            startPreview(appliedRequest, hideSettingsAfterStart = false)
        }
    }

    /** Main-thread invalidation; producer cleanup is serialized before the next start. */
    private fun invalidateStart() {
        startRequest.incrementAndGet()
        generation.incrementAndGet()
        requestedTexture = null
        startPending = false
        applyButton.text = label("应用并预览", "Apply and preview")
    }

    private fun updateSelectedZoomLabel() {
        zoomButton.text = if (requestedFollowShared) label("倍率：跟随共享", "Zoom: follow shared")
            else getString(R.string.camera_zoom_selected, requestedZoom.toString())
    }

    private fun updateLiveZoomLabel() {
        liveZoomButton.text = if (followingShared) label("切换倍率\n当前跟随共享", "Change zoom\nFollowing shared")
            else label("切换倍率\n已请求 ${liveRequestedZoom}×", "Change zoom\nRequested ${liveRequestedZoom}×")
    }

    private fun changeLiveZoom() {
        val session = zoomSession ?: return
        val requestId = startRequest.get()
        if (!followingShared && liveRequestedZoom == zooms.last()) {
            followLiveZoom()
            return
        }
        val ratio = if (followingShared) zooms.first()
            else zooms[(zooms.indexOfFirst { it == liveRequestedZoom } + 1) % zooms.size]
        val command = zoomCommand.incrementAndGet()
        io {
            if (!previewStarted || session !== zoomSession || command != zoomCommand.get()) return@io
            val code = session.setZoom(ratio)
            if (code != 0) showStatus(label("倍率未更新（返回码 $code），请等待预览就绪后重试。", "Zoom was not updated (code $code). Wait for preview readiness and retry."))
            if (code == 0) ui { if (foreground && startRequest.get() == requestId && session === zoomSession && command == zoomCommand.get()) {
                liveRequestedZoom = ratio
                requestedZoom = ratio
                requestedFollowShared = false
                followingShared = false
                appliedRequest = appliedRequest.copy(camera = appliedRequest.camera.copy(zoom = ratio), followShared = false)
                zoomButton.text = getString(R.string.camera_zoom_selected, ratio.toString())
                updateLiveZoomLabel()
            } }
            updateActualZoom()
            if (code == 0) showLiveStatus()
        }
    }

    private fun followLiveZoom() {
        val session = zoomSession ?: return
        val requestId = startRequest.get()
        val command = zoomCommand.incrementAndGet()
        io {
            if (!previewStarted || session !== zoomSession || command != zoomCommand.get()) return@io
            val code = session.followShared()
            if (code != 0) showStatus(label("跟随请求未接受（返回码 $code），请稍后重试。", "Follow request was not accepted (code $code). Please retry."))
            if (code == 0) ui { if (foreground && startRequest.get() == requestId && session === zoomSession && command == zoomCommand.get()) {
                requestedFollowShared = true
                updateSelectedZoomLabel()
                followingShared = true
                appliedRequest = appliedRequest.copy(followShared = true)
                updateLiveZoomLabel()
            } }
            updateActualZoom()
            if (code == 0) showLiveStatus()
        }
    }

    private fun queryCameraState() {
        val result = beginQueryResult(label("设备能力与预览状态", "Device capabilities and preview state"))
        io {
            try {
                val service = checkNotNull(GlassSdk.getGlassMediaService()) { label("媒体服务未连接", "Media service is not connected") }
                val capabilities = service.mediaCapabilities
                val session = zoomSession
                val state = session?.refreshState() ?: service.cameraZoomState
                val sizes = CameraShareHelper().getSupportedPreviewSizes().map { it.first to it.second }.distinct()
                fun supported(bit: Int) = if (capabilities and bit != 0) label("支持", "Supported") else label("不支持", "Unsupported")
                val running = previewStarted && session != null
                val text = buildString {
                    appendLine(label("本页预览：", "This preview: ") + if (running) label("已启动", "Started") else label("未开启", "Not started"))
                    appendLine(label("查询时机：", "Query timing: ") + if (running)
                        label("预览运行中，读取本页会话状态", "Preview running; reading this page's session")
                    else label("预览未开启，读取共享相机状态", "Preview stopped; reading shared camera state"))
                    appendLine(label("小数倍率共享：", "Fractional zoom sharing: ") + supported(CameraZoomSession.CAPABILITY_SHARE))
                    appendLine(label("小数倍率录像：", "Fractional zoom recording: ") + supported(CameraZoomSession.CAPABILITY_RECORD))
                    appendLine(label("小数倍率拍照：", "Fractional zoom photos: ") + supported(CameraZoomSession.CAPABILITY_PHOTO))
                    if (running) {
                        appendLine(label("本页倍率：", "This preview zoom: ") + if (followingShared) label("跟随共享", "Follow shared") else "${liveRequestedZoom}×")
                        appendLine(if (state?.ready == true && state.appliedZoom.isFinite())
                            getString(R.string.camera_zoom_actual, state.appliedZoom.toString())
                        else getString(R.string.camera_zoom_pending))
                        appendLine(runtimeState())
                    } else appendLine(label("尚无本页预览状态；以下为共享相机状态。", "No active preview on this page; shared camera state follows."))
                    appendLine(cameraApiState(state))
                    appendLine(label("支持的输出尺寸：", "Supported output sizes: ") + if (sizes.isEmpty()) label("未获取到", "Unavailable") else sizes.joinToString { "${it.first}×${it.second}" })
                }
                L.i("CameraDemo", "Surface query capabilities=$capabilities; $text")
                result(text)
                updateActualZoom()
                if (sizes.isNotEmpty()) ui { resolutions = sizes }
            } catch (error: Exception) {
                val text = label("查询失败：", "Query failed: ") + (error.message ?: error.javaClass.simpleName)
                showStatus(text)
                result(text)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        if (previewWanted) {
            setPreviewLoading(true)
            setPanelVisible(false)
            showStatus(label("正在启动 Surface 预览…", "Starting Surface preview…"))
            maybeStartPreview()
        } else {
            setPreviewLoading(false)
            setPreviewActionsEnabled(false)
        }
    }

    override fun onStop() {
        foreground = false
        zoomDisplayTimeout?.cancel()
        invalidateStart()
        setPreviewLoading(false)
        setPreviewActionsEnabled(false)
        cleanup { stopProducer() }
        super.onStop()
    }

    private fun alive(token: Long) = foreground && generation.get() == token

    private fun canStart(texture: SurfaceTexture, requestId: Long) =
        foreground && texture === availableTexture && startRequest.get() == requestId

    private fun startProducer(texture: SurfaceTexture, configuration: PreviewRequest, requestId: Long): Long? {
        val request = configuration.camera
        check(producerCallback == null) { "Stop the current Surface before starting another" }
        if (!canStart(texture, requestId)) return null
        // Keep the existing NV21 demo's firmware handoff grace when replacing a producer.
        // The Binder stop returns before camera/GL cleanup has finished; never force-close peers.
        val waitMs = 1500 - (SystemClock.elapsedRealtime() - producerStoppedAt)
        if (waitMs > 0) Thread.sleep(waitMs)
        if (!canStart(texture, requestId)) return null
        val service = checkNotNull(GlassSdk.getGlassMediaService()) { "Media service is not connected" }
        CameraZoomSession.requireCapability(service, CameraZoomSession.CAPABILITY_SHARE)
        val mark = configuration.watermark
        val config = request.options().let {
            if (configuration.followShared) it.copy(zoom = CameraZoomConfig(CameraZoomConfig.FOLLOW_SHARED)) else it
        }
        // Only unknown transaction returns zero. IPC failures propagate, and nonzero malformed
        // contracts fail validation; neither condition is silently downgraded to Options.
        val contract = SurfaceShareContractProbe.read(service, config, false, mark?.hasWatermark() == true)
        val plan = CameraSurfaceBufferPlan.resolve(contract, request.width, request.height, mark != null)
        if (!canStart(texture, requestId)) return null
        // Install exactly the negotiated size BEFORE constructing and submitting the Surface.
        preview.setCameraBufferSize(plan.width, plan.height, texture)
        val surface = Surface(texture)
        val token = generation.incrementAndGet()
        appliedEis = null; appliedFps = null; producerRequestedEis = request.eis
        ui {
            if (alive(token) && texture === availableTexture) {
                displayWidth = if (plan.path == CameraSurfaceContract.PATH_DIRECT_CAMERA) plan.height else plan.width
                displayHeight = if (plan.path == CameraSurfaceContract.PATH_DIRECT_CAMERA) plan.width else plan.height
                fitPreview()
            }
        }
        showStatus("Surface negotiated=${plan.negotiated}; buffer=${plan.width}×${plan.height}; path=${plan.path}; callbackSource=${plan.callbackUsesCameraSourceSize}")
        val session = CameraZoomSession()
        val callback = object : ICameraSurfaceCallback.Stub() {
            override fun getCallbackId() = "sdk222-surface-${System.identityHashCode(this)}-$token"
            override fun onCameraOpened(width: Int, height: Int) {
                if (alive(token)) {
                    applyActualOutputSize(texture, token, width, height, plan)
                    L.i("CameraDemo", surfaceDimensions(request, plan, width, height) + "\n${cameraApiState(session.state)}\n${runtimeState()}")
                    showLiveStatus()
                }
            }
            override fun onCameraClosed() { failProducer(token, requestId, label("相机已关闭，请重新开始预览。", "Camera closed. Start preview again.")) }
            override fun onError(code: Int, msg: String?) { failProducer(token, requestId, "Surface error=$code") }
            override fun onSurfaceShareConfigChanged(width: Int, height: Int, appliedPreviewFps: Int, videoStabilizationEnabled: Boolean) {
                if (alive(token)) {
                    appliedEis = videoStabilizationEnabled
                    appliedFps = appliedPreviewFps
                    applyActualOutputSize(texture, token, width, height, plan)
                    L.i("CameraDemo", surfaceDimensions(request, plan, width, height) + "\n${runtimeState()}\n${cameraApiState(session.state)}")
                    showLiveStatus()
                }
            }
            override fun onZoomLevelChanged(zoomLevel: Int) {
                // Legacy integer notification is not proof of the requested fractional zoom.
            }
        }
        session.listener = object : CameraZoomSession.Listener {
            override fun onStateChanged(state: CameraZoomState) {
                if (alive(token)) {
                    updateActualZoom()
                    L.i("CameraDemo", "Surface: ${cameraApiState(state)}\n${runtimeState()}")
                    showLiveStatus()
                }
            }
            override fun onError(code: Int, reason: String) {
                failProducer(token, requestId, "Surface session error=$code")
            }
        }
        producerWatermark = mark
        producerService = service; producerCallback = callback
        producerSurface = surface; producerTexture = texture; zoomSession = session
        previewStarted = true
        updateActualZoom()
        observing.set(false)
        try {
            if (plan.negotiated) {
                service.startCameraShareWithContract(surface, callback, false, config, mark, session.callback(), plan.contract)
            } else {
                service.startCameraShareWithOptions(surface, callback, false, config, mark, session.callback())
            }
        } catch (error: Exception) {
            stopProducer(); throw error
        }
        if (!canStart(texture, requestId)) {
            stopProducer()
            return null
        }
        return token
    }

    private fun failProducer(token: Long, requestId: Long, message: String) {
        cleanup {
            if (!alive(token) || startRequest.get() != requestId) return@cleanup
            try { stopProducer() } finally {
                ui { if (foreground && startRequest.get() == requestId) {
                    previewWanted = false
                    requestedTexture = null
                    setPreviewLoading(false)
                    setPreviewActionsEnabled(false)
                    showStatus(message)
                    beginQueryResult(label("预览已结束", "Preview ended"))(message)
                } }
            }
        }
    }

    private fun stopProducer() {
        val stoppedGeneration = generation.incrementAndGet()
        previewStarted = false
        ui { if (generation.get() == stoppedGeneration) {
            setPreviewActionsEnabled(false)
        } }
        appliedEis = null; appliedFps = null
        val service = producerService
        val callback = producerCallback
        val surface = producerSurface
        producerWatermark = null
        producerService = null; producerCallback = null; producerSurface = null; producerTexture = null
        val session = zoomSession
        zoomSession = null
        updateActualZoom()
        session?.listener = null
        try {
            // Stop only this callback's producer, before releasing the local BufferQueue endpoint.
            session?.close()
            if (callback != null) service?.stopCameraShare(callback)
        } finally {
            surface?.release()
            if (callback != null) {
                producerStoppedAt = SystemClock.elapsedRealtime()
                showStatus("Surface cleanup complete")
            }
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        availableTexture = surface
        fitPreview()
        maybeStartPreview()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { fitPreview() }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (availableTexture === surface) {
            availableTexture = null
            invalidateStart()
            setPreviewLoading(foreground && previewWanted)
            setPreviewActionsEnabled(false)
        }
        cleanup {
            try { if (producerTexture === surface) stopProducer() } finally { surface.release() }
        }
        // We own the asynchronous release so the remote producer is detached first.
        return false
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        val now = SystemClock.elapsedRealtime()
        measuredFrames++
        if (measuredSince == 0L) measuredSince = now
        if (now - measuredSince >= 1000) {
            L.i("CameraDemo", "Surface frames=$measuredFrames fps=${measuredFrames * 1000 / (now - measuredSince)} display=${displayWidth}x${displayHeight}")
            showLiveStatus()
            measuredFrames = 0; measuredSince = now
        }
        val session = zoomSession ?: return
        val state = session.state ?: return
        if (!foreground || state.ready || now - lastObservedAt < 50 || !observing.compareAndSet(false, true)) return
        val timestampNs = surface.timestamp
        lastObservedAt = now
        io {
            try { if (session === zoomSession) session.observeSurfaceFrame(timestampNs) }
            finally { observing.set(false) }
        }
    }

    private fun fitPreview() {
        // Fit the view itself, as the original GL renderer fits its content. TextureView must
        // not first stretch into the controls' remaining area and rely on a second correction.
        preview.setPreviewAspectRatio(displayWidth, displayHeight)
    }

    private fun applyActualOutputSize(texture: SurfaceTexture, token: Long, sourceWidth: Int, sourceHeight: Int,
        plan: CameraSurfaceBufferPlan) {
        // A negotiated contract describes the output buffer, which may have a different aspect
        // from the camera source. Otherwise mirror the original page's actual callback geometry,
        // including direct Camera2 output; requested dimensions alone are not actual dimensions.
        if (plan.negotiated || sourceWidth <= 0 || sourceHeight <= 0) return
        val output = if (plan.callbackUsesCameraSourceSize) sourceHeight to sourceWidth
            else sourceWidth to sourceHeight
        ui {
            if (!alive(token) || texture !== availableTexture) return@ui
            displayWidth = output.first
            displayHeight = output.second
            // Updating display geometry must NOT resize a direct Camera2 producer after start.
            plan.legacyWatermarkResize(sourceWidth, sourceHeight)?.let { (width, height) ->
                preview.setCameraBufferSize(width, height, texture)
            }
            fitPreview()
        }
    }

    private fun surfaceDimensions(request: CameraApiRequest, plan: CameraSurfaceBufferPlan, width: Int, height: Int): String {
        val callbackMeaning = if (plan.callbackUsesCameraSourceSize) "camera source" else "output"
        val buffer = if (plan.negotiated) "${plan.width}×${plan.height} (contract)" else if (plan.path == CameraSurfaceContract.PATH_COMPOSED) "$height×$width (legacy watermark)" else "${plan.width}×${plan.height} (legacy direct)"
        return "requested=${request.width}×${request.height}; $callbackMeaning=$width×$height; buffer=$buffer"
    }
}
