package com.rokid.glass.utils

import android.os.Environment
import android.os.StatFs
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

    /**
     * 返回值：字节（Bytes）
     */
    fun getSdCardAvailableBytes(): Long {
        val path: File = Environment.getExternalStorageDirectory()
        // 文件系统统计
        val statFs = StatFs(path.absolutePath)
        // availableBlocksLong 当前 App 真正能用的块数, 系统会给 root / system 预留空间,普通 App 不能用那部分
        // blockSizeLong = 4096 bytes,文件系统最小分配单位
        // 可用块数 × 每块大小 = 当前应用还能安全写入的真实空间
        return statFs.availableBlocksLong * statFs.blockSizeLong
    }
}