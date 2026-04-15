package com.rokid.glass.utils

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 长点击事件检测器
 * 用于检测指环按键的长按事件
 * 自动检测长按开始和结束
 */
class LongPressDetector private constructor(
    private val longPressDelay: Long = DEFAULT_LONG_PRESS_DELAY,
    private val autoStopDelay: Long = DEFAULT_AUTO_STOP_DELAY
) {

    companion object {
        private const val TAG = "LongPressDetector"
        private const val DEFAULT_LONG_PRESS_DELAY = 250L // 默认250 毫秒触发长按
        private const val DEFAULT_AUTO_STOP_DELAY = 1000L // 默认1000 毫秒自动判定为停止

        // 创建单例实例
        @Volatile
        private var instance: LongPressDetector? = null

        fun getInstance(
            longPressDelay: Long = DEFAULT_LONG_PRESS_DELAY,
            autoStopDelay: Long = DEFAULT_AUTO_STOP_DELAY
        ): LongPressDetector {
            return instance ?: synchronized(this) {
                instance ?: LongPressDetector(longPressDelay, autoStopDelay).also { instance = it }
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var longPressJob: Runnable? = null
    private var autoStopJob: Runnable? = null
    private var isLongPressing = false
    private var lastKeyDownTime = 0L
    private var currentKeyCode = -1

    /**
     * 开始检测长按事件
     * @param keyCode 按键码
     * @param onLongPressStart 长按开始回调
     * @param onLongPressEnd 长按结束回调
     */
    fun startDetect(
        keyCode: Int,
        onLongPressStart: (() -> Unit)? = null,
        onLongPressEnd: (() -> Unit)? = null
    ) {
        val currentTime = System.currentTimeMillis()

        // 如果是同一个按键且已经在长按中，重置自动停止计时器
        if (isLongPressing && keyCode == currentKeyCode) {
            Log.d(TAG, "同一组长按事件，重置计时器：keyCode=$keyCode")
            resetAutoStopTimer(keyCode, onLongPressEnd)
            return
        }

        // 取消之前的任务
        cancelDetect()

        lastKeyDownTime = currentTime
        currentKeyCode = keyCode

        // 创建长按任务
        longPressJob = Runnable {
            isLongPressing = true
//            Log.d(TAG, "长按事件触发：keyCode=$keyCode")
            onLongPressStart?.invoke()

            // 启动自动停止检测
            startAutoStopDetection(currentKeyCode, onLongPressEnd)
        }

        // 延迟执行长按任务
        handler.postDelayed(longPressJob!!, longPressDelay)
    }

    /**
     * 停止检测长按事件
     * @param onLongPressEnd 长按结束回调
     */
    fun stopDetect(onLongPressEnd: (() -> Unit)? = null) {
        handleLongPressEnd(onLongPressEnd)
    }

    /**
     * 处理长按结束
     */
    private fun handleLongPressEnd(onLongPressEnd: (() -> Unit)? = null) {
        if (isLongPressing) {
//            Log.d(TAG, "长按事件结束：keyCode=$currentKeyCode")
            onLongPressEnd?.invoke()
        }
        reset()
    }

    /**
     * 取消所有检测任务
     */
    private fun cancelDetect() {
        longPressJob?.let {
            handler.removeCallbacks(it)
            longPressJob = null
        }
        autoStopJob?.let {
            handler.removeCallbacks(it)
            autoStopJob = null
        }
    }

    /**
     * 启动自动停止检测
     * 当超过设定时间没有再次收到按键事件时，自动判定为停止长按
     */
    private fun startAutoStopDetection(keyCode: Int, onLongPressEnd: (() -> Unit)? = null) {
        autoStopJob = Runnable {
            val elapsedTime = System.currentTimeMillis() - lastKeyDownTime
            if (elapsedTime >= autoStopDelay) {
                // 超过自动停止时间，判定为长按结束
                Log.d(TAG, "自动检测到长按停止：keyCode=$keyCode, 间隔=${elapsedTime}ms")
                handleLongPressEnd(onLongPressEnd)
            } else {
                // 继续检测
                startAutoStopDetection(keyCode, onLongPressEnd)
            }
        }
        handler.postDelayed(autoStopJob!!, autoStopDelay)
    }

    /**
     * 重置自动停止计时器
     * 当再次收到相同按键事件时调用
     */
    private fun resetAutoStopTimer(keyCode: Int, onLongPressEnd: (() -> Unit)? = null) {
        lastKeyDownTime = System.currentTimeMillis()
        autoStopJob?.let {
            handler.removeCallbacks(it)
            autoStopJob = null
        }
//        Log.d(TAG, "重置自动停止计时器：keyCode=$keyCode")
        // 重新启动检测
        startAutoStopDetection(keyCode, onLongPressEnd)
    }

    /**
     * 重置检测器状态
     */
    fun reset() {
        cancelDetect()
        isLongPressing = false
        lastKeyDownTime = 0L
        currentKeyCode = -1
    }

    /**
     * 是否正在长按中
     */
    fun isLongPressing(): Boolean {
        return isLongPressing
    }

    /**
     * 获取当前长按的按键码
     */
    fun getCurrentKeyCode(): Int {
        return currentKeyCode
    }
}
