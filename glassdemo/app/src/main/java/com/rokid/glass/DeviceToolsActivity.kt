package com.rokid.glass

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.rokid.glass.base.BaseGlassActivity
import com.rokid.glass.base.GlassKeyEvent
import com.rokid.glass.event.CusEventReceiverService
import com.rokid.glass.media.DeviceAudioCapture
import com.rokid.glass.utils.FileUtils
import com.rokid.glass.utils.demoString
import com.rokid.glesse.R
import com.rokid.glesse.databinding.ActivityDeviceToolsBinding
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.callback.LeqiInterceptor
import com.rokid.security.system.server.aichat.listener.AiChatListener
import com.rokid.security.glass3.qrcode.api.GlassScanCallback
import com.rokid.security.glass3.qrcode.api.GlassScanner
import com.rokid.security.glass3.qrcode.model.GlassScanConfig
import com.rokid.security.glass3.qrcode.model.ScanType
import com.rokid.security.glass3.sdk.base.data.device.bean.GlassAppConfig
import com.rokid.security.glass3.sdk.base.data.device.bean.GlassAppType
import com.rokid.security.glass3.sdk.base.data.device.bean.ThirdPartyApp
import com.rokid.security.glass3.sdk.base.data.device.wifi.WifiConnectRequest
import com.rokid.security.glass3.sdk.base.data.device.wifi.WifiNetworkInfo
import com.rokid.security.glass3.sdk.base.data.device.wifi.WifiOperationCode
import com.rokid.security.glass3.sdk.base.data.device.wifi.WifiOperationStage
import com.rokid.security.glass3.sdk.base.data.device.wifi.WifiRemoveRequest
import com.rokid.security.system.server.device.listener.IAppVisibilityListener
import com.rokid.security.system.server.device.listener.IWifiOperationCallback
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * 二维码、网络、应用显示、AI 对话、乐奇指令拦截和音频采集的使用示例。
 * Examples for QR scanning, networking, app visibility, AI chat, Leqi interception and audio capture.
 */
class DeviceToolsActivity : BaseGlassActivity() {
    override val translateTouchToGlassGesture = false

    companion object {
        private const val TAG = "DeviceTools"
        private const val DEMO_WIFI_SSID = "EBG-RD"
        private const val DEMO_WIFI_PASSWORD = "Rkd2023/"
        private const val DEMO_WIFI_HIDDEN = false
        private const val REQUEST_RECORD_AUDIO_PERMISSION = 1002
    }

    private lateinit var binding: ActivityDeviceToolsBinding
    private lateinit var buttons: List<AppCompatTextView>
    private val worker = Executors.newSingleThreadExecutor()
    private val logBuilder = StringBuilder(4000)
    private var selected = 0
    @Volatile private var hideApps = true
    @Volatile private var appVisibilityPending = false
    @Volatile private var closed = false

    private val chatRequest = AtomicReference<Any?>(null)
    private var ownsChat = false
    private val audioCapture = lazy { DeviceAudioCapture(::log) }
    private var startAudioAfterPermissionGranted = false
    private var startAudioOnResume = false
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchSwipeConsumed = false
    private var touchSwipeDirection = GlassKeyEvent.KEYCODE_FRONT
    @Volatile private var pageResumed = false
    private val leqiCallbackCount = AtomicInteger()

