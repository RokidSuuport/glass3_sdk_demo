package com.rokid.glass.base

import android.R
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.rokid.security.glass3.open.sdk.uitls.log.L
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Author: zhangshengwei
 * Date: 2025/5/13
 */
open class BaseGlassActivity : AppCompatActivity() {

    private lateinit var rootView: View
    private var TAG ="BaseGlassActivity"


    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        initContentView()
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        initContentView()
    }

    override fun setContentView(view: View?, params: ViewGroup.LayoutParams?) {
        super.setContentView(view, params)
        initContentView()
    }

    private fun initContentView() {
        rootView = findViewById(R.id.content)
        ViewCompat.addOnUnhandledKeyEventListener(rootView, mOnUnhandledKeyEventListener)
    }



    private val mOnUnhandledKeyEventListener = ViewCompat.OnUnhandledKeyEventListenerCompat { v, event ->
        var ret = false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                when (event.keyCode) {
//                    KeyEvent.KEYCODE_DPAD_DOWN -> {
//                        Log.d(TAG, "---OnUnhandledKeyEventListenerCompat:方向键向下事件")
//                        ret = GlassKeyEvent(GlassKeyEvent.KEYCODE_DPAD_DOWN)
//                        return@OnUnhandledKeyEventListenerCompat true
//                    }
                    // 触摸板向前滑动
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        Log.d(TAG, "---OnUnhandledKeyEventListenerCompat:按下事件,触摸板向前滑动")
                        ret = GlassKeyEvent(GlassKeyEvent.KEYCODE_FRONT)
                        return@OnUnhandledKeyEventListenerCompat true
                    }
                    // 触摸板向后滑动
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        Log.d(TAG, "---OnUnhandledKeyEventListenerCompat:按下事件,触摸板向后滑动")
                        ret = GlassKeyEvent(GlassKeyEvent.KEYCODE_BEHIND)
                        return@OnUnhandledKeyEventListenerCompat true
                    }
                    // 触摸板返回键
                    KeyEvent.KEYCODE_BACK -> {
                        Log.d(TAG, "---OnUnhandledKeyEventListenerCompat:按下事件,触摸板返回键")
                        ret = GlassKeyEvent(GlassKeyEvent.KEYCODE_BACK)
                        return@OnUnhandledKeyEventListenerCompat ret
                    }
                }
            }
        }
        false
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        when (event?.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                Log.d(TAG, "dispatchTouchEvent ACTION_DOWN, startX:$startX, startY:$startY")
            }

            MotionEvent.ACTION_MOVE -> {
                Log.d(TAG, "dispatchTouchEvent ACTION_MOVE")
//                return true
            }

            MotionEvent.ACTION_UP -> {
                val endX = event.x
                val endY = event.y
                Log.d(TAG, "dispatchTouchEvent ACTION_UP")
                if (endX == startX && endY == startY) {
                    ringHomeClick()
                } else if (endX - startX < 0) {
                    Log.d(TAG, "dispatchTouchEvent 指环前键双击,endX:${endX}, endY:${endY}")
                    onGlassKeyEvent(GlassKeyEvent.KEYCODE_DOUBLE_FRONT)
                } else if (endX - startX > 0) {
                    Log.d(TAG, "dispatchTouchEvent 指环后键双击,endX:${endX}, endY:${endY}")
                    onGlassKeyEvent(GlassKeyEvent.KEYCODE_DOUBLE_BEHIND)
                } else if (endY > startY) {
                    Log.d(TAG, "dispatchTouchEvent 指环前键单击,endX:${endX}, endY:${endY}")
                    onGlassKeyEvent(GlassKeyEvent.KEYCODE_FRONT)
                } else if (endY < startY) {
                    Log.d(TAG, "dispatchTouchEvent 指环后键单击,endX:${endX}, endY:${endY}")
                    onGlassKeyEvent(GlassKeyEvent.KEYCODE_BEHIND)
                }
                return true
            }
        }
        return super.dispatchTouchEvent(event)
    }


    private var lastHomeClickTime = 0L
    private var DoubleTime = 500L
    private var isDoubleClickDetected = false
    private var clickJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun ringHomeClick() {
        val curTime = System.currentTimeMillis()
        val timeDiff = curTime - lastHomeClickTime

        Log.e("BaseGlassActivity", "ringHomeClick->$timeDiff ms | curTime:$curTime | lastHomeClickTime:$lastHomeClickTime")

        // 取消之前未执行的单击协程
        clickJob?.cancel()
        clickJob = null
        

        if (timeDiff < DoubleTime) {
            Log.d("BaseGlassActivity", "判定为双击：间隔${timeDiff}ms")
            GlassKeyEvent(GlassKeyEvent.KEYCODE_DOUBLE_CLICK)
        } else {
            // 启动协程延迟发送单击事件
            clickJob = scope.launch {
                delay(DoubleTime)
                // 检查协程是否仍处于活跃状态
                if (isActive) {
                    Log.d("BaseGlassActivity", "判定为单击：间隔${timeDiff}ms")
                    GlassKeyEvent(GlassKeyEvent.KEYCODE_CLICK)
                }
            }
        }

        lastHomeClickTime = curTime
    }

    private var startX = 0f
    private var startY = 0f

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


    private var lastKeyTime: Long = 0
    private var lastKeyCode = -1
    // 防重复时间间隔（100ms）
    private val DEBOUNCE_INTERVAL: Long = 100

    fun GlassKeyEvent(keyEvent: Int): Boolean {
        Log.d("BaseGlassActivity", "onGlassKeyEvent:" + keyEvent)
        // 获取当前时间戳
        val currentTime = System.currentTimeMillis()

        // 检查是否在100ms内按下相同的键
        if (keyEvent == lastKeyCode && (currentTime - lastKeyTime) < DEBOUNCE_INTERVAL) {
            // 100ms内重复按相同键，视为重复事件，直接返回
            Log.d("BaseGlassActivity", "忽略重复按键: " + keyEvent)
            return true // 消费事件，不再传递
        }

        // 不是重复事件，更新记录的时间和键值
        lastKeyTime = currentTime
        lastKeyCode = keyEvent
        onGlassKeyEvent(keyEvent)
        return false
    }




     open fun onGlassKeyEvent(@GlassKeyEvent keyEvent: Int): Boolean {
         if (keyEvent == GlassKeyEvent.KEYCODE_DOUBLE_CLICK) {
             finish()
         }
         return false
     }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }










}