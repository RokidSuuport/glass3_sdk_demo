package com.rokid.phone.utils

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

object NetworkUtil {

    /**
     * 判断当前是否有网络连接（移动数据或 Wi-Fi）
     * Check whether a network connection is available through mobile data or Wi-Fi.
     * @param context 上下文
     * Context.
     * @return true：有网络；false：无网络
     * true if a network is available; false otherwise.
     */
    @SuppressLint("ServiceCast")
    fun isNetworkAvailable(context: Context): Boolean {
        // 获取连接管理器
        // Get the connectivity manager.
        val connectivityManager = context.applicationContext.getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as ConnectivityManager

        // Android 10 及以上版本使用 NetworkCapabilities
        // Use NetworkCapabilities on Android 10 and later.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 获取当前活跃网络
            // Get the currently active network.
            val network = connectivityManager.activeNetwork ?: return false
            // 获取网络 capabilities
            // Get the network capabilities.
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            // 检查是否有联网能力（Wi-Fi 或移动数据）
            // Check for internet connectivity through Wi-Fi or mobile data.
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        } else {
            // Android 10 以下版本（过时方法，仅兼容用）
            // On Android versions below 10, use the deprecated API for compatibility only.
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            @Suppress("DEPRECATION")
            return networkInfo != null && networkInfo.isConnected
        }
    }

}
