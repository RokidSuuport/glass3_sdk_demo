package com.rokid.phone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.net.wifi.p2p.WifiP2pDevice
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.rokid.phone.ai.AiVisionRecord
import com.rokid.phone.ai.AiVisionRelayPayload
import com.rokid.phone.ai.AiVisionStore
import com.rokid.phone.data.CustomMessage
import com.rokid.phone.data.DeviceStatus
import com.rokid.phone.data.GlobalData
import com.rokid.phone.ui.classicbt.model.BluetoothDeviceInfo
import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.security.phone.sdk.api.bluetooth.classic.listener.IClassicBTClientListener
import com.rokid.security.phone.sdk.api.msg.listener.IMessageListener
import com.rokid.security.phone.sdk.api.wifip2p.listener.IWifiP2PClientListener
import com.rokid.security.phone.sdk.base.utils.other.workScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.os.Handler
import android.os.Looper
import com.rokid.phone.utils.RKSystemInfo
import com.rokid.phone.utils.SystemGlobalConstant
import com.rokid.phone.utils.SPUtil
import com.rokid.phone.utils.SpKeyConstant
import java.util.concurrent.CopyOnWriteArraySet
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 维护眼镜蓝牙连接，并接收眼镜发来的巡检结果和现场图片。
 */
object DeviceLinkerManager {
    private const val TAG = "DeviceLinkerManager"
    private const val PREFS = "hazard_inspection_device"
    private const val KEY_ADDRESS = "bluetooth_address"
    private const val KEY_NAME = "bluetooth_name"
    private const val DEFAULT_NAME = "Rokid Glass3"

    private val systemCallSet = CopyOnWriteArraySet<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun addSystemInfoListener(callback: () -> Unit) { systemCallSet.add(callback) }
    fun removeSystemInfoListener(callback: () -> Unit) { systemCallSet.remove(callback) }
    fun getGlassSystemInfoMsg() = refreshDeviceStatus()

    fun saveBlueToothDeviceInfo(device: BluetoothDeviceInfo) = saveDevice(device)

    fun saveP2pDevice(device: WifiP2pDevice) {
        SPUtil.getInstance(MyApplication.instance).putString(SpKeyConstant.WifiP2pDevice_key, gson.toJson(device))
    }

    private val gson = Gson()
    private val pendingResults = ConcurrentHashMap<String, AiVisionRelayPayload>()
    private val pendingImages = ConcurrentHashMap<String, ByteArray>()
    private var statusPollingJob: Job? = null

    @Volatile
    private var p2pConnecting = false

    @Volatile
    var mBluetoothDevice: BluetoothDeviceInfo? = null
        private set

    @Volatile
    var mConnectingBluetoothDevice: BluetoothDeviceInfo? = null

    private var initialized = false

    private val classicListener = object : IClassicBTClientListener {
        override fun onDeviceFound(device: BluetoothDevice) = Unit

        override fun onScanFinished() = Unit

        override fun onConnect(success: Boolean) {
            GlobalData.setBtConnectState(success)
            if (success) {
                mConnectingBluetoothDevice?.let(::saveDevice)
                requestSystemInfo()
                startStatusPolling()
                connectP2p()
            } else {
                stopStatusPolling()
            }
        }

        override fun onConnectionRejected(reason: String, code: Int) {
            Log.w(TAG, "Bluetooth connection rejected: $reason ($code)")
        }
    }

    private val messageListener = object : IMessageListener {
        override fun onClassicBTTextMessage(msg: String, clientId: String) {
            if (handleAiVisionResult(msg)) return
            handleSystemInfo(msg)
        }

        override fun onBTStreamDataReceived(tag: String, data: ByteArray, clientId: String) {
            handleAiVisionImage(tag, data)
        }
    }

    private val p2pListener = object : IWifiP2PClientListener {
        override fun onWifiP2pEnabled(enabled: Boolean) {
            GlobalData.setP2pConnectState(enabled)
        }
    }

    fun initialize() {
        if (initialized) return
        initialized = true
        PSecuritySDK.getClassicBlueToothClientService()?.addClientListener(classicListener)
        PSecuritySDK.getMessageService()?.addMessageListener(messageListener)
        PSecuritySDK.getWifiP2PClientService()?.addWifiP2PClientListener(p2pListener)
    }

    fun loadSavedDevice() {
        val prefs = MyApplication.instance.getSharedPreferences(PREFS, 0)
        val address = prefs.getString(KEY_ADDRESS, "").orEmpty()
        val name = prefs.getString(KEY_NAME, "").orEmpty()
        if (address.isBlank()) return
        val type = runCatching {
            BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address).type
        }.getOrDefault(BluetoothDevice.DEVICE_TYPE_CLASSIC)
        mBluetoothDevice = BluetoothDeviceInfo(name.ifBlank { DEFAULT_NAME }, address, type)
        mConnectingBluetoothDevice = mBluetoothDevice
    }

    fun getDeviceName(): String = mBluetoothDevice?.name ?: DEFAULT_NAME

    @SuppressLint("MissingPermission")
    fun connectBt(device: BluetoothDeviceInfo, action: (Boolean) -> Unit) {
        mConnectingBluetoothDevice = device
        if (GlobalData.btConnectState.value) return
        runCatching {
            val remote = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(device.address)
            PSecuritySDK.getClassicBlueToothClientService()?.connectToServer(remote) { success ->
                if (success) saveDevice(device)
                GlobalData.setBtConnectState(success)
                action(success)
            }
        }.onFailure {
            Log.e(TAG, "connectBt failed: ${it.message}", it)
            action(false)
        }
    }

    private fun saveDevice(device: BluetoothDeviceInfo) {
        mBluetoothDevice = device
        mConnectingBluetoothDevice = device
        MyApplication.instance.getSharedPreferences(PREFS, 0).edit()
            .putString(KEY_ADDRESS, device.address)
            .putString(KEY_NAME, device.name)
            .apply()
    }

    fun release() {
        runCatching {
            PSecuritySDK.getClassicBlueToothClientService()?.removeClientListener(classicListener)
        }.onFailure {
            Log.w(TAG, "remove Bluetooth listener failed: ${it.message}")
        }
        runCatching {
            PSecuritySDK.getMessageService()?.removeMessageListener(messageListener)
        }.onFailure {
            Log.w(TAG, "remove message listener failed: ${it.message}")
        }
        runCatching {
            PSecuritySDK.getWifiP2PClientService()?.removeWifiP2PClientListener(p2pListener)
        }.onFailure {
            Log.w(TAG, "remove P2P listener failed: ${it.message}")
        }
        stopStatusPolling()
        DeviceStatus.reset()
        initialized = false
        pendingResults.clear()
        pendingImages.clear()
    }

    fun connectP2p() {
        if (!GlobalData.btConnectState.value || p2pConnecting || GlobalData.p2pConnectState.value) return
        p2pConnecting = true
        val service = PSecuritySDK.getWifiP2PClientService()
        if (service == null) {
            p2pConnecting = false
            return
        }
        service.isConnect { connected ->
            if (connected) {
                GlobalData.setP2pConnectState(true)
                p2pConnecting = false
                return@isConnect
            }
            service.sendConnectP2pRequest { success ->
                p2pConnecting = false
                if (success) GlobalData.setP2pConnectState(true)
            }
        }
    }

    fun disconnectP2p() {
        p2pConnecting = false
        PSecuritySDK.getWifiP2PClientService()?.disconnect()
        GlobalData.setP2pConnectState(false)
    }

    fun disconnectBt() {
        disconnectP2p()
        PSecuritySDK.getClassicBlueToothClientService()?.disconnect()
        GlobalData.setBtConnectState(false)
    }

    fun refreshDeviceStatus() {
        if (GlobalData.btConnectState.value) requestSystemInfo()
    }

    private fun requestSystemInfo() {
        val message = CustomMessage().apply { type = MESSAGE_GET_SYSTEM_INFO }
        PSecuritySDK.getMessageService()?.sendTextMessageByClassicBT(
            gson.toJson(message),
            MyApplication.MainAppClientId,
        )
    }

    private fun startStatusPolling() {
        if (statusPollingJob?.isActive == true) return
        statusPollingJob = workScope.launch {
            while (isActive && GlobalData.btConnectState.value) {
                requestSystemInfo()
                delay(8_000L)
            }
        }
    }

    private fun stopStatusPolling() {
        statusPollingJob?.cancel()
        statusPollingJob = null
    }

    private fun handleSystemInfo(msg: String) {
        val custom = CustomMessage.fromClassicBtPayload(gson, msg) ?: return
        when (custom.type) {
            MESSAGE_SYSTEM_INFO_RESPONSE -> {
                DeviceStatus.updateFromJson(custom.message)
                val systemInfo = runCatching { gson.fromJson(custom.message, RKSystemInfo::class.java) }.getOrNull()
                if (systemInfo != null) {
                    SystemGlobalConstant.osType = systemInfo.osType
                    SystemGlobalConstant.cpuType = systemInfo.cpuType
                    SystemGlobalConstant.version = systemInfo.version
                    SystemGlobalConstant.isCharge = systemInfo.isCharge
                    SystemGlobalConstant.powerValue = systemInfo.powerValue
                    SystemGlobalConstant.brightness = systemInfo.brightness
                    SystemGlobalConstant.maxBrightness = systemInfo.maxBrightness
                    SystemGlobalConstant.isAutoBrightness = systemInfo.isAutoBrightness
                    SystemGlobalConstant.curVolume = systemInfo.curVolume
                    SystemGlobalConstant.maxVolume = systemInfo.maxVolume
                    SystemGlobalConstant.glassRingConnected = systemInfo.glassRingConnected
                    SystemGlobalConstant.glassRingBluetoothDeviceName = systemInfo.glassRingBluetoothDeviceName

                    SystemGlobalConstant.deviceTypeId = systemInfo.deviceTypeId
                    SystemGlobalConstant.deviceId = systemInfo.deviceId
                    SPUtil.getInstance(MyApplication.instance.baseContext)
                        .putString(SpKeyConstant.DEVICE_ID, SystemGlobalConstant.deviceId)
                    mainHandler.post { systemCallSet.forEach { it.invoke() } }
                }
            }
            MESSAGE_POWER_UPDATE -> {
                DeviceStatus.updateFromJson(custom.message, updatePowerOnly = true)
                SystemGlobalConstant.isCharge = DeviceStatus.charging.value
                SystemGlobalConstant.powerValue = DeviceStatus.powerValue.value
            }
        }
    }

    private fun handleAiVisionResult(msg: String): Boolean {
        val custom = CustomMessage.fromClassicBtPayload(gson, msg) ?: return false
        if (custom.type != AiVisionRelayPayload.MESSAGE_TYPE) return false
        val payload = runCatching {
            gson.fromJson(custom.message, AiVisionRelayPayload::class.java)
        }.getOrNull() ?: return true
        if (payload.questionId.isBlank()) return true
        pendingResults[payload.questionId] = payload
        pendingImages.remove(payload.questionId)?.let { saveResult(payload, it) }
        return true
    }

    private fun handleAiVisionImage(tag: String, data: ByteArray) {
        if (!tag.startsWith(AiVisionRelayPayload.IMAGE_TAG_PREFIX) || data.isEmpty()) return
        val questionId = tag.removePrefix(AiVisionRelayPayload.IMAGE_TAG_PREFIX)
        if (questionId.isBlank()) return
        val payload = pendingResults.remove(questionId)
        if (payload == null) {
            pendingImages[questionId] = data.copyOf()
        } else {
            saveResult(payload, data)
        }
    }

    private fun saveResult(payload: AiVisionRelayPayload, data: ByteArray) {
        workScope.launch {
            runCatching {
                val context = MyApplication.instance
                val directory = context.getExternalFilesDir("receiver") ?: error("receiver directory unavailable")
                directory.mkdirs()
                val file = File(directory, "image-${payload.timestamp}-${payload.questionId}.jpg")
                file.writeBytes(data)
                val imageUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                ).toString().replaceFirst("content:", "https:")
                val record = AiVisionRecord(
                    questionId = payload.questionId,
                    question = payload.question,
                    answer = payload.answer,
                    reportTitle = payload.reportTitle,
                    reportSummary = payload.reportSummary,
                    imageUrl = imageUri,
                    timestamp = payload.timestamp,
                    completed = true,
                )
                val records = AiVisionStore.load(context)
                    .filterNot { it.questionId == record.questionId }
                    .toMutableList()
                    .apply { add(record) }
                AiVisionStore.save(context, records)
                context.sendBroadcast(
                    Intent(AiVisionStore.ACTION_RECORDS_UPDATED).setPackage(context.packageName),
                )
            }.onFailure {
                Log.w(TAG, "save AI vision result failed: ${it.message}", it)
            }
        }
    }

    private const val MESSAGE_GET_SYSTEM_INFO = "GET_SYSTEM_INFO"
    private const val MESSAGE_SYSTEM_INFO_RESPONSE = "SYSTEM_INFO_RESPONSE"
    private const val MESSAGE_POWER_UPDATE = "POWER_UPDATE"
}
