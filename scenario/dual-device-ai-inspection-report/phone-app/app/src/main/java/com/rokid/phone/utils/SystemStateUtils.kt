package com.rokid.phone.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.wifi.WifiManager
import android.provider.Settings
import android.os.Build
import android.util.Log

object SystemStateUtils {

    /**
     * 检查 Wi-Fi 是否已启用
     * Check whether Wi-Fi is enabled.
     *
     * @param context 上下文
     * Context.
     * @return Wi-Fi 已启用返回 true，否则返回 false
     * true if Wi-Fi is enabled, otherwise false.
     */
    @JvmStatic
    fun isWifiEnabled(context: Context): Boolean {
        val wifiManager = context.getSystemService(WifiManager::class.java)
        return wifiManager?.isWifiEnabled == true
    }

    /**
     * 检查蓝牙是否已启用
     * Check whether Bluetooth is enabled.
     *
     * @return 蓝牙已启用返回 true，否则返回 false
     * true if Bluetooth is enabled, otherwise false.
     */
    @JvmStatic
    fun isBluetoothEnabled(): Boolean {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        return try {
            bluetoothAdapter?.isEnabled == true
        } catch (e: SecurityException) {
            Log.w("SystemStateUtils", "Missing bluetooth permission when checking bluetooth state", e)
            false
        }
    }
}
