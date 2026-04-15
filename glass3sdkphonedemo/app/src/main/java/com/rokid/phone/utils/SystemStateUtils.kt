package com.rokid.phone.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.wifi.WifiManager
import android.provider.Settings
import android.os.Build
import android.util.Log

/**
 * Created by wjm on 2025/6/23
 */
object SystemStateUtils {

    /**
     * 检查 Wi-Fi 是否已启用
     *
     * @param context 上下文
     * @return Wi-Fi 已启用返回 true，否则返回 false
     */
    @JvmStatic
    fun isWifiEnabled(context: Context): Boolean {
        val wifiManager = context.getSystemService(WifiManager::class.java)
        return wifiManager?.isWifiEnabled == true
    }

    /**
     * 检查蓝牙是否已启用
     *
     * @return 蓝牙已启用返回 true，否则返回 false
     */
    @JvmStatic
    fun isBluetoothEnabled(): Boolean {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        return bluetoothAdapter?.isEnabled == true
    }
}