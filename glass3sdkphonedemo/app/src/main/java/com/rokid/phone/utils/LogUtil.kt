package com.rokid.phone.utils



import android.util.Log

object LogUtil {

    /** 日志等级常量（与 android.util.Log 一致） */
    const val VERBOSE = Log.VERBOSE
    const val DEBUG = Log.DEBUG
    const val INFO = Log.INFO
    const val WARN = Log.WARN
    const val ERROR = Log.ERROR
    const val NONE = Int.MAX_VALUE   // 不打印任何日志
    const val Rokid = "Rokid"
    /** 当前日志等级（初始化时设置） */
    var logLevel: Int = VERBOSE

    /** 默认 TAG */
    private const val DEFAULT_TAG = "PhonedemoLog"

    fun v(tag: String = DEFAULT_TAG, msg: String) {
        if (logLevel <= VERBOSE) Log.v(Rokid+tag, msg)
    }

    fun d(tag: String = DEFAULT_TAG, msg: String) {
        if (logLevel <= DEBUG) Log.d(Rokid+tag, msg)
    }

    fun i(tag: String = DEFAULT_TAG, msg: String) {
        if (logLevel <= INFO) Log.i(Rokid+tag, msg)
    }

    fun w(tag: String = DEFAULT_TAG, msg: String) {
        if (logLevel <= WARN) Log.w(Rokid+tag, msg)
    }

    fun e(tag: String = DEFAULT_TAG, msg: String, tr: Throwable? = null) {
        if (logLevel <= ERROR) Log.e(Rokid+tag, msg, tr)
    }

    /** 打印长日志（按 logLevel 控制） */
    fun longLog(tag: String = DEFAULT_TAG, msg: String, level: Int = DEBUG) {
        if (logLevel > level) return
        val maxLength = 2000
        var start = 0
        val length = msg.length
        while (start < length) {
            val end = (start + maxLength).coerceAtMost(length)
            when (level) {
                VERBOSE -> Log.v(tag, msg.substring(start, end))
                DEBUG -> Log.d(tag, msg.substring(start, end))
                INFO -> Log.i(tag, msg.substring(start, end))
                WARN -> Log.w(tag, msg.substring(start, end))
                ERROR -> Log.e(tag, msg.substring(start, end))
            }
            start = end
        }
    }
}


