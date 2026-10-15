package com.rokid.glass.camera

import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.rokid.glass.view.glsurface.view.I420SurfaceView
import com.rokid.glesse.R
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.camera.CameraShareHelper
import com.rokid.security.glass3.open.sdk.camera.CameraZoomSession
import com.rokid.security.glass3.sdk.base.data.media.CameraZoomState
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * I420 用于需要 Y/U/V 独立平面的消费者，例如 WebRTC I420Buffer 或原生视频处理；尤其适合
 * 需要跨线程持有帧的链路。按 strideY/U/V 访问，最终消费者释放时 close，而不是回调结束就归还。
 * 本页同步模式在回调内复制预览数据；异步模式把租约交给有界工作队列，工作线程完成复制后归还。
 * 两种模式都使用真实导出帧，没有人为延迟。GL 后续渲染使用自有副本，不再读取已归还的租约。
 * 接入 WebRTC 时应将 close 接到最终释放回调；本页展示线程交接与归还方式，未接入 WebRTC。
 *
 * 选型：只需 GPU 预览/合成用 Surface；算法已有 NV21 输入用 NV21；需要 I420 平面与异步租约用本页。
 * 共享内存减少帧传递中的整帧复制，但并不自动显示画面，也不代表整条链路完全无复制。
 * NV21 Buffer 也使用共享内存，但只能在回调期间借用；这里的 I420 租约可以交给异步消费者，
 * 由最终释放回调 close。若下游只接受 NV21，不必为了“共享内存”改用 I420 再转回 NV21。
 * 本页额外用 I420SurfaceView 复制有效像素后上传三个纹理来预览，不另启动 Surface 共享，
 * 所见画面来自本次 I420 导出。关闭预览可观察纯消费性能；Surface 的文字水印不会自动出现在这里。
 * 每帧（包括队满、停止后的迟到帧）都必须归还，停止导出不代替已借出租约的 close。
 */
class I420ExportFragment : CameraControlsFragment() {
    companion object { fun newInstance() = I420ExportFragment() }
    override val previewRequiresStart = true
    private var previewWanted = true
    override val previewStartsAutomatically get() = previewWanted
    override val previewSettingsText get() = label("设置", "Settings")
    private val helper = CameraShareHelper()
    private val epoch = AtomicLong()
    @Volatile private var foreground = false
    @Volatile private var exporting = false
    private var version2 = true
    @Volatile private var activeOptions = false
    private var asyncConsumer = false
    private lateinit var preview: I420SurfaceView
    private lateinit var exportInfo: TextView
    private lateinit var exportInfoScroll: ScrollView
    private val previewLock = Any()
    @Volatile private var previewEnabled = true
    @Volatile private var consumer: LeaseConsumer<CameraShareHelper.I420FrameLease>? = null
    private var lastConsumer: LeaseConsumer<CameraShareHelper.I420FrameLease>? = null
    private var lastRequest: CameraApiRequest? = null
    private var lastOptions = true
    private var lastAsync = false
    private data class FrameInfo(
        val width: Int,
        val height: Int,
        val strideY: Int,
        val strideU: Int,
        val strideV: Int,
        val timestampNs: Long,
        val receivedAtMs: Long,
    )
    @Volatile private var frameInfo: FrameInfo? = null
    private var sampledConsumer: LeaseConsumer<CameraShareHelper.I420FrameLease>? = null
    private var lastSampleAtMs = 0L
    private var lastSampleCount = 0
    private var measuredFps: Long? = null
    private val refreshExportInfo = object : Runnable {
        override fun run() {
            if (!foreground) return
            showLiveStatus()
            exportInfo.postDelayed(this, 1000)
        }
    }
    private var restartAfter = 0L
    private var ratio = 1.575f
    private var fps = 15
    private var width = 640
    private var height = 480
    private lateinit var apiButton: Button
    private lateinit var zoomButton: Button
    private lateinit var fpsButton: Button
    private lateinit var sizeButton: Button
    private lateinit var startButton: Button

    override fun buildControls() {
        preview = I420SurfaceView(requireContext()).apply { tag = "i420_preview" }
        previewHost.addView(preview, FrameLayout.LayoutParams(-1, -1))
        exportInfo = TextView(requireContext()).apply {
            tag = "i420_live_export_info"
            setTextColor(Color.WHITE)
            textSize = 14f
            val inset = (8 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset, inset, inset)
        }
        exportInfoScroll = ScrollView(requireContext()).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            addView(exportInfo)
        }
        previewHost.addView(exportInfoScroll, FrameLayout.LayoutParams(-1, -1))
        note(label("I420 设置 · 修改后点击应用", "I420 settings · Apply changes below"))
        note(getString(R.string.i420_settings_hint))
        sizeButton = action(label("分辨率：", "Resolution: ") + "$width×$height") {
            val sizes = listOf(640 to 480, 1280 to 720, 1920 to 1080)
            val next = sizes[(sizes.indexOf(width to height) + 1) % sizes.size]
            width = next.first; height = next.second
            sizeButton.text = label("分辨率：", "Resolution: ") + "$width×$height"
        }.apply { tag = "i420_size" }
        fpsButton = action(label("帧率：$fps 帧/秒", "Frame rate: $fps fps")) {
            val values = listOf(15, 20, 24, 30)
            fps = values[(values.indexOf(fps) + 1) % values.size]
            fpsButton.text = label("帧率：$fps 帧/秒", "Frame rate: $fps fps")
        }.apply { tag = "i420_fps" }
        settingRow(sizeButton, fpsButton)
        // API choice and processing strategy are normal integration options, visible by default.
        apiButton = action(getString(R.string.i420_api_v2)) {
            version2 = !version2
            apiButton.setText(if (version2) R.string.i420_api_v2 else R.string.i420_api_v1)
            zoomButton.isEnabled = version2
            zoomButton.text = if (version2) label("倍率：", "Zoom: ") + "$ratio×" else label("倍率：1×（旧接口）", "Zoom: 1× (legacy)")
        }.apply { tag = "i420_api" }
        lateinit var consumption: Button
        consumption = action(getString(R.string.i420_processing_sync)) {
            asyncConsumer = !asyncConsumer
            consumption.setText(if (asyncConsumer) R.string.i420_processing_async else R.string.i420_processing_sync)
        }.apply { tag = "i420_async" }
        settingRow(apiButton, consumption)
        zoomButton = action(label("倍率：", "Zoom: ") + "$ratio×") {
            val values = listOf(1f, 1.25f, 1.575f, 2f, 4f)
            ratio = values[(values.indexOf(ratio) + 1) % values.size]
            zoomButton.text = label("倍率：", "Zoom: ") + "$ratio×"
        }.apply { tag = "i420_zoom" }

