package com.rokid.glass.speech

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
                    GlassKeyEvent(GlassKeyEvent.KEYCODE_CLICK)
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> touchSequence.cancel()
        }
        return super.dispatchTouchEvent(event)
    }

}