    // 离页后仍保留此回调，直到主页释放 SDK 时注销。
    // Keep this callback after leaving the page until Home releases the SDK.
    private val leqiInterceptor = object : LeqiInterceptor {
        override fun onLeqiInstruction(extra: String?): Boolean {
            val count = leqiCallbackCount.incrementAndGet()
            log(getString(R.string.device_leqi_callback, count, extra ?: "<null>"))
            return true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceToolsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        buttons = listOf(
            binding.btQRCodeImage, binding.btQRCode, binding.btQRCodeXml,
            binding.btConnectWifi, binding.btDisconnectWifi, binding.btConfigureAppVisibility,
            binding.btnAiChat, binding.btnStopAiChat, binding.btnRegisterLeqi,
            binding.btnSendCusEvent, binding.btnRecordAudio, binding.btnStopRecordAudio
        )
        buttons.forEachIndexed { index, button ->
            button.setOnClickListener {
                select(index)
                performAction(button.id)
            }
        }
        select(0)
    }

    private fun performAction(id: Int) {
        // 镜腿点击可能延迟到离页后送达，不能重新开启会话或抢占拦截器。
        // Ignore delayed glasses clicks after leaving; they must not restart a session or register an interceptor.
        if (!pageResumed || isFinishing || isDestroyed) return
        when (id) {
            R.id.btQRCodeImage -> {
                val bitmap = FileUtils.loadBitmapFromAssets(this, "qrcode.jpg") ?: run {
                    log(demoString(R.string.demo_failed_to_load_qr_code_image))
                    return
                }
                GlassScanner.scanFromBitmap(this, bitmap = bitmap, callback = object : GlassScanCallback {
                    override fun onScanFailure(error: String) {
                        log(demoString(R.string.demo_scan_failed, error))
                    }

                    override fun onScanSuccess(content: String?, barcode: Barcode) {
                        log(demoString(R.string.demo_scan_successful_barcode, content, barcode.rawValue))
                    }
                })
            }

            R.id.btQRCode -> {
                // cameraZoomLevel 取值范围为 1～10；适当放大可提升远距离二维码的识别效果。
                // cameraZoomLevel ranges from 1 to 10; moderate zoom can improve recognition of distant QR codes.
                GlassScanner.launch(
                    this,
                    config = GlassScanConfig(enableAutoClose = true, scanType = ScanType.QR_CODE_ONLY, cameraZoomLevel = 5),
                    scanCallback = object : GlassScanCallback {
                        override fun onScanSuccess(content: String?, barcode: Barcode) {
                            log(demoString(R.string.demo_scan_successful, content))
                        }

                        override fun onScanFailure(error: String) {
                            log(demoString(R.string.demo_scan_failed, error))
                        }
                    })
            }

            R.id.btQRCodeXml -> {
                startActivity(Intent(this, ActivityQRView::class.java))
            }

            R.id.btConnectWifi -> submitDeviceAction { connectWifi() }
            R.id.btDisconnectWifi -> submitDeviceAction { disconnectWifi() }
            R.id.btConfigureAppVisibility -> submitDeviceAction { configureAppVisibility() }
            R.id.btnAiChat -> submitAiAction { startAi() }
            R.id.btnStopAiChat -> submitAiAction {
                stopAi()
                log(getString(R.string.demo_stopping_ai_chat))
            }
            R.id.btnRegisterLeqi -> registerLeqi()
            R.id.btnSendCusEvent -> sendCusEvent()
            R.id.btnRecordAudio -> startAudioCapture()
            R.id.btnStopRecordAudio -> worker.execute {
                if (pageResumed) runCatching { audioCapture.value.stop() }
                    .onSuccess { log(demoString(R.string.demo_stopping_audio_recording)) }
                    .onFailure { log(getString(R.string.device_operation_failed, it.message.orEmpty())) }
            }
        }
    }

    private fun submitAiAction(action: () -> Unit) {
        // 同步 Binder 请求和停止在同一工作线程执行；回调日志切回主线程。
        // Serialize synchronous Binder requests and cleanup off the UI thread; post callback logs to the main thread.
        worker.execute {
            if (pageResumed) runCatching(action).onFailure(::logAiError)
        }
    }

    private fun startAudioCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            startAudioAfterPermissionGranted = true
            log(getString(R.string.device_audio_permission_required))
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO_PERMISSION)
            return
        }
        worker.execute {
            if (pageResumed) runCatching { audioCapture.value.start() }
                .onFailure { log(getString(R.string.device_operation_failed, it.message.orEmpty())) }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO_PERMISSION) return
        val requested = startAudioAfterPermissionGranted
        startAudioAfterPermissionGranted = false
        if (!requested) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (pageResumed) startAudioCapture()
            else startAudioOnResume = true
        } else log(getString(R.string.device_audio_permission_denied))
    }

    private fun startAi() {
        val service = GlassSdk.getGlassAiChatService() ?: run {
            log(getString(R.string.device_ai_service_unavailable))
            return
        }
        val request = Any()
        chatRequest.set(request)
        ownsChat = true
        log(getString(R.string.demo_starting_ai_chat))
        try {
            service.startAiChat(false)
            service.toAiChat(getString(R.string.demo_will_it_rain_in_hangzhou_tomorrow), aiCallback(request))
        } catch (error: Exception) {
            runCatching { stopAi() }.onFailure(::logAiError)
            throw error
        }
    }

    private fun stopAi() {
        chatRequest.set(null)
        // 请求失败不代表 startAiChat 开启的会话已经结束；仍需显式 endAiChat。
        // A failed request does not end a session opened by startAiChat; endAiChat is still required.
        if (!ownsChat) return
        GlassSdk.getGlassAiChatService()?.endAiChat()
        ownsChat = false
    }

    private fun aiCallback(request: Any) = object : AiChatListener.Stub() {
        override fun onContinuousModeUpdate(continuousMode: Boolean, timeout: Long, keepSessionActive: Boolean) {
            if (!pageResumed || chatRequest.get() !== request) return
            log(getString(R.string.demo_oncontinuousmodeupdate_continuousmode_timeout_keepsessionactive_status_17,
                continuousMode, timeout, keepSessionActive))
        }

        override fun onAiChatAnswer(answer: String?, isFinish: Boolean, contentType: String?, sessionId: String?) {
            if (!pageResumed || chatRequest.get() !== request) return
            log(getString(R.string.demo_onaichatanswer_answer_isfinish_contenttype_sessionid_status_18,
                answer, isFinish, contentType, sessionId))
        }

        override fun onError(code: Int, message: String) {
            if (pageResumed && chatRequest.compareAndSet(request, null)) {
                log(getString(R.string.demo_aichat_onerror_code_message_status_19, code, message))
            }
        }

        override fun onAiTakePhoto(filePath: String) {
            if (!pageResumed || chatRequest.get() !== request) return
            log(getString(R.string.demo_onaitakephoto_filepath_status_20, filePath))
        }
    }

    private fun registerLeqi() {
        try {
            GlassSdk.registerLeqiInterceptor(this, leqiInterceptor)
            log(getString(R.string.device_leqi_register_returned))
        } catch (error: Exception) {
            log(getString(R.string.device_leqi_error, getString(R.string.device_leqi_register),
                error.message ?: error.javaClass.simpleName))
        }
    }

    private fun sendCusEvent() {
        val service = GlassSdk.getGlassDeviceService() ?: run {
            log(getString(R.string.demo_device_service_unavailable_check_the_glasses_system_service))
            return
        }
        val eventCode = 12345
        val extra = "glassdemo"
        runCatching {
            service.sendCusEvent(eventCode, extra)
            Log.d(TAG, "sendCusEvent code=$eventCode extra=$extra returned")
            log(getString(R.string.device_cus_event_sent, eventCode, extra))
        }.onFailure { error ->
            Log.e(TAG, "sendCusEvent failed: ${error.message}", error)
            log(getString(R.string.device_cus_event_failed, error.message ?: error.javaClass.simpleName))
        }
    }

    override fun onResume() {
        super.onResume()
        pageResumed = true
        binding.root.post {
            if (pageResumed && !isFinishing && !isDestroyed) {
                runCatching { startService(Intent(this, CusEventReceiverService::class.java)) }
                    .onFailure { Log.w(TAG, "Unable to start custom event receiver", it) }
            }
        }
        if (startAudioOnResume) {
            startAudioOnResume = false
            startAudioCapture()
        }
    }

    override fun onPause() {
        pageResumed = false
        stopService(Intent(this, CusEventReceiverService::class.java))
        chatRequest.set(null)
        worker.execute { runCatching { stopAi() }.onFailure(::logAiError) }
        worker.execute {
            if (audioCapture.isInitialized()) {
                runCatching { audioCapture.value.stop() }
                    .onFailure { log(getString(R.string.device_operation_failed, it.message.orEmpty())) }
            }
        }
        super.onPause()
    }

    private fun configureAppVisibility() {
        if (appVisibilityPending) {
            log(getString(R.string.device_app_visibility_pending))
            return
        }
        val apply = hideApps
        val action = getString(if (apply) R.string.demo_app_display_settings else R.string.device_cancel_app_visibility)
        // 隐藏列表是完整列表，不是增量：不在列表里的内置应用会恢复显示；空配置恢复全部默认。
        // 指定三方应用时，SDK 将其排在 Launcher 前面。
        // The hide list is complete rather than incremental: built-in apps missing from it become
        // visible again, and an empty configuration restores every default. Given third-party apps
        // are placed at the front of the Launcher.
        //
        // SCAN / SETTINGS / DUAL_SYSTEM 是后加入的三项，需要眼镜系统镜像里的 system-server 与
        // Launcher 一并支持；旧镜像可能受理成功但界面不生效，详见 DeviceTools 说明。
        // SCAN / SETTINGS / DUAL_SYSTEM were added later and require matching system-server and
        // Launcher support in the glasses system image; older images may accept the request without
        // changing the Launcher, so verify on the device.
        val hideList = mutableListOf(
            GlassAppType.AI_WORK_ASSISTANT, GlassAppType.AI_CHAT,
            GlassAppType.AI_INSPECTION, GlassAppType.OFFLINE_FACE,
            GlassAppType.TAKE_PHOTO, GlassAppType.XPERT,
            GlassAppType.OFFLINE_PLATE, GlassAppType.HG_IDENTIFICATION,
            GlassAppType.SCAN, GlassAppType.SETTINGS, GlassAppType.DUAL_SYSTEM)
        val config = if (apply) GlassAppConfig(
            hideList,
            mutableListOf(ThirdPartyApp("com.rokid.glesse", "glassdemo"))
        ) else GlassAppConfig()
        if (apply) {
            log(getString(R.string.device_app_visibility_targets, hideList.joinToString(", ") { it.name }))
        }
        val service = GlassSdk.getGlassDeviceService() ?: run {
            log(getString(R.string.demo_device_service_unavailable_check_the_glasses_system_service))
            return
        }
        appVisibilityPending = true
        try {
            service.configureAppVisibility(config, object : IAppVisibilityListener.Stub() {
                override fun onResult(success: Boolean) {
                    if (success) hideApps = !apply
                    appVisibilityPending = false
                    log(getString(R.string.device_app_visibility_result, action,
                        getString(if (success) R.string.demo_success else R.string.demo_unknown_error)))
                    runOnUiThread {
                        if (!closed && !isDestroyed) binding.btConfigureAppVisibility.setText(
                            if (hideApps) R.string.demo_app_display_settings else R.string.device_cancel_app_visibility)
                    }
                }
            })
        } catch (error: Exception) {
            appVisibilityPending = false
            throw error
        }
    }

    private fun submitDeviceAction(action: () -> Unit) {
        // 同步 Binder 请求在串行工作线程执行，回调日志切回主线程。
        // Run synchronous Binder calls serially off the UI thread; display callback logs on the main thread.
        worker.execute {
            if (closed) return@execute
            try {
                action()
            } catch (error: Exception) {
                log(getString(R.string.device_operation_failed, error.message ?: error.javaClass.simpleName))
            }
        }
    }

    /**
     * 连接指定 Wi-Fi。
     * Connect to the specified Wi-Fi network.
     *
     * 修改 companion object 中的 DEMO_WIFI_SSID / DEMO_WIFI_PASSWORD 即可切换示例热点。
     * Change DEMO_WIFI_SSID / DEMO_WIFI_PASSWORD in the companion object to switch the sample access point.
     * 空密码会按开放网络处理；非空密码默认使用自动 PSK 模式。
     * An empty password selects an open network; a non-empty password uses automatic PSK mode by default.
     */
    private fun connectWifi() {
        if (!checkDeviceServiceReady()) {
            return
        }

        val ssid = DEMO_WIFI_SSID.trim()
        if (ssid.isBlank()) {
            log(demoString(R.string.demo_configure_the_wi_fi_network_name_first))
            return
        }

        val currentWifiInfo = GlassSdk.getCurrentWifiInfo()
        if (currentWifiInfo?.status == WifiNetworkInfo.STATUS_CURRENT &&
            !currentWifiInfo.ssid.isNullOrBlank() && currentWifiInfo.ssid == ssid
        ) {
            log(demoString(R.string.demo_already_connected_to_wi_fi, currentWifiInfo.ssid))
            return
        }

        val password = DEMO_WIFI_PASSWORD.takeIf { it.isNotBlank() }
        val request = WifiConnectRequest(
            ssid = ssid,
            securityType = if (password == null) {
                WifiConnectRequest.SECURITY_OPEN
            } else {
                WifiConnectRequest.SECURITY_WPA_PSK
            },
            password = password,
            hiddenSsid = DEMO_WIFI_HIDDEN
        )
        log(demoString(R.string.demo_connecting_to_wi_fi, ssid))
        GlassSdk.connectWifi(request, createWifiOperationCallback(demoString(R.string.demo_connect_wi_fi)))
    }

    /**
     * 断开当前 Wi-Fi。
     * Disconnect from the current Wi-Fi network.
     *
     * SDK 当前提供的是 removeWifi 接口，这里传 disconnectIfCurrent=true，
     * The SDK currently provides removeWifi; pass disconnectIfCurrent=true here
     * 会先断开当前连接，再移除对应的已保存配置。
     * to disconnect the current connection first, then remove its saved configuration.
     */
    private fun disconnectWifi() {
        if (!checkDeviceServiceReady()) {
            return
        }

        val currentWifiInfo = GlassSdk.getCurrentWifiInfo()
        if (currentWifiInfo?.ssid.isNullOrBlank()) {
            log(demoString(R.string.demo_no_wi_fi_network_connected_or_connecting))
            return
        }

        val request = WifiRemoveRequest(
            ssid = currentWifiInfo.ssid,
            bssid = currentWifiInfo.bssid,
            networkId = currentWifiInfo.networkId,
            disconnectIfCurrent = true
        )
        log(demoString(R.string.demo_disconnecting_and_removing_wi_fi, currentWifiInfo.ssid))
        GlassSdk.removeWifi(request, createWifiOperationCallback(demoString(R.string.demo_disconnect_wi_fi)))
    }

    private fun checkDeviceServiceReady(): Boolean {
        if (!GlassSdk.isReady()) {
            log(demoString(R.string.demo_sdk_initialization_is_incomplete_try_again_later))
            return false
        }
        if (GlassSdk.getGlassDeviceService() == null) {
            log(demoString(R.string.demo_device_service_unavailable_check_the_glasses_system_service))
            return false
        }
        return true
    }

    private fun createWifiOperationCallback(operationName: String) = object : IWifiOperationCallback.Stub() {
        override fun onProgress(stage: Int, message: String?) {
            val stageName = wifiStageName(stage)
            val detail = message.orEmpty().takeIf { it.isNotBlank() && it != stageName }
            log(if (detail == null) demoString(R.string.demo_progress, operationName, stageName) else demoString(R.string.demo_progress_2, operationName, stageName, detail))
        }

        override fun onResult(code: Int, message: String?, networkId: Int, ssid: String?) {
            if (code == WifiOperationCode.SUCCESS) {
                log(demoString(R.string.demo_succeeded_ssid_network_id, operationName, ssid.orEmpty(), networkId))
            } else {
                log(demoString(R.string.demo_failed_code_message, operationName, code, wifiCodeName(code), message.orEmpty()))
            }
        }
    }

    private fun wifiStageName(stage: Int): String {
        return when (stage) {
            WifiOperationStage.VALIDATING -> demoString(R.string.demo_validate_parameters)
            WifiOperationStage.CONFIGURING -> demoString(R.string.demo_configure_network)
            WifiOperationStage.CONNECTING -> demoString(R.string.demo_wait_for_connection)
            WifiOperationStage.VERIFYING -> demoString(R.string.demo_verify_connection)
            WifiOperationStage.REMOVING -> demoString(R.string.demo_remove_configuration)
            else -> demoString(R.string.demo_unknown_stage)
        }
    }

    private fun wifiCodeName(code: Int): String {
        return when (code) {
            WifiOperationCode.SUCCESS -> demoString(R.string.demo_success)
            WifiOperationCode.ERROR_INVALID_ARGUMENT -> demoString(R.string.demo_invalid_parameters)
            WifiOperationCode.ERROR_PERMISSION_DENIED -> demoString(R.string.demo_permission_denied)
            WifiOperationCode.ERROR_UNSUPPORTED_SECURITY -> demoString(R.string.demo_unsupported_security_type)
            WifiOperationCode.ERROR_WIFI_DISABLED -> demoString(R.string.demo_wi_fi_is_off)
            WifiOperationCode.ERROR_ADD_OR_UPDATE_FAILED -> demoString(R.string.demo_configuration_failed)
            WifiOperationCode.ERROR_AUTH_FAILED -> demoString(R.string.demo_authentication_failed)
            WifiOperationCode.ERROR_TIMEOUT -> demoString(R.string.demo_connection_timed_out)
            WifiOperationCode.ERROR_NOT_FOUND -> demoString(R.string.demo_configuration_not_found)
            WifiOperationCode.ERROR_INTERNAL -> demoString(R.string.demo_internal_error)
            else -> demoString(R.string.demo_unknown_error)
        }
    }

    private fun logAiError(error: Throwable) {
        log(getString(R.string.device_ai_operation_failed, error.message ?: error.javaClass.simpleName))
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        runOnUiThread {
            if (closed || isDestroyed) return@runOnUiThread
            if (logBuilder.length > 4000) logBuilder.clear()
            logBuilder.insert(0, "$message\n")
            binding.tvLog.text = logBuilder.toString()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::buttons.isInitialized) {
            // 窗口就绪后退出触摸模式，避免第一次镜腿滑动只恢复焦点。
            // Leave touch mode once the window is ready so the first glasses swipe also changes selection.
            buttons[selected].requestFocusFromTouch()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) return super.dispatchTouchEvent(null)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                touchSwipeConsumed = false
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val dx = event.x - touchStartX
                val dy = event.y - touchStartY
                val threshold = 24f * resources.displayMetrics.density
                if (!touchSwipeConsumed && abs(dy) >= threshold && abs(dy) > abs(dx)) {
                    touchSwipeConsumed = true
                    touchSwipeDirection = if (dy > 0f) GlassKeyEvent.KEYCODE_FRONT else GlassKeyEvent.KEYCODE_BEHIND
                    // Cancel the child's press before moving selection so a swipe cannot click a button.
                    // 移动选中项前取消子控件按压，避免滑动同时点击按钮。
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
                if (touchSwipeConsumed) {
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        GlassKeyEvent(touchSwipeDirection)
                    }
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> touchSwipeConsumed = false
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        when (keyEvent) {
            GlassKeyEvent.KEYCODE_FRONT -> select((selected + 1) % buttons.size)
            GlassKeyEvent.KEYCODE_BEHIND -> select((selected - 1 + buttons.size) % buttons.size)
            GlassKeyEvent.KEYCODE_CLICK -> buttons[selected].performClick()
            GlassKeyEvent.KEYCODE_BACK, GlassKeyEvent.KEYCODE_DOUBLE_CLICK -> finish()
            else -> return super.onGlassKeyEvent(keyEvent)
        }
        return true
    }

    private fun select(index: Int) {
        selected = index
        buttons.forEachIndexed { position, button ->
            val focused = position == index
            button.isSelected = focused
            button.isFocusable = focused
            button.isFocusableInTouchMode = focused
            button.setTextColor(ContextCompat.getColor(this, if (focused) R.color.green else R.color.green_70))
            button.setBackgroundResource(if (focused) R.drawable.round_select_bg else R.drawable.round_unselect_bg)
        }
        buttons[index].apply {
            requestFocusFromTouch()
            post {
                if (isShown && buttons[selected] === this) {
                    requestRectangleOnScreen(Rect(0, 0, width, height), true)
                }
            }
        }
    }

    override fun onDestroy() {
        closed = true
        // 保留已排队的停止操作；其他排队任务通过 closed 标记退出。
        // Drain queued session cleanup; other queued tasks exit through the closed flag.
        worker.shutdown()
        super.onDestroy()
    }
}