        // Fold optional view and session controls to keep the primary settings compact.
        val extraViews = mutableListOf<View>()
        var expanded = false
        lateinit var more: Button
        more = action(getString(R.string.camera_view_and_zoom)) {
            expanded = !expanded
            extraViews.forEach { it.visibility = if (expanded) View.VISIBLE else View.GONE }
            more.setText(if (expanded) R.string.camera_hide_options else R.string.camera_view_and_zoom)
        }.apply { tag = "i420_more" }
        settingRow(zoomButton, more)
        val firstExtra = content.childCount
        lateinit var previewButton: Button
        fun previewLabel() = if (previewEnabled) label("显示导出画面：开启", "Show exported frames: on")
            else label("显示导出画面：关闭", "Show exported frames: off")
        previewButton = action(previewLabel()) {
            synchronized(previewLock) {
                previewEnabled = !previewEnabled
                if (!previewEnabled) preview.clear()
            }
            previewButton.text = previewLabel()
            showLiveStatus()
        }.apply { tag = "i420_preview_toggle" }
        note(getString(R.string.i420_processing_hint))
        action(label("仅更新运行中的倍率", "Update zoom without restarting")) {
            val value = ratio
            val result = beginQueryResult(label("更新倍率", "Update zoom"))
            io {
                if (!activeOptions || !exporting) result(label("请先开始 V2 导出。", "Start V2 export first."))
                else result(zoomResult(helper.i420ZoomSession.setZoom(value)))
            }
        }
        // 后续处理依赖已确认的实际倍率时，用协程等待；返回 null 时不能使用请求值代替。
        // Await frame-confirmed zoom when subsequent work depends on it; never substitute the request for null.
        action(getString(R.string.camera_zoom_confirm)) {
            val value = ratio
            val token = epoch.get()
            val result = beginQueryResult(label("倍率确认", "Zoom confirmation"))
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val actual = withContext(controlDispatcher) {
                        check(token == epoch.get() && foreground && activeOptions && exporting) { label("请先开始 V2 导出。", "Start V2 export first.") }
                        helper.i420ZoomSession.setZoomAndAwait(value, 2000)
                    }
                    if (token == epoch.get()) result(cameraZoomAwaitResult(value, actual))
                } catch (error: Exception) { if (token == epoch.get()) result(error.message.orEmpty()) }
            }
        }.tag = "i420_await"
        action(label("撤销本页倍率，跟随共享相机", "Follow shared camera zoom")) {
            val result = beginQueryResult(label("跟随共享倍率", "Follow shared zoom"))
            io {
                if (!activeOptions || !exporting) result(label("请先开始 V2 导出。", "Start V2 export first."))
                else result(zoomResult(helper.i420ZoomSession.followShared()))
            }
        }
        for (index in firstExtra until content.childCount) extraViews.add(content.getChildAt(index))
        extraViews.forEach { it.visibility = View.GONE }

        startButton = action(label("应用并预览", "Apply and preview")) {
            previewWanted = true
            applyExport(CameraApiRequest(width, height, fps, ratio), version2, asyncConsumer)
        }.apply { tag = "i420_start" }
        // The same status page is reachable before starting and after stopping; no separate
        // lease query with a fixed string, and no global zoom cache presented as output evidence.
        val exportStatus = action(label("查看导出状态", "Export status")) { queryExportState() }
            .apply { tag = "i420_status" }
        settingRow(startButton, exportStatus)
        previewAction(label("停止导出", "Stop export")) { previewWanted = false; io { stopExport() } }.tag = "i420_stop"
        previewAction(label("查看导出状态", "Export status")) { queryExportState() }.tag = "i420_preview_status"
    }

    private fun applyExport(config: CameraApiRequest, options: Boolean, async: Boolean) {
        startButton.isEnabled = false
        setPreviewLoading(true)
        setPreviewActionsEnabled(false)
        setPanelVisible(false)
        io {
            try { startExport(config, options, async) }
            catch (error: Exception) { showStatus(label("启动失败：", "Start failed: ") + error.message) }
            finally { ui {
                startButton.isEnabled = true
                setPreviewLoading(false)
                setPreviewActionsEnabled(exporting)
                startButton.text = label("应用并预览", "Apply and preview")
            } }
        }
    }

    private fun zoomResult(code: Int) = when (code) {
        0 -> label("请求已接受，实际倍率以画面确认后的状态为准。", "Accepted. Actual zoom follows frame confirmation.")
        -1 -> label("倍率参数无效。", "Invalid zoom parameter.")
        -2 -> label("会话未创建或已关闭。", "Session not created or already closed.")
        else -> label("控制失败：$code", "Control failed: $code")
    }

    private fun showLiveStatus() = ui {
        if (!foreground || !exporting) return@ui
        val currentConsumer = consumer
        val now = SystemClock.elapsedRealtime()
        // UI 定时读取本地计数；帧回调只保存元数据，不持有租约或排队 UI 更新。
        // The UI samples local counters; frame callbacks save metadata without retaining leases or queuing UI work.
        val returned = currentConsumer?.returned?.get() ?: 0
        val received = currentConsumer?.received?.get() ?: 0
        if (sampledConsumer !== currentConsumer) {
            sampledConsumer = currentConsumer
            lastSampleAtMs = now
            lastSampleCount = received
            measuredFps = null
        } else if (now - lastSampleAtMs >= 1000) {
            measuredFps = (received - lastSampleCount) * 1000L / (now - lastSampleAtMs)
            lastSampleAtMs = now
            lastSampleCount = received
        }
        val info = frameInfo
        val image = if (info == null) label("等待首帧", "Waiting for first frame") else "${info.width}×${info.height} · ${measuredFps ?: "—"} " + label("帧/秒", "fps")
        val zoom = if (activeOptions) cameraActualZoomText(helper.i420ZoomSession.state) else label("V1 旧接口 · 请求 1×", "Legacy V1 · requested 1×")
        val requestText = lastRequest?.let { label("请求：", "Requested: ") + "${it.width}×${it.height} · ${it.fps} FPS" }.orEmpty()
        showStatus(label("I420 预览", "I420 preview") + "\n" + requestText + "\n" +
            label("当前画面：", "Current frames: ") + image + "\n" + zoom +
            (if (previewEnabled) "" else "\n" + label("画面显示已关闭，数据仍在导出", "Image hidden; data export continues")),
            showInSettings = false)
        exportInfoScroll.visibility = if (previewEnabled) View.GONE else View.VISIBLE
        if (!previewEnabled) exportInfo.text = buildString {
            appendLine(getString(R.string.i420_live_processing,
                getString(if (lastAsync) R.string.i420_processing_async else R.string.i420_processing_sync)))
            appendLine(getString(R.string.i420_live_received, received))
            appendLine(getString(R.string.i420_live_returns, returned, received - returned))
            appendLine(getString(R.string.i420_live_drops,
                currentConsumer?.dropped?.get() ?: 0, currentConsumer?.returnFailures?.get() ?: 0))
            if (info != null) {
                appendLine(getString(R.string.i420_live_strides, info.strideY, info.strideU, info.strideV))
                appendLine(getString(R.string.i420_live_timestamp, info.timestampNs))
                appendLine(getString(R.string.i420_live_age, (now - info.receivedAtMs).coerceAtLeast(0)))
            } else {
                appendLine(label("等待首帧", "Waiting for first frame"))
            }
            append(getString(R.string.i420_live_returns_hint))
        }
    }

    private fun queryExportState() {
        val result = beginQueryResult(label("I420 导出状态", "I420 export status"))
        io {
            val media = GlassSdk.getGlassMediaService()
            val capabilities = runCatching { media?.mediaCapabilities }.getOrNull()
            val state = if (activeOptions && exporting) runCatching { helper.i420ZoomSession.refreshState() }.getOrNull() else null
            val c = consumer ?: lastConsumer
            // Read returned before received so concurrent callbacks cannot yield negative outstanding.
            val returned = c?.returned?.get() ?: 0
            val received = c?.received?.get() ?: 0
            val outstanding = received - returned
            val info = frameInfo
            result(buildString {
                appendLine(label("导出：", "Export: ") + when {
                    exporting -> label("进行中", "Running")
                    lastRequest == null -> label("尚未开始", "Not started")
                    else -> label("已停止", "Stopped")
                })
                lastRequest?.let { request ->
                    appendLine(label("本轮接口：", "This run's API: ") + if (lastOptions) "V2" else label("V1（旧接口）", "V1 (legacy)"))
                    appendLine(label("处理方式：", "Processing: ") + getString(if (lastAsync) R.string.i420_processing_async else R.string.i420_processing_sync))
                    appendLine(label("请求输出：", "Requested output: ") + "${request.width}×${request.height} · ${request.fps} FPS")
                    appendLine(label("实际帧尺寸：", "Actual frame size: ") + (info?.let { "${it.width}×${it.height}" } ?: label("尚未收到帧", "No frame received")))
                    if (exporting && lastOptions) appendLine(cameraActualZoomText(state))
                    else if (exporting) appendLine(label("旧接口请求 1×；没有本输出的实际倍率确认。", "Legacy requests 1×; no per-output zoom confirmation."))
                }
                appendLine()
                appendLine(label("帧归还统计（本轮）", "Frame returns (this run)"))
                appendLine(label("已接收：$received", "Received: $received"))
                appendLine(label("已归还：$returned", "Returned: $returned"))
                appendLine(label("待归还：$outstanding", "Outstanding: $outstanding"))
                appendLine(label("丢帧：${c?.dropped?.get() ?: 0}", "Dropped: ${c?.dropped?.get() ?: 0}"))
                appendLine(label("归还失败：${c?.returnFailures?.get() ?: 0}", "Return failures: ${c?.returnFailures?.get() ?: 0}"))
                appendLine(label("统计本 Demo 的帧释放调用。导出中有待归还帧是正常的；停止并处理完在途帧后应归零。", "Counts this Demo's frame-release calls. Outstanding frames are normal while running; they should drain after stopping."))
                appendLine()
                appendLine(label("设备能力", "Device capabilities"))
                appendLine(label("共享相机小数倍率：", "Fractional zoom for shared camera: ") + when {
                    capabilities == null -> label("未获取到（服务未连接或查询失败）", "Unavailable (disconnected or query failed)")
                    capabilities and CameraZoomSession.CAPABILITY_SHARE != 0 -> label("支持", "Supported")
                    else -> label("不支持", "Unsupported")
                })
            })
        }
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        exportInfo.post(refreshExportInfo)
        if (previewWanted) applyExport(lastRequest ?: CameraApiRequest(width, height, fps, ratio),
            if (lastRequest != null) lastOptions else version2,
            if (lastRequest != null) lastAsync else asyncConsumer)
    }
    override fun onResume() { super.onResume(); preview.onResume() }
    override fun onPause() { preview.onPause(); super.onPause() }
    override fun onStop() {
        exportInfo.removeCallbacks(refreshExportInfo)
        synchronized(previewLock) { foreground = false; epoch.incrementAndGet(); preview.clear() }
        cleanup { stopExport(report = false) }
        super.onStop()
    }
    private fun stopExport(report: Boolean = true) {
        synchronized(previewLock) { epoch.incrementAndGet(); preview.clear() }
        exporting = false
        activeOptions = false
        consumer?.close()
        lastConsumer = consumer ?: lastConsumer
        consumer = null
        helper.i420ZoomSession.listener = null
        val wasActive = helper.isI420Active()
        try { helper.releaseI420Export() }
        finally {
            if (wasActive) restartAfter = SystemClock.elapsedRealtime() + 1500
            if (report) ui {
                exportInfoScroll.visibility = View.GONE
                setPreviewLoading(false)
                setPreviewActionsEnabled(false)
                startButton.isEnabled = true
                startButton.text = label("应用并预览", "Apply and preview")
            }
        }
        if (report) showStatus(label("导出已停止。可查看本轮帧归还统计。", "Export stopped. This run's frame returns remain available."))
    }
    private fun startExport(config: CameraApiRequest, options: Boolean, async: Boolean) {
        stopExport(report = false)
        val token = epoch.incrementAndGet()
        // Firmware releases asynchronously; wait only on the control thread before replacing it.
        val waitMs = restartAfter - SystemClock.elapsedRealtime()
        if (waitMs > 0) Thread.sleep(waitMs)
        if (!foreground || token != epoch.get()) return
        val outputPreview = preview
        activeOptions = options
        exporting = true
        lastRequest = config; lastOptions = options; lastAsync = async; frameInfo = null
        helper.i420ZoomSession.listener = if (options) object : CameraZoomSession.Listener {
            override fun onStateChanged(state: CameraZoomState) { if (token == epoch.get()) showLiveStatus() }
            override fun onError(code: Int, reason: String) { if (token == epoch.get()) showStatus(label("倍率控制失败：", "Zoom control failed: ") + "$code · $reason") }
        } else null
        fun consume(frame: CameraShareHelper.I420FrameLease) {
            if (!foreground || token != epoch.get()) return
            synchronized(previewLock) {
                if (!foreground || token != epoch.get()) return
                if (previewEnabled) outputPreview.submit(frame.dataY, frame.dataU, frame.dataV,
                    frame.width, frame.height, frame.strideY, frame.strideU, frame.strideV)
            }
        }
        // Both modes count actual close calls. Async submit owns every frame, including queue
        // rejection and late callbacks; stopping does not release the running consumer's lease.
        // The worker performs real preview preparation. LeaseConsumer closes after consume
        // completes, including errors; a rejected frame is returned without being queued.
        val outputConsumer = LeaseConsumer<CameraShareHelper.I420FrameLease>(consume = ::consume,
            onError = { if (token == epoch.get()) showStatus(label("帧处理或归还失败：", "Frame processing or return failed: ") + it.message) }, asynchronous = async)
        consumer = outputConsumer
        val callback = object : CameraShareHelper.I420BufferCallback {
            override fun onCameraOpened(width: Int, height: Int, appliedPreviewFps: Int) {
                if (token == epoch.get()) showLiveStatus()
            }
            override fun onI420Frame(frame: CameraShareHelper.I420FrameLease) {
                synchronized(previewLock) {
                    if (foreground && token == epoch.get()) {
                        frameInfo = FrameInfo(frame.width, frame.height, frame.strideY, frame.strideU,
                            frame.strideV, frame.timestampNs, SystemClock.elapsedRealtime())
                    }
                }
                outputConsumer.submit(frame)
            }
            override fun onError(code: Int, msg: String) {
                cleanup {
                    if (token != epoch.get()) return@cleanup
                    stopExport()
                    showStatus(label("导出失败：", "Export failed: ") + "$code · $msg")
                }
            }
        }
        showLiveStatus()
        try {
            if (options) helper.initI420BufferExportWithOptions(config.options(), callback)
            else helper.initI420BufferExportWithConfig(config.legacy(), callback)
            ui { if (foreground && token == epoch.get()) {
                setPreviewLoading(false)
                setPreviewActionsEnabled(true)
            } }
        } catch (error: Exception) { stopExport(report = false); throw error }
    }
}
