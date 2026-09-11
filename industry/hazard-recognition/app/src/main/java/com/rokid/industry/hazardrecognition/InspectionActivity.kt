package com.rokid.industry.hazardrecognition

import android.app.Activity
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.TextView
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.camera.CameraShareHelper
import com.rokid.security.glass3.open.sdk.client.IServiceConnectionCallback
import com.rokid.security.glass3.sdk.base.data.media.CameraShareConfig
import com.rokid.security.system.server.IClientCallback
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 用例入口：打开眼镜应用，即可预览现场并通过 Wi-Fi 识别消防隐患。
 * 阅读顺序：startSession → connectSdk → startCamera → inspect → expireResult。
 * 本 Demo 仅使用 SDK 的服务绑定、客户端注册和 NV21 相机能力；未传入 Rokid AK/SK，
 * 也未接入 ASR/TTS。调用大模型所需的地址和密钥放在 ModelConfig 中，不是 SDK 初始化参数。
 */
class InspectionActivity : Activity() {
    private lateinit var preview: Nv21PreviewView
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var detail: TextView
    private lateinit var table: HazardTableView
    private val frames = FrameQueue()
    private val ledger = HazardLedger() // 用例：只保存用于判断重复的隐患摘要；页面不显示历史列表。
    private var latestResult: InspectionResult? = null
    private var latestResultTime: String? = null
    private var resultExpiresAt = 0L
    private var resultExpiry: Job? = null
    private var frameOffer = FrameOffer.STALE
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val config = ModelConfig(BuildConfig.MODEL_ENDPOINT, BuildConfig.MODEL_NAME, BuildConfig.MODEL_API_KEY)
    private val client = InspectionClient(config)
    private var camera: CameraShareHelper? = null
    private var tick: Job? = null
    private var analysis: Job? = null
    private var resumed = false
    private var destroyed = false
    private var binding = false
    private var running = false
    private var cameraStarted = false
    private var startedAt = 0L
    private var cameraStartedAt = 0L
    private var retryAt = 0L
    private var failures = 0
    private var auto = true
    private val frameLock = Any()
    // 停止取帧时增加 session 编号；迟到的旧回调会被忽略，避免更新已退出的页面。
    @Volatile private var session = 0
    @Volatile private var latest: Nv21Frame? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inspection)
        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        result = findViewById(R.id.result)
        detail = findViewById(R.id.detail)
        table = findViewById(R.id.hazard_table)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        preview.onResume()
        expireResult() // 用例：回到前台先清除过期结果，不重新计算旧结果的显示时长。
        // 用例：通过眼镜系统服务接收共享 NV21 帧，本应用不直接打开 Android Camera。
        // 进入前台即可绑定 SDK，不在应用侧申请 CAMERA 运行时权限。
        startSession()
    }

    /** 用例 1：进入前台后启动 SDK 会话。帧队列和界面都由主线程更新，避免多个线程同时修改。 */
    private fun startSession() {
        if (!resumed || running) return
        running = true
        val token = ++session
        startedAt = SystemClock.elapsedRealtime()
        retryAt = 0
        failures = 0
        frames.clear()
        table.render(latestResult)
        status.text = "正在连接相机"
        frameOffer = FrameOffer.STALE
        result.text = latestResult?.summary ?: "对准识别区域，等待识别"
        detail.text = latestResultTime?.let { "采样 $it · 最近结果 · 依据供复核" }
            ?: "九小场所 · 最近结果 · 法规依据供复核"
        connectSdk()
        if (!running) return
        // sampleFps=3 表示约每 334ms 筛选一次最新帧，不代表云端每秒完成三次推理。
        tick = scope.launch {
            while (isActive && token == session) {
                val now = SystemClock.elapsedRealtime()
                val frame = latest
                if (!cameraStarted && now - startedAt > 15000) {
                    binding = false
                    fail("SDK 连接超时，单击重试")
                    break
                }
                if (cameraStarted && now - (frame?.receivedAt ?: cameraStartedAt) > 8000) {
                    fail("相机无画面，单击重试")
                    break
                }
                val wifi = hasWifi()
                if (!wifi && analysis?.isActive == true) {
                    analysis?.cancel()
                    result.text = "网络中断，已保留最近结果"
                }
                // 未配置模型时仍可预览；具备 Wi-Fi 和有效配置后才把候选帧送入识别队列。
                if (auto && wifi && config.error() == null && frame?.isFresh(now) == true) {
                    frameOffer = frames.offer(frame, now)
                    inspect()
                }
                status.text = when {
                    !cameraStarted -> "正在连接相机"
                    frame == null || !frame.isFresh(now) -> "等待相机画面"
                    !wifi -> "网络未连接"
                    config.error() != null -> "模型配置无效"
                    analysis?.isActive == true -> "识别中"
                    now < retryAt -> "请求退避中"
                    auto && frameOffer == FrameOffer.KNOWN_HAZARD -> "该隐患重复，已收录"
                    auto && frameOffer == FrameOffer.KNOWN_FRAME -> "画面重复，已识别"
                    auto -> "等待识别"
                    else -> "已暂停自动识别"
                }
                delay(BuildConfig.SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun connectSdk() {
        // 用例 2：先绑定眼镜服务，再注册客户端，不能在 bind 返回时就打开相机。
        // 页面暂停时只停止取帧，保留 SDK 连接。此版本 SDK 只会解绑已连接的服务，
        // 所以页面销毁时若还未连接，等连接回调到达后再释放。
        // isReady() 仅表示服务已经连接；仍需注册客户端并等待 onReady()。
        if (GlassSdk.isReady()) {
            registerCameraClient()
            return
        }
        if (binding) return
        binding = true
        try {
            GlassSdk.bindSecurityService(applicationContext, object : IServiceConnectionCallback {
                override fun onServiceConnected() {
                    runOnUiThread {
                        binding = false
                        if (destroyed) runCatching { GlassSdk.release() }
                        else if (resumed && running) registerCameraClient()
                    }
                }
                override fun onServiceDisconnected() = disconnected()
                override fun onBindingDied() = disconnected()
                private fun disconnected() {
                    runOnUiThread {
                        binding = false
                        if (resumed && running) fail("眼镜服务断开，单击重试")
                    }
                }
            })
        } catch (_: Exception) {
            binding = false
            fail("无法连接眼镜 SDK，单击重试")
        }
    }

    private fun registerCameraClient() {
        val token = session
        try {
            // HazardRecognition 是客户端名称，不是 AK/SK；收到 onReady 才调用相机接口。
            GlassSdk.registerClient("HazardRecognition", object : IClientCallback.Stub() {
                override fun onReady() = post(token) { startCamera(token) }
            })
        } catch (_: Exception) { fail("眼镜 SDK 注册失败，单击重试") }
    }

    /** 用例 3：从 SDK 返回的尺寸中选择适合小窗预览和上传的分辨率，再开启 NV21 输出。 */
    private fun startCamera(token: Int) {
        if (cameraStarted) return
        try {
            val helper = CameraShareHelper()
            camera = helper
            // 返回值依次是宽、高、是否竖屏；第三项不是“是否叠加屏幕”。
            // 保留 SDK 返回的宽高顺序；纯相机画面由下面的 enableMix=false 决定。
            val size = PreviewSizeSelector.select(helper.getSupportedPreviewSizes())
            cameraStarted = true
            cameraStartedAt = SystemClock.elapsedRealtime()
            // 15 FPS 为相机请求帧率；实际送入模型的频率由 startSession 中的采样器控制。
            helper.initNv21ExportWithConfig(false, CameraShareConfig(
                previewWidth = size.first, previewHeight = size.second,
                previewTargetFps = 15, enableVideoStabilization = false, zoomLevel = 1,
            ), object : CameraShareHelper.Nv21Callback {
                override fun onCameraOpened(width: Int, height: Int) = Unit
                override fun onNv21Frame(nv21: ByteArray, width: Int, height: Int, timestamp: Long) {
                    if (token != session) return
                    if (!Nv21Frame.validSize(nv21.size, width, height)) {
                        post(token) { fail("相机帧格式不支持，单击重试") }
                        return
                    }
                    synchronized(frameLock) {
                        if (token != session) return
                        // SDK 可能复用回调缓冲区，必须复制后再交给异步预览、编码和网络任务。
                        // elapsedRealtime 不受系统日期调整影响，用于判断帧是否过期；currentTimeMillis 用于显示时间。
                        val frame = Nv21Frame(nv21.copyOf(), width, height,
                            SystemClock.elapsedRealtime(), System.currentTimeMillis())
                        latest = frame
                        preview.submit(frame)
                    }
                }
                override fun onCameraClosed() = post(token) { fail("相机已关闭，单击重试") }
                override fun onError(code: Int, msg: String) = post(token) { fail("相机错误 $code，单击重试") }
            })
        } catch (_: Exception) { fail("相机启动失败，单击重试") }
    }

    /** 用例 4：单击触摸板强制复检。force 允许重新检查相似画面，但仍需等待当前请求完成及失败后的重试间隔。 */
    private fun manualCheck() {
        if (!running) startSession() else {
            val frame = latest
            if (frame == null || !frame.isFresh(SystemClock.elapsedRealtime())) {
                result.text = "等待清晰的新画面"
                return
            }
            frames.offer(frame, SystemClock.elapsedRealtime(), force = true)
            inspect()
        }
    }

    /** 用例 5：一次取出 1～3 帧进行分析；已有请求进行中时直接返回，避免慢网下请求堆积。 */
    private fun inspect() {
        val now = SystemClock.elapsedRealtime()
        if (analysis?.isActive == true || !running || now < retryAt) return
        if (!hasWifi()) { result.text = "网络未连接，已保留最近结果"; return }
        config.error()?.let { result.text = it; return }
        val batch = frames.begin(now)
        if (batch.isEmpty()) return
        val token = session
        status.text = "识别中"
        analysis = scope.launch {
            var success = false
            var uncertain = false
            var hazardFrameIndices = emptySet<Int>()
            try {
                val answer = client.inspect(batch.map { it.frame }, ledger.known())
                ensureActive()
                if (token != session) return@launch
                // 模型返回后先确认页面仍在使用本次相机，再更新去重摘要；界面只展示本次 answer。
                val added = ledger.merge(answer.hazards, batch)
                latestResult = answer
                table.render(answer)
                hazardFrameIndices = answer.hazards.map { it.frameIndex }.toSet()
                success = true
                uncertain = answer.risk == Risk.UNKNOWN
                failures = 0
                retryAt = 0
                result.text = if (answer.hazards.isNotEmpty() && added == 0) "该隐患重复，已收录" else answer.summary
                val time = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(batch.last().frame.wallTime))
                latestResultTime = time
                detail.text = "采样 $time · 最近结果 · 依据供复核"
                // 用例 6：成功返回的新结果显示 10 秒；新结果替换旧结果并重置计时。
                // 本地跳过的重复帧、失败重试、相机新帧都不能延长旧结果的显示时间。
                // 清除任务独立于采样任务，因此暂停识别或相机故障也不会一直保留旧表格。
                resultExpiresAt = SystemClock.elapsedRealtime() + 10_000L
                resultExpiry?.cancel()
                resultExpiry = scope.launch {
                    delay(10_000L)
                    expireResult()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (token == session) {
                    // 连续失败后分别等待 2、4、8、16、30 秒再重试；成功后重新计数。
                    failures = (failures + 1).coerceAtMost(5)
                    retryAt = SystemClock.elapsedRealtime() + (2000L shl (failures - 1)).coerceAtMost(30000L)
                    result.text = "识别失败：${e.message ?: "请稍后重试"}"
                }
            } finally {
                // 无论成功、异常还是取消都结束本批次；只有成功结果才能写入已识别缓存。
                if (token == session) frames.complete(SystemClock.elapsedRealtime(), success, uncertain, hazardFrameIndices)
            }
        }
    }

    /** 用例：10 秒到期后清空表格，ledger 中的隐患摘要仍保留，供后续识别判断重复。 */
    private fun expireResult() {
        if (latestResult == null || SystemClock.elapsedRealtime() < resultExpiresAt) return
        latestResult = null
        latestResultTime = null
        resultExpiresAt = 0L
        table.render(null)
        result.setText(R.string.awaiting_check)
        detail.setText(R.string.inspection_note)
    }

    private fun hasWifi(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun post(token: Int, action: () -> Unit) {
        runOnUiThread { if (resumed && running && token == session) action() }
    }

    private fun fail(message: String) {
        stopSession()
        status.text = "识别已暂停"
        result.text = message
        detail.text = "已保留最近结果"
    }

    /** 用例 7：离开前台时停止取帧、取消请求并清空画面缓存；隐患摘要和结果到期时间保留。 */
    private fun stopSession() {
        running = false
        synchronized(frameLock) {
            session++
            latest = null
            preview.submit(null)
        }
        tick?.cancel()
        tick = null
        analysis?.cancel()
        analysis = null
        frames.clear()
        runCatching { camera?.releaseNv21Export() }
        camera = null
        cameraStarted = false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 用例：单击触摸板重新识别，左右滑动翻页；长按在 onKeyLongPress 中处理。
        // 设备系统可能拦截长按，只有实际分发到本 Activity 的事件才会触发暂停/恢复。
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (event.repeatCount == 0) event.startTracking()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.repeatCount == 0) table.page(if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            auto = !auto
            status.text = if (auto) "已开启实时识别" else "已暂停自动识别"
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (!event.isCanceled) manualCheck()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onPause() {
        resumed = false
        stopSession()
        preview.onPause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        // 用例：销毁时回收协程、网络连接和 SDK；尚未完成的绑定由迟到的连接回调释放。
        if (GlassSdk.isReady()) runCatching { GlassSdk.release() }
        scope.cancel()
        client.close()
        super.onDestroy()
    }
}
