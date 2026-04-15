package com.rokid.glass

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log


class GlassesButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            ORDER_ACTION_BUTTON_CLICK -> {
                Log.d("GlassesButton", "收到单击事件")
                // TODO: 业务逻辑
                abortBroadcast() // 拦截广播，阻止系统默认处理
            }
            ORDER_ACTION_BUTTON_DOUBLE_CLICK -> {
                Log.d("GlassesButton", "收到双击事件")
                // TODO: 业务逻辑
                abortBroadcast()
            }
        }
    }

    companion object {
        const val ORDER_ACTION_BUTTON_CLICK =
            "com.rokid.glass3.action.button.CLICK"
        const val ORDER_ACTION_BUTTON_DOUBLE_CLICK =
            "com.rokid.glass3.action.button.DOUBLE_CLICK"
    }
}
