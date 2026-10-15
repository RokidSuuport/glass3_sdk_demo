package com.rokid.glass

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rokid.glass.utils.GlassSdkUtils
import com.rokid.glass.utils.ToastUtil

/**
 * 眼镜端 Demo 的应用入口。
 * Application entry point for the glasses demo.
 */
class MyApplication : Application() {
    companion object {
        private const val TAG = "MyApplication"

        // 仅保存 Application Context，避免持有 Activity 引用。
        // Keep only the Application Context to avoid retaining an Activity reference.
        @Volatile
        var mContext: MyApplication? = null

        // 眼镜腿物理按键单击
        // Single press of the physical temple button.
        const val ACTION_BUTTON_CLICK = "com.rokid.glass3.action.button.CLICK"
        // 眼镜腿物理按键双击
        // Double press of the physical temple button.
        const val ACTION_BUTTON_DOUBLE_CLICK = "com.rokid.glass3.action.button.DOUBLE_CLICK"
        const val ACTION_LONG_PRESS: String = "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS"
        const val ACTION_BUTTON_DOWN: String = "com.android.action.ACTION_SPRITE_BUTTON_DOWN"
        const val ACTION_BUTTON_UP: String = "com.android.action.ACTION_SPRITE_BUTTON_UP"
        const val ACTION_TAKE_STATUS: String = "com.rokid.sprite.ACTION_TAKE_STATUS_CHANGED"
        const val ACTION_LEG_STATUS: String = "com.rokid.sprite.ACTION_LEG_STATUS_CHANGED"

        var gMainHandler: Handler? = null
        var curIsCameraActivity = false

        var sendAudioStatus = false
        fun getContext(): MyApplication {
            return mContext ?: throw IllegalStateException("Application not initialized")
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 初始化全局 Context
        // Initialize the global Context.
        mContext = this
        gMainHandler = Handler(Looper.getMainLooper())
        ToastUtil.init(this)
        // SDK 初始化放到 Application：进程被系统重启（例如直接恢复到某个 Activity）时也会执行，
        // 否则服务池不会建立，所有 Media 调用都会报 media_service_unavailable。
        // Keep SDK init in Application so it also runs when the process is restarted straight
        // into another activity; otherwise no service pool is created and media calls fail.
        if (Application.getProcessName() == packageName) {
            runCatching { GlassSdkUtils.initSdk() }
                .onFailure { Log.w(TAG, "initSdk failed: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

}
