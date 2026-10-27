package com.rokid.glass.utils

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rokid.glass.MyApplication
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.client.IServiceConnectionCallback
import com.rokid.security.system.server.IClientCallback

/**
 * 初始化 Glass3 Open SDK，并注册本应用的 clientId。
 *
 * 只有客户端就绪后，AI 问答和自定义消息接口才能使用。这里不订阅普通业务通知，
 * 避免把展示页不需要的蓝牙、P2P、指环和系统信息回调带进应用。
 */
object GlassSdkUtils {
    private const val TAG = "GlassSdkUtils"
    private const val CLIENT_ID = "GlassSample"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var bindingInFlight = false

    private val clientCallback = object : IClientCallback.Stub() {
        override fun onReady() {
            Log.i(TAG, "Glass SDK client ready")
        }
    }

    @Synchronized
    fun initSdk() {
        if (GlassSdk.isReady() || bindingInFlight) return
        bindingInFlight = true
        GlassSdk.bindSecurityService(MyApplication.getContext(), object : IServiceConnectionCallback {
            override fun onServiceConnected() {
                bindingInFlight = false
                GlassSdk.registerClient(CLIENT_ID, clientCallback)
            }

            override fun onServiceDisconnected() {
                bindingInFlight = false
                Log.w(TAG, "Glass SDK service disconnected")
            }

            override fun onBindingDied() {
                bindingInFlight = false
                Log.e(TAG, "Glass SDK binding died; retrying")
                mainHandler.postDelayed({ runCatching { initSdk() } }, 2_000L)
            }
        })
    }
}
