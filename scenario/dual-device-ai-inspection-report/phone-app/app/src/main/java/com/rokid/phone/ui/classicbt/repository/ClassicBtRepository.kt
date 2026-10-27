package com.rokid.phone.ui.classicbt.repository

import android.bluetooth.BluetoothDevice
import com.rokid.phone.ui.classicbt.model.BluetoothDeviceInfo
import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.security.phone.sdk.api.bluetooth.classic.listener.IClassicBTClientListener
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 只负责搜索附近的 Glass3 经典蓝牙设备。
 */
class ClassicBtRepository {
    private val devices = linkedMapOf<String, BluetoothDeviceInfo>()

    val events = MutableSharedFlow<List<BluetoothDeviceInfo>>(extraBufferCapacity = 8)
    val scanFinished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val listener = object : IClassicBTClientListener {
        override fun onDeviceFound(device: BluetoothDevice) {
            val name = device.name
            if (name.isNullOrBlank() || !name.contains("Glass3")) return
            if (device.type == BluetoothDevice.DEVICE_TYPE_LE) return
            devices[device.address] = BluetoothDeviceInfo(name, device.address, device.type)
            events.tryEmit(devices.values.toList())
        }

        override fun onScanFinished() {
            scanFinished.tryEmit(Unit)
        }

        override fun onConnect(success: Boolean) = Unit

        override fun onConnectionRejected(reason: String, code: Int) = Unit
    }

    fun addListener() {
        PSecuritySDK.getClassicBlueToothClientService()?.addClientListener(listener)
    }

    fun removeListener() {
        PSecuritySDK.getClassicBlueToothClientService()?.removeClientListener(listener)
    }

    fun startScan() {
        devices.clear()
        PSecuritySDK.getClassicBlueToothClientService()?.startScan(10_000L)
    }

    fun stopScan() {
        PSecuritySDK.getClassicBlueToothClientService()?.stopScan()
    }
}
