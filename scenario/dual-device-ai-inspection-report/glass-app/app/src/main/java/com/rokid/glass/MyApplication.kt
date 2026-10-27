package com.rokid.glass

import android.app.Application
import android.util.Log
import com.rokid.glass.utils.GlassSdkUtils

/**
 * 眼镜端隐患识别应用的进程入口，只负责 SDK 初始化和应用 Context。
 */
class MyApplication : Application() {
    companion object {
        private const val TAG = "MyApplication"

        @Volatile
        var mContext: MyApplication? = null

        fun getContext(): MyApplication {
            return mContext ?: throw IllegalStateException("Application not initialized")
        }
    }

    override fun onCreate() {
        super.onCreate()
        mContext = this
        if (Application.getProcessName() == packageName) {
            runCatching { GlassSdkUtils.initSdk() }
                .onFailure { Log.w(TAG, "initSdk failed: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }
}
