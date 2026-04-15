package com.rokid.glass.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeUtils {

    // 时间戳转格式化时间（秒级）
    fun timestampToDateTime(timestamp: Long, pattern: String = "yyyy-MM-dd HH:mm:ss"): String {
        val sdf = SimpleDateFormat(pattern, Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            days > 0 -> "${days}天 ${hours % 24}小时 ${minutes % 60}分钟"
            hours > 0 -> "${hours}小时 ${minutes % 60}分钟 ${seconds % 60}秒"
            minutes > 0 -> "${minutes}分钟 ${seconds % 60}秒"
            seconds >0 -> "${seconds}秒"
            else -> "${millis}毫秒"
        }
    }
}