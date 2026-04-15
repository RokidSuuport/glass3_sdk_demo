package com.rokid.glass.base

import android.view.KeyEvent
import androidx.annotation.IntDef
import com.rokid.glass.base.GlassKeyEvent.Companion.KEYCODE_BACK
import com.rokid.glass.base.GlassKeyEvent.Companion.KEYCODE_BEHIND
import com.rokid.glass.base.GlassKeyEvent.Companion.KEYCODE_CLICK
import com.rokid.glass.base.GlassKeyEvent.Companion.KEYCODE_DOUBLE_BEHIND
import com.rokid.glass.base.GlassKeyEvent.Companion.KEYCODE_DOUBLE_CLICK
import com.rokid.glass.base.GlassKeyEvent.Companion.KEYCODE_DOUBLE_FRONT
import com.rokid.glass.base.GlassKeyEvent.Companion.KEYCODE_DPAD_DOWN
import com.rokid.glass.base.GlassKeyEvent.Companion.KEYCODE_FRONT

/**
 * Author: zhangshengwei
 * Date: 2025/5/13
 */


@Retention(AnnotationRetention.SOURCE)
@IntDef(
    /**
     * 点击
     * **/
    KEYCODE_DPAD_DOWN,
    /**
     * 向后滑
     * **/
    KEYCODE_BEHIND,
    /**
     * 向前滑
     * **/
    KEYCODE_FRONT,
    /**
     * 返回
     * **/
    KEYCODE_BACK,


    /**
     * 向后滑双击(指环)
     * **/
    KEYCODE_DOUBLE_BEHIND,

    /**
     * 向前滑双击(指环)
     * **/
    KEYCODE_DOUBLE_FRONT,

    /**
     * 指环中间键单击
     * **/
    KEYCODE_CLICK,

    /**
     * 指环中间键双击
     * **/
    KEYCODE_DOUBLE_CLICK,
)
annotation class GlassKeyEvent{


    companion object {
        const val KEYCODE_DPAD_DOWN = KeyEvent.KEYCODE_DPAD_DOWN
        const val KEYCODE_BEHIND = KeyEvent.KEYCODE_DPAD_RIGHT
        const val KEYCODE_FRONT = KeyEvent.KEYCODE_DPAD_LEFT
        const val KEYCODE_DOUBLE_BEHIND = 100001
        const val KEYCODE_DOUBLE_FRONT = 100002
        const val KEYCODE_CLICK = 100003
        const val KEYCODE_DOUBLE_CLICK = 100004
        const val KEYCODE_BACK = KeyEvent.KEYCODE_BACK
    }

}
