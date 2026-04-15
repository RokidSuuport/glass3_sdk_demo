package com.rokid.phone.utils


/**
 * 全局数据单例，用来存储应用内的数据和状态
 */
object AppDataManager {

//    binding.tvDeviceName.text = DeviceLinkerManager.mBluetoothDevice?.name
//    binding.tvDeviceSn.text = SystemGlobalConstant.deviceId
//    binding.tvDeviceSystem.text = SystemGlobalConstant.version
//    Log.d("gzt",SystemGlobalConstant.deviceId)
//
//    Log.d("gzt",SystemGlobalConstant.version)

    // 是否已连接 P2P
    var isP2pConnected: Boolean = false

    // 是否已连接普通 蓝牙
    var isBtConnect: Boolean = false

    // 当前连接的蓝牙 设备名
    var mBluetoothDeviceName: String? = null
    //系统版本
    var ioVersion: String? = null
    var deviceId: String? = null

    // 缓存的数据（比如临时存放消息列表）
    val messageCache: MutableList<String> = mutableListOf()

    // 统一清理方法
    fun clearAll() {
        isP2pConnected = false
        isBtConnect = false
        ioVersion = null
        mBluetoothDeviceName=null
        deviceId=null
        messageCache.clear()
    }
}
