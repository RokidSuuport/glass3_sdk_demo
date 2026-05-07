package com.rokid.phone.utils

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.rokid.security.phone.sdk.base.data.EnvType
import com.rokid.security.sdk.ability.net.SecuritySDKEnv
import com.rokid.security.sdk.ability.net.UrlConfig.hostUrl

/**
 * Author: zhangshengwei
 * Date: 2025/7/30
 */
object NetworkUtil {

    /**
     * 判断当前是否有网络连接（移动数据或 Wi-Fi）
     * @param context 上下文
     * @return true：有网络；false：无网络
     */
    @SuppressLint("ServiceCast")
    fun isNetworkAvailable(context: Context): Boolean {
        // 获取连接管理器
        val connectivityManager = context.applicationContext.getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as ConnectivityManager

        // Android 10 及以上版本使用 NetworkCapabilities
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 获取当前活跃网络
            val network = connectivityManager.activeNetwork ?: return false
            // 获取网络 capabilities
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            // 检查是否有联网能力（Wi-Fi 或移动数据）
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        } else {
            // Android 10 以下版本（过时方法，仅兼容用）
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            @Suppress("DEPRECATION")
            return networkInfo != null && networkInfo.isConnected
        }
    }


    /**
     * 获取图片 url 前缀地址
     *
     * @return 公网固定是："https://tatooine.rokidcdn.com"  内网或私有化为其自身 url
     */
    fun getImageHost(): String {
        return if(SecuritySDKEnv.envType == EnvType.PUBLIC ||
            SecuritySDKEnv.envType == EnvType.PUBLIC_DEV ||
            SecuritySDKEnv.envType == EnvType.PUBLIC_TEST) {

            "https://tatooine.rokidcdn.com"
        }
        else {
            hostUrl
        }
    }

}
