package com.rokid.glass.base

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.rokid.security.glass3.open.sdk.uitls.log.L
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

open class BaseActivity : AppCompatActivity() {

    private var TAG = "BaseActivity"
    private val glassKeyInput = GlassKeyInput { onGlassKeyEvent(it) }

    /** 控件自行处理触摸时关闭触摸转指环手势。 / Disable touch-to-ring translation for interactive controls. */
    protected open val translateTouchToGlassGesture: Boolean = true

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (glassKeyInput.dispatch(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private var lastHomeClickTime = 0L
    private val DoubleTime = 500L
    private var clickJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    protected fun ringHomeClick() {
        val curTime = System.currentTimeMillis()
        val timeDiff = curTime - lastHomeClickTime
        Log.d(TAG, "指环中间事件->时间差值:${timeDiff}毫秒")
        // 取消之前未执行的单击协程
        // Cancel any previous pending single-click coroutine.
        clickJob?.cancel()
        clickJob = null
        if (timeDiff < DoubleTime) {
            Log.d(TAG, "指环中间事件->判定为双击：间隔${timeDiff}ms")
            GlassKeyEvent(GlassKeyEvent.KEYCODE_DOUBLE_CLICK)
        } else {
            // 启动协程延迟发送单击事件
            // Start a coroutine to dispatch the single-click event after a delay.
            clickJob = scope.launch {
                delay(DoubleTime)
                // 检查协程是否仍处于活跃状态
                // Check whether the coroutine is still active.
                if (isActive) {
                    Log.d(TAG, "指环中间事件->判定为单击：间隔${timeDiff}ms")
                    GlassKeyEvent(GlassKeyEvent.KEYCODE_CLICK)
                }
            }
        }
        lastHomeClickTime = curTime
    }

    private var startX = 0f
    private var startY = 0f
    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        if (!translateTouchToGlassGesture) return super.dispatchTouchEvent(event)
        when (event?.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                Log.d(TAG, "dispatchTouchEvent ACTION_DOWN, startX:$startX, startY:$startY")
            }

            MotionEvent.ACTION_MOVE -> {
                Log.d(TAG, "dispatchTouchEvent ACTION_MOVE")
            }

            MotionEvent.ACTION_UP -> {
                val endX = event.x
                val endY = event.y
                Log.d(TAG, "dispatchTouchEvent ACTION_UP")
                if (endX == startX && endY == startY) {
                    ringHomeClick()
                } else if (endX - startX < 0) {
                    Log.d(TAG, "dispatchTouchEvent 指环前键双击,endX:${endX}, endY:${endY}")
                    GlassKeyEvent(GlassKeyEvent.KEYCODE_DOUBLE_FRONT)
                } else if (endX - startX > 0) {
                    Log.d(TAG, "dispatchTouchEvent 指环后键双击,endX:${endX}, endY:${endY}")
                    GlassKeyEvent(GlassKeyEvent.KEYCODE_DOUBLE_BEHIND)
                } else if (endY > startY) {
                    Log.d(TAG, "dispatchTouchEvent 指环前键单击,endX:${endX}, endY:${endY}")
                    GlassKeyEvent(GlassKeyEvent.KEYCODE_FRONT)
                } else if (endY < startY) {
                    Log.d(TAG, "dispatchTouchEvent 指环后键单击,endX:${endX}, endY:${endY}")
                    GlassKeyEvent(GlassKeyEvent.KEYCODE_BEHIND)
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        event?.let { motionEvent ->
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> {
                    L.d(TAG, "onTouchEvent:ACTION_DOWN")
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    L.d(TAG, "onTouchEvent:ACTION_MOVE")
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    L.d(TAG, "onTouchEvent:ACTION_UP")
                    return true
                }

                else -> {}
            }
        }

        return super.onTouchEvent(event)
    }

    fun GlassKeyEvent(keyEvent: Int): Boolean {
        return glassKeyInput.dispatchGesture(keyEvent)
    }

    open fun onGlassKeyEvent(@GlassKeyEvent keyEvent: Int): Boolean {
        if (keyEvent == GlassKeyEvent.KEYCODE_DOUBLE_CLICK) {
            finish()
        }
        return false
    }

    /**
     * 屏幕常亮
     * Keep the screen on.
     */
    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * 取消屏幕常亮
     * Stop keeping the screen on.
     */
    override fun onPause() {
        super.onPause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

}
