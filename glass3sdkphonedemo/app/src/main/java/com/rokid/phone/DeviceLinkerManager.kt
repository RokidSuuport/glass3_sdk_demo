package com.rokid.phone

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.blankj.utilcode.util.ToastUtils
import com.rokid.phone.data.Config
import com.google.gson.Gson
import com.rokid.phone.ui.classicbt.model.BluetoothDeviceInfo
import com.rokid.phone.data.CustomMessage
import com.rokid.phone.data.GlobalData
import com.rokid.phone.data.GlobalEvent
import com.rokid.phone.utils.ProjectBusinessType
import com.rokid.phone.utils.RKSystemInfo
import com.rokid.phone.utils.SPUtil
import com.rokid.phone.utils.SpKeyConstant
import com.rokid.phone.utils.SystemGlobalConstant
import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.security.phone.sdk.api.bluetooth.classic.listener.IClassicBTClientListener
import com.rokid.security.phone.sdk.api.msg.listener.IMessageListener
import com.rokid.security.phone.sdk.api.wifip2p.listener.IWifiP2PClientListener
import com.rokid.security.phone.sdk.base.utils.log.L
import com.rokid.security.phone.sdk.base.utils.other.defaultScope
import com.rokid.security.phone.sdk.base.utils.other.ktx.call
import com.rokid.security.phone.sdk.base.utils.other.ktx.collect
import com.rokid.security.phone.sdk.base.utils.other.mainScope
import com.rokid.security.phone.sdk.base.utils.other.workScope


import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Author: zhangshengwei
 * Date: 2025/6/24
 */
object DeviceLinkerManager {

    const val TAG = "DeviceLinkerManager"
    private const val AUDIO_TAG = "AUDIO_TAG"
    private const val AUDIO_STREAM_START = "AUDIO_STREAM_START"
    private const val AUDIO_STREAM_STOP = "AUDIO_STREAM_STOP"
    var mWifiP2pDevice: WifiP2pDevice? = null

    //上一次连接的设备状态
    var mBluetoothDevice: BluetoothDeviceInfo? = null
    var mGetGlassSystemInfoMsgTask: Job? = null
    var mGson = Gson()
    var DefaultName = "Rokid Glass3"
    private val systemCallSet: HashSet<() -> Unit> = HashSet()

    @Volatile
    private var isSerialConnecting = false

    @Volatile
    private var isAudioStreamRequested = false

    //正在连接的蓝牙设备信息，连接完成后会与mBluetoothDevice一致
    var mConnectingBluetoothDevice: BluetoothDeviceInfo? = null

    private val mIClassicBTClientListener = object : IClassicBTClientListener {
        @SuppressLint("MissingPermission")
        override fun onDeviceFound(device: BluetoothDevice) {
            Log.d(TAG, "发现蓝牙设备：${device.name}")
        }

        override fun onScanFinished() {
        }

        override fun onConnect(success: Boolean) {
            if (success) {
                Log.d(TAG, "onConnect方法蓝牙连接成功")
                mConnectingBluetoothDevice?.let {
                    saveBlueToothDeviceInfo(it)
                    getSystemMsgTask()
                }
            } else {
                Log.d(TAG, "onConnect方法蓝牙连接失败")
                GlobalEvent.autoConnectionEvent.call(workScope)
            }
            GlobalData.setBtConnectState(success)
            if (success && isSerialConnecting) {
                // 只有在 connectDevices() 串行流程里，才会继续连接 Wi-Fi P2P
                mWifiP2pDevice?.let { device ->
                    if (!GlobalData.p2pConnectState.value) {
                        wifiConnect(device)
                    }
                }
                isSerialConnecting = false // 完成一次串行流程，清理标记
            }
        }

        override fun onConnectionRejected(reason: String, code: Int) {
            Log.i(TAG, "BT onConnectionRejected:  $reason")
            ToastUtils.showShort(reason)
            GlobalEvent.connectionRejectedEvent.call(workScope)
        }
    }

    private val mIWifiP2PClientListener2 = object : IWifiP2PClientListener {
        override fun onWifiP2pEnabled(enabled: Boolean) {
            Log.i(TAG, "p2p onWifiP2pEnabled:  $enabled")
            GlobalData.setP2pConnectState(enabled)
        }
    }

    fun initObserver() {
        GlobalData.sdkInitState.collect(defaultScope) {
            if (it) {
                PSecuritySDK.getClassicBlueToothClientService()?.addClientListener(mIClassicBTClientListener)
                PSecuritySDK.getWifiP2PClientService()?.addWifiP2PClientListener(mIWifiP2PClientListener2)
            } else {
                Log.e(TAG, "initObserver: sdkInitState $it")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun getDeviceName(): String {
        if (GlobalData.btConnectState.value) {
            val name = SPUtil.getInstance(MyApplication.instance.baseContext).getString(SpKeyConstant.BluetoothDevice_NAME_Key)
            return name
        }
        if (mBluetoothDevice == null) {
            return DefaultName
        }
        val name = SPUtil.getInstance(MyApplication.instance.baseContext).getString(SpKeyConstant.BluetoothDevice_NAME_Key)
        return name
    }

    fun saveP2pDevice(device: WifiP2pDevice) {
        mWifiP2pDevice = device
        var json = Gson().toJson(device)
        Log.d(TAG, "saveP2pDevice" + json)
        SPUtil.getInstance(MyApplication.instance.baseContext).putString(SpKeyConstant.WifiP2pDevice_key, json)
    }

    fun saveBlueToothDeviceInfo(device: BluetoothDeviceInfo) {
        val json = Gson().toJson(device)
        mBluetoothDevice = device
        Log.d(TAG, "保存蓝牙设备信息：" + json + " " + device.name + " " + device.address)
        SPUtil.getInstance(MyApplication.instance.baseContext).putString(SpKeyConstant.BluetoothDevice_AD_Key, device.address)
        SPUtil.getInstance(MyApplication.instance.baseContext).putString(SpKeyConstant.BluetoothDevice_NAME_Key, device.name)
    }

    fun getP2pDevice(): WifiP2pDevice? {
        val json = SPUtil.getInstance(MyApplication.instance.baseContext).getString(SpKeyConstant.WifiP2pDevice_key)
        val wifiP2pDevice = Gson().fromJson(json, WifiP2pDevice::class.java)
        Log.d(TAG, "getP2pDevice" + json)
        return wifiP2pDevice
    }

    @SuppressLint("MissingPermission")
    fun getBlueToothDevice(): BluetoothDeviceInfo? {
        val address = SPUtil.getInstance(MyApplication.instance.baseContext).getString(SpKeyConstant.BluetoothDevice_AD_Key)
        val name = SPUtil.getInstance(MyApplication.instance.baseContext).getString(SpKeyConstant.BluetoothDevice_NAME_Key)
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (address.isNotEmpty()) {
            val device: BluetoothDevice = bluetoothAdapter.getRemoteDevice(address)
            return BluetoothDeviceInfo(name, address, device.type)
        }
        return null
    }

    private var findLastDevice: Boolean = false
    private var isNeedAutoConnect = false
    private var mIWifiP2PClientListener: IWifiP2PClientListener? = null
    private var p2pConnectJob: Job? = null

    fun connectBt(bluetoothDevice: BluetoothDeviceInfo, action: (isConnect: Boolean) -> Unit) {
        // 检查设备是否为空
        mBluetoothDevice = bluetoothDevice
        if (GlobalData.btConnectState.value) {
            return
        }
        val bluetoothDevice = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(bluetoothDevice.address)
        if (bluetoothDevice != null) {
            PSecuritySDK.getClassicBlueToothClientService()?.connectToServer(bluetoothDevice) {
                if (it) {
                    Log.d(TAG, "----------蓝牙连接成功")
                    GlobalData.setBtConnectState(true)
                } else {
                    Log.d(TAG, "----------蓝牙连接失败")
                    action(false)
                }
            }
        } else {
            Log.e(TAG, "bluetoothConnect: bluetoothDevice == null")
        }
    }

    fun connectP2p(wifiP2pDevice: WifiP2pDevice?) {
        if (wifiP2pDevice != null) {
            mWifiP2pDevice = wifiP2pDevice
        }
        if (!GlobalData.p2pConnectState.value) {
            wifiConnect(wifiP2pDevice)
        }
    }

    private fun wifiConnect(wifiP2pDevice: WifiP2pDevice?) {
        val targetDeviceName = mConnectingBluetoothDevice?.name
            ?: mBluetoothDevice?.name
            ?: wifiP2pDevice?.deviceName
        if (targetDeviceName.isNullOrBlank()) {
            Log.e(TAG, "wifiConnect: 缺少目标设备名，无法发现对应的 P2P 设备")
            return
        }

        Log.d(TAG, "wifiConnect: targetName=$targetDeviceName, cachedDevice=$wifiP2pDevice")
        // Wi-Fi P2P 连接
        val wifiP2PClientService = PSecuritySDK.getWifiP2PClientService()
        if (wifiP2PClientService == null) {
            Log.e(TAG, "wifiConnect: WifiP2PClientService 尚未初始化")
            return
        }

        p2pConnectJob?.cancel()
        mIWifiP2PClientListener?.let { wifiP2PClientService.removeWifiP2PClientListener(it) }
        findLastDevice = false
        isNeedAutoConnect = false
        mIWifiP2PClientListener = object : IWifiP2PClientListener {
            override fun onWifiP2pEnabled(enabled: Boolean) {
                Log.i(TAG, "onWifiP2pEnabled: $enabled")
                if (enabled) {
                    p2pConnectJob?.cancel()
                    mIWifiP2PClientListener?.let {
                        wifiP2PClientService.removeWifiP2PClientListener(it)
                    }
                }
            }

            override fun onPeersAvailable(devices: List<WifiP2pDevice>) {
                if (!findLastDevice && isNeedAutoConnect) {
                    Log.d(TAG, "wifiConnect: discovered=${devices.map { "${it.deviceName}/${it.deviceAddress}" }}")
                    val device = devices.firstOrNull {
                        it.deviceName == targetDeviceName &&
                                it.deviceAddress == wifiP2pDevice?.deviceAddress
                    } ?: devices.firstOrNull {
                        it.deviceName == targetDeviceName
                    }
                    if (device != null) {
                        findLastDevice = true
                        isNeedAutoConnect = false
                        mWifiP2pDevice = device
                        wifiP2PClientService.connectDevice(device) { result ->
                            Log.i(TAG, "wifiConnect: connectDevice result=$result")
                        }
                    }
                }
            }
        }
        wifiP2PClientService.addWifiP2PClientListener(mIWifiP2PClientListener!!)

        p2pConnectJob = mainScope.launch {
            wifiP2PClientService.disconnect()
            delay(500)
            wifiP2PClientService.initialize { result ->
                if (result.isSuccess) {
                    Log.i(TAG, "wifiConnect: initialize success")
                    isNeedAutoConnect = true
                    wifiP2PClientService.startDiscoverPeers {
                        Log.i(TAG, "wifiConnect: startDiscoverPeers success=${it.isSuccess}")
                    }
                } else {
                    Log.e(TAG, "wifiConnect: initialize failed: $result")
                }
            }
            delay(20_000)
            if (!GlobalData.p2pConnectState.value) {
                Log.e(TAG, "wifiConnect: 20 秒内未连接到 $targetDeviceName")
                isNeedAutoConnect = false
                wifiP2PClientService.stopPeerDiscovery()
                mIWifiP2PClientListener?.let {
                    wifiP2PClientService.removeWifiP2PClientListener(it)
                }
            }
        }
    }


    fun release() {
        stopAudioStream()
        closeSystemMsgTask()
        systemCallSet.clear()
        mBluetoothDevice = null
        mWifiP2pDevice = null
        p2pConnectJob?.cancel()
        if (hasBluetoothScanPermission()) {
            try {
                PSecuritySDK.getClassicBlueToothClientService()?.removeClientListener(mIClassicBTClientListener)
                PSecuritySDK.getMessageService()?.removeMessageListener(messageListener)
                mIWifiP2PClientListener?.apply {
                    PSecuritySDK.getWifiP2PClientService()?.removeWifiP2PClientListener(this)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "release: remove bluetooth listener failed because scan permission is missing", e)
            }
        } else {
            Log.w(TAG, "release: skip remove bluetooth listener because BLUETOOTH_SCAN is missing")
        }
    }

    private fun hasBluetoothScanPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(
                    MyApplication.instance.baseContext,
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
    }

    fun addSystemInfoListener(systemCallback: (() -> Unit)) {
        systemCallSet.add(systemCallback)
    }

    fun addMessageListener() {
        PSecuritySDK.getMessageService()?.addMessageListener(messageListener)
    }

    private val messageListener = object : IMessageListener {
        override fun onClassicBTTextMessage(msg: String, clientId: String) {
            if (handleAudioStreamControl(msg)) return
            try {
//                Log.d(TAG,"--------处理前msg=$msg,clientId=$clientId")
                // 普通蓝牙文本不是系统业务消息，解析不到 CustomMessage 时直接忽略。
                val customMessage = CustomMessage.fromClassicBtPayload(mGson, msg) ?: return
//                Log.d(TAG, "---------处理后msg=${customMessage.message},type=${customMessage.type}")
                if (customMessage.type == ProjectBusinessType.SYSTEM_INFO_RESPONSE) {
                    val systemInfo = mGson.fromJson(customMessage.message, RKSystemInfo::class.java)
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
                        for (function in systemCallSet) {
                            function.invoke()
                        }
                    }
                } else if (customMessage.type == ProjectBusinessType.POWER_UPDATE) {
                    val systemInfo = mGson.fromJson(customMessage.message, RKSystemInfo::class.java)
                    Log.d(TAG, "-----眼镜电量信息: version = ${systemInfo.version}, msg = $msg")
                    if (systemInfo != null) {
                        SystemGlobalConstant.isCharge = systemInfo.isCharge
                        SystemGlobalConstant.powerValue = systemInfo.powerValue
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "onClassicBTTextMessage 解析异常: ${e.message}", e)
            }
        }
    }

    private fun handleAudioStreamControl(msg: String): Boolean {
        return when (msg) {
            AUDIO_STREAM_START -> {
                requestAudioStream()
                true
            }

            AUDIO_STREAM_STOP -> {
                stopAudioStream()
                true
            }

            else -> false
        }
    }

    private fun requestAudioStream() {
        if (isAudioStreamRequested) {
            Log.d(TAG, "requestAudioStream: audio stream is already requested")
            return
        }
        val device = PSecuritySDK.getAbsDeviceInfoService() ?: run {
            Log.e(TAG, "requestAudioStream: device service is not initialized")
            return
        }
        isAudioStreamRequested = true
        device.requestAudioStream(AUDIO_TAG) { isSuccess ->
            if (!isSuccess) isAudioStreamRequested = false
            Log.i(TAG, "requestAudioStream: success=$isSuccess")
        }
    }

    fun stopAudioStream() {
        if (!isAudioStreamRequested) return
        isAudioStreamRequested = false
        PSecuritySDK.getAbsDeviceInfoService()?.stopAudioStream(AUDIO_TAG) { isSuccess ->
            Log.i(TAG, "stopAudioStream: success=$isSuccess")
        }
    }

    private var isFirstGetSystemInfo = true
    fun getSystemMsgTask() {
        if (mGetGlassSystemInfoMsgTask == null) {
            mGetGlassSystemInfoMsgTask = workScope.launch {
                while (isActive) {
                    if (isFirstGetSystemInfo) {
                        delay(800)
                        isFirstGetSystemInfo = false
                    } else {
                        delay(1000 * 8)
                    }
                    Log.d(TAG, "----getSystemInfo")
                    getGlassSystemInfoMsg()
                }
            }
        }
    }

    fun closeSystemMsgTask() {
        mGetGlassSystemInfoMsgTask?.cancel("")
        mGetGlassSystemInfoMsgTask = null
    }


    fun getGlassSystemInfoMsg() {
        val customMessage = CustomMessage()
        customMessage.type = ProjectBusinessType.GET_SYSTEM_INFO
        val message = mGson.toJson(customMessage)
        PSecuritySDK.getMessageService()?.sendTextMessageByClassicBT(message, MyApplication.MainAppClientId)
    }

    fun removeSystemInfoListener(systemCallback: (() -> Unit)) {
        systemCallSet.remove(systemCallback)
    }

    fun getGlassPowerInfoMsg() {
        val customMessage = CustomMessage()
        customMessage.type = ProjectBusinessType.POWER_UPDATE
        val message = mGson.toJson(customMessage)
        PSecuritySDK.getMessageService()?.sendTextMessageByClassicBT(message, MyApplication.MainAppClientId)
    }

    fun setVolume(progress: Int = 0) {
        val customMessage = CustomMessage()
        customMessage.type = ProjectBusinessType.SET_VOLUME
        customMessage.message = progress.toString()
        val msg = mGson.toJson(customMessage)
        PSecuritySDK.getMessageService()?.sendTextMessageByClassicBT(msg, MyApplication.MainAppClientId)
    }

    fun setBrightness(progress: Int = 0) {
        val customMessage = CustomMessage()
        customMessage.type = ProjectBusinessType.SET_BRIGHTNESS
        customMessage.message = progress.toString()
        val msg = mGson.toJson(customMessage)
        PSecuritySDK.getMessageService()?.sendTextMessageByClassicBT(msg, MyApplication.MainAppClientId)
    }

    fun sendConfig(config: Config) {
        Log.d(TAG, "sendConfig ${config.envType}")
        val customMessage = CustomMessage()
        customMessage.type = ProjectBusinessType.SEND_CONFIG
        customMessage.message = mGson.toJson(config)
        val msg = mGson.toJson(customMessage)
        PSecuritySDK.getMessageService()?.sendTextMessageByClassicBT(msg, MyApplication.MainAppClientId)
    }

    fun getCustomWake() {
        val cusMsg = CustomMessage().apply {
            type = ProjectBusinessType.GET_CUSTOM_WAKE
        }
        PSecuritySDK.getMessageService()?.sendTextMessageByClassicBT(mGson.toJson(cusMsg), MyApplication.MainAppClientId)
    }

    fun setCustomWake(isChecked: Boolean) {
        val cusMsg = CustomMessage().apply {
            type = ProjectBusinessType.SET_CUSTOM_WAKE
            message = isChecked.toString()
        }
        PSecuritySDK.getMessageService()?.sendTextMessageByClassicBT(mGson.toJson(cusMsg), MyApplication.MainAppClientId)
    }

    fun setZoomCamera(level: Int) {
        PSecuritySDK.getMessageService()?.sendTextMessageByClassicBT(
            mGson.toJson(
                CustomMessage().apply {
                    type = ProjectBusinessType.SET_ZOOM_CAMERA
                    message = level.toString()
                }
            ), MyApplication.MainAppClientId
        )
    }


}
