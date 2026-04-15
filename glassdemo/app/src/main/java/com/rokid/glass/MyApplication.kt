package com.rokid.glass

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.rokid.glass.utils.ToastUtil


/**
 * Description:
 * Author:Lc
 * Date:2025/5/25
 */
class MyApplication : Application() {
    companion object {
        // 全局 Context 变量
        @Volatile
        var mContext: MyApplication? = null

        //        val APP_ID = "GlassSample"
        // 眼镜腿物理按键单击
        const val ACTION_BUTTON_CLICK = "com.rokid.glass3.action.button.CLICK"
        // 眼镜腿物理按键双击
        const val ACTION_BUTTON_DOUBLE_CLICK = "com.rokid.glass3.action.button.DOUBLE_CLICK"
        const val ACTION_LONG_PRESS: String = "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS"
        const val ACTION_BUTTON_DOWN: String = "com.android.action.ACTION_SPRITE_BUTTON_DOWN"
        const val ACTION_BUTTON_UP: String = "com.android.action.ACTION_SPRITE_BUTTON_UP"
        const val ACTION_TAKE_STATUS: String = "com.rokid.sprite.ACTION_TAKE_STATUS_CHANGED"
        const val ACTION_LEG_STATUS: String = "com.rokid.sprite.ACTION_LEG_STATUS_CHANGED"

        var gMainHandler: Handler? = null
        var curIsCameraActivity = false

        // 获取全局 Context 的方法
//        var sendVideoStatus = false
        var sendAudioStatus = false
        fun getContext(): MyApplication {
            return mContext ?: throw IllegalStateException("Application not initialized")
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 初始化全局 Context
        mContext = this
        gMainHandler = Handler(Looper.getMainLooper())
        ToastUtil.init(this)
    }


}