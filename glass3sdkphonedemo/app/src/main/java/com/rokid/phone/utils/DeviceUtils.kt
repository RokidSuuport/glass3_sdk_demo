package com.rokid.phone.utils

import android.bluetooth.BluetoothProfile
import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.telephony.TelephonyManager
import android.util.Log

/** 将设备连接状态转换为便于展示的文本。 */
object DeviceUtils {

    fun getP2PDeviceStatus(deviceStatus: Int): String {
        return when (deviceStatus) {
            WifiP2pDevice.AVAILABLE -> "可用的"
            WifiP2pDevice.INVITED -> "邀请中"
            WifiP2pDevice.CONNECTED -> "已连接"
            WifiP2pDevice.FAILED -> "失败的"
            WifiP2pDevice.UNAVAILABLE -> "不可用的"
            else -> "未知"
        }
    }

    fun getBleDeviceStatus(connectionState: Int): String {
        return when (connectionState) {
            BluetoothProfile.STATE_DISCONNECTED -> "未连接"
            BluetoothProfile.STATE_CONNECTING -> "连接中"
            BluetoothProfile.STATE_CONNECTED -> "已连接"
            BluetoothProfile.STATE_DISCONNECTING -> "断开中"
            else -> "未知状态"
        }
    }





    fun getSimCountryIso(context: Context): String {
        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val simCountryIso = telephonyManager.simCountryIso
            Log.d("DeviceUtils", "SIM country ISO: $simCountryIso")
            if (simCountryIso.isNullOrEmpty()) {
                "CN"
            } else {
                simCountryIso
            }
        } catch (e: Exception) {
            Log.e("DeviceUtils","Failed to get SIM country ISO", e)
            "CN"
        }
    }

}
