package com.rokid.glass.base

import android.os.SystemClock
import android.view.KeyEvent

/**
 * Dispatch temple keys before Android moves view focus, while leaving unhandled keys to the view.
 * 在 Android 移动视图焦点前分发镜腿按键，未处理的按键仍交给视图。
 */
internal class GlassKeyInput(private val onGesture: (Int) -> Boolean) {
    private companion object {
        const val DEBOUNCE_INTERVAL_MS = 100L
    }

    private var handledKeyCode: Int? = null
    private var lastGesture = -1
    private var lastGestureTime = 0L

    fun dispatchGesture(gesture: Int): Boolean {
        // 单调时钟避免系统时间调整影响去重；未处理事件仍交给原生视图。
        // Use a monotonic clock for debounce; leave unhandled events to native views.
        val now = SystemClock.uptimeMillis()
        if (gesture == lastGesture && now - lastGestureTime < DEBOUNCE_INTERVAL_MS) return true
        return onGesture(gesture).also { handled ->
            if (handled) {
                lastGesture = gesture
                lastGestureTime = now
            }
        }
    }

    fun dispatch(event: KeyEvent): Boolean {
        val gesture = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> GlassKeyEvent.KEYCODE_FRONT
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> GlassKeyEvent.KEYCODE_BEHIND
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> GlassKeyEvent.KEYCODE_CLICK
            KeyEvent.KEYCODE_BACK -> GlassKeyEvent.KEYCODE_BACK
            else -> return false
        }
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return handledKeyCode == event.keyCode
                dispatchGesture(gesture).also { handled ->
                    if (handled) handledKeyCode = event.keyCode
                }
            }
            KeyEvent.ACTION_UP -> {
                if (handledKeyCode == event.keyCode) {
                    handledKeyCode = null
                    true
                } else false
            }
            else -> false
        }
    }
}
