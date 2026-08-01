package com.rokid.glass.speech

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.rokid.glass.base.BaseActivity
import com.rokid.glass.base.GlassKeyEvent

abstract class SpeechGestureActivity : BaseActivity() {
    private val touchSequence by lazy {
        SpeechTouchSequence(ViewConfiguration.get(this).scaledTouchSlop.toFloat())
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        when (event?.actionMasked) {
            MotionEvent.ACTION_DOWN -> touchSequence.onDown(event.x, event.y)
            MotionEvent.ACTION_UP -> {
                if (touchSequence.isTapOnUp(event.x, event.y)) {
                    onGlassKeyEvent(GlassKeyEvent.KEYCODE_CLICK)
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> touchSequence.cancel()
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isCenterClick = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> true
            else -> false
        }
        if (!isCenterClick) return super.dispatchKeyEvent(event)

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            onGlassKeyEvent(GlassKeyEvent.KEYCODE_CLICK)
        }
        return true
    }
}
