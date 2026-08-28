package com.rokid.phone.ui.classicbt.repository

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.util.Log
import com.rokid.phone.MyApplication
import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.security.phone.sdk.api.bluetooth.classic.listener.IClassicBTClientListener

import com.rokid.phone.ui.classicbt.model.BluetoothDeviceInfo
import kotlinx.coroutines.flow.MutableSharedFlow

/** 封装经典蓝牙扫描、连接和消息发送操作。 */
class ClassicBtRepository(private val pSecuritySDK: PSecuritySDK) {

    private val TAG = "ClassicBtRepository"
    private var linkAddress = ""

    private val _foundDevices = mutableListOf<BluetoothDeviceInfo>()
    val _events = MutableSharedFlow<BluetoothEvent>(
        replay = 1, // 重放最近的一个事件给新的收集器
        extraBufferCapacity = 10
    )
    fun addListener() {
        pSecuritySDK.getClassicBlueToothClientService()?.apply {
            addClientListener(listener)
        }
    }

    fun startScan(timeout: Long) {
        _foundDevices.clear() // 清空以备下次扫描
        pSecuritySDK.getClassicBlueToothClientService()?.startScan(timeout)
    }

    fun stopScan() {
        pSecuritySDK.getClassicBlueToothClientService()?.stopScan()
    }


    fun disconnect() {
        pSecuritySDK.getClassicBlueToothClientService()?.disconnect()
    }

    fun sendMessageBt(msg: String) {
        pSecuritySDK.getMessageService()?.sendTextMessageByClassicBT(msg, MyApplication.MainAppClientId)
    }

    fun connectBt(deviceInfo: BluetoothDeviceInfo) {
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(deviceInfo.address)
        if (device != null) {
            pSecuritySDK.getClassicBlueToothClientService()?.connectToServer(device) {
                Log.i(TAG, "connectBt: $it")
                _events.tryEmit(BluetoothEvent.ConnectResult(it))
            }
            linkAddress = deviceInfo.address
        } else {
            Log.e(TAG, "connectBt:  device = null")
        }
    }

    fun btDeviceFilter(deviceName: String?): Boolean {
        if (deviceName.isNullOrEmpty() || "null".equals(deviceName.lowercase())) {
            return false
        }
        // 如需限制可发现设备，可在此处按设备名称实现过滤规则。
//        if(deviceName.contains("Rokid")
//            || deviceName.contains("P30")
//            || deviceName.contains("HUAWEI")
//            || deviceName.contains("Mate")
//            || deviceName.contains("OPPO")
//            || deviceName.contains("VIVO")
//            || deviceName.contains("P40")
//            || deviceName.contains("Honor")
//            || deviceName.contains("Redmi")
//            || deviceName.contains("OnePlus")
//            || deviceName.contains("Bolon")
//            || deviceName.contains("Glass3")
//            || deviceName.contains("Glasses")
//        ){
//            return true
//        }
        //正式版本只展示Glass3 开头的蓝牙设备
        if (deviceName.contains("Glass3")) {
            return true
        }
        return false
    }

    private val listener = object : IClassicBTClientListener {
        override fun onDeviceFound(device: BluetoothDevice) {
            val name = device.name
            val address = device.address
            val type = device.type
            Log.d(TAG, "onDeviceFound ----  ${name} --- ${address} ---- ${type}")
//            Log.d(TAG, "onDeviceFound " + _foundDevices.size)
            if (btDeviceFilter(name) && type != BluetoothDevice.DEVICE_TYPE_LE) {
                val bluetoothDeviceInfo = BluetoothDeviceInfo(name, address, type)
                _foundDevices.add(bluetoothDeviceInfo)
                val emitted = _events.tryEmit(BluetoothEvent.DeviceFound(ArrayList(_foundDevices)))
                Log.d(TAG, "Event emitted: ${_foundDevices.size} 个BT设备, success: $emitted")
            }
        }

        override fun onScanFinished() {
            // 扫描完成时发送累积的设备列表副本
            Log.d(TAG, "ScanFinished " + _foundDevices.size)
            _events.tryEmit(BluetoothEvent.ScanFinished(ArrayList(_foundDevices)))

        }

        override fun onConnect(success: Boolean) {
            Log.d(TAG, "IClassicBTClientListener onConnect-->$success")

        }

        override fun onConnectionRejected(reason: String, code: Int) {
            Log.e(TAG, "onConnectionRejected reason = $reason, code = $code")
        }
    }


    sealed class BluetoothEvent {
        data class DeviceFound(val devices: List<BluetoothDeviceInfo>) : BluetoothEvent()
        data class ScanFinished(val devices: List<BluetoothDeviceInfo>) : BluetoothEvent()
        data class ConnectResult(val connect: Boolean) : BluetoothEvent()
    }
}
