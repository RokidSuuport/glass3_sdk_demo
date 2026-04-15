package com.rokid.phone.utils

import java.io.File
import java.text.DecimalFormat

object FileSizeUtil {

    private const val KB = 1024.0
    private const val MB = KB * 1024
    private const val GB = MB * 1024

    private val decimalFormat = DecimalFormat("#.##")

    /**
     * 获取文件大小并自动选择合适单位
     */
    fun getFileSizeAuto(file: File): String {
        return if (!file.exists()) {
            "0 B"
        } else {
            formatFileSize(file.length().toDouble())
        }
    }

    /**
     * 获取文件大小（字节）
     */
    fun getFileSizeBytes(file: File): Long {
        return if (file.exists()) file.length() else 0
    }

    /**
     * 获取文件大小（KB）
     */
    fun getFileSizeKB(file: File): Double {
        return if (file.exists()) file.length() / KB else 0.0
    }

    /**
     * 获取文件大小（MB）
     */
    fun getFileSizeMB(file: File): Double {
        return if (file.exists()) file.length() / MB else 0.0
    }

    /**
     * 获取文件大小（GB）
     */
    fun getFileSizeGB(file: File): Double {
        return if (file.exists()) file.length() / GB else 0.0
    }

    /**
     * 格式化文件大小，自动选择单位
     */
    fun formatFileSize(sizeInBytes: Double): String {
        return when {
            sizeInBytes < KB -> "${decimalFormat.format(sizeInBytes)} B"
            sizeInBytes < MB -> "${decimalFormat.format(sizeInBytes / KB)} KB"
            sizeInBytes < GB -> "${decimalFormat.format(sizeInBytes / MB)} MB"
            else -> "${decimalFormat.format(sizeInBytes / GB)} GB"
        }
    }

    /**
     * 格式化文件大小，指定单位
     */
    fun formatFileSize(sizeInBytes: Double, unit: SizeUnit): String {
        return when (unit) {
            SizeUnit.BYTES -> "${decimalFormat.format(sizeInBytes)} B"
            SizeUnit.KB -> "${decimalFormat.format(sizeInBytes / KB)} KB"
            SizeUnit.MB -> "${decimalFormat.format(sizeInBytes / MB)} MB"
            SizeUnit.GB -> "${decimalFormat.format(sizeInBytes / GB)} GB"
        }
    }

    enum class SizeUnit {
        BYTES, KB, MB, GB
    }
}