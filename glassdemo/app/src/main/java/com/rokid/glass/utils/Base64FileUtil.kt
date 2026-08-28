package com.rokid.glass.utils

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import android.util.Base64

object Base64FileUtil {

    // ==================== 文件转 Base64 ====================

    /**
     * 将文件转换为 Base64 字符串
     * @param filePath 文件路径
     * @param base64Flags Base64 编码标志，默认 DEFAULT
     * @return Base64 字符串
     */
    @Throws(IOException::class)
    fun fileToBase64(filePath: String, base64Flags: Int = Base64.DEFAULT): String {
        val file = File(filePath)
        if (!file.exists()) {
            throw FileNotFoundException("文件不存在: $filePath")
        }

        return fileToBase64(file, base64Flags)
    }

    /**
     * 将文件转换为 Base64 字符串
     * @param file 文件对象
     * @param base64Flags Base64 编码标志
     * @return Base64 字符串
     */
    @Throws(IOException::class)
    fun fileToBase64(file: File, base64Flags: Int = Base64.DEFAULT): String {
        FileInputStream(file).use { fis ->
            val bytes = ByteArray(file.length().toInt())
            fis.read(bytes)
            return Base64.encodeToString(bytes, base64Flags)
        }
    }

    /**
     * 将文件转换为 Base64（支持大文件，分块读取）
     * @param filePath 文件路径
     * @param chunkSize 分块大小（字节），默认 8192
     * @param base64Flags Base64 编码标志
     * @return Base64 字符串
     */
    @Throws(IOException::class)
    fun fileToBase64Large(
        filePath: String,
        chunkSize: Int = 8192,
        base64Flags: Int = Base64.DEFAULT
    ): String {
        val file = File(filePath)
        if (!file.exists()) {
            throw FileNotFoundException("文件不存在: $filePath")
        }

        val buffer = ByteArray(chunkSize)
        val outputStream = ByteArrayOutputStream()

        FileInputStream(file).use { fis ->
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
        }

        return Base64.encodeToString(outputStream.toByteArray(), base64Flags)
    }

    /**
     * 带进度回调的文件转 Base64
     * @param filePath 文件路径
     * @param onProgress 进度回调 (当前字节数, 总字节数)
     * @return Base64 字符串
     */
    @Throws(IOException::class)
    fun fileToBase64WithProgress(
        filePath: String,
        onProgress: (current: Long, total: Long) -> Unit
    ): String {
        val file = File(filePath)
        val totalSize = file.length()
        val buffer = ByteArray(8192)
        val outputStream = ByteArrayOutputStream()
        var currentSize: Long = 0

        FileInputStream(file).use { fis ->
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                currentSize += bytesRead
                onProgress(currentSize, totalSize)
            }
        }

        return Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)
    }

    // ==================== Base64 转文件 ====================

    /**
     * 将 Base64 字符串保存为文件
     * @param base64Str Base64 字符串
     * @param outputPath 输出文件路径
     * @return 保存的文件对象
     */
    @Throws(IOException::class)
    fun base64ToFile(base64Str: String, outputPath: String): File {
        // 移除可能的换行符和空格
        val cleanBase64 = base64Str.replace("[\\n\\r\\s]".toRegex(), "")

        val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
        val file = File(outputPath)

        // 确保目录存在
        file.parentFile?.mkdirs()

        FileOutputStream(file).use { fos ->
            fos.write(bytes)
            fos.flush()
        }

        return file
    }

    /**
     * 将 Base64 字符串保存为文件（支持大 Base64 字符串）
     * @param base64Str Base64 字符串
     * @param outputPath 输出文件路径
     * @param chunkSize 分块大小
     * @return 保存的文件对象
     */
    @Throws(IOException::class)
    fun base64ToFileLarge(
        base64Str: String,
        outputPath: String,
        chunkSize: Int = 8192
    ): File {
        val cleanBase64 = base64Str.replace("[\\n\\r\\s]".toRegex(), "")
        val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
        val file = File(outputPath)

        file.parentFile?.mkdirs()

        FileOutputStream(file).use { fos ->
            var offset = 0
            while (offset < bytes.size) {
                val length = minOf(chunkSize, bytes.size - offset)
                fos.write(bytes, offset, length)
                offset += length
            }
            fos.flush()
        }

        return file
    }

    /**
     * 带进度回调的 Base64 转文件
     * @param base64Str Base64 字符串
     * @param outputPath 输出文件路径
     * @param onProgress 进度回调 (当前字节数, 总字节数)
     * @return 保存的文件对象
     */
    @Throws(IOException::class)
    fun base64ToFileWithProgress(
        base64Str: String,
        outputPath: String,
        onProgress: (current: Long, total: Long) -> Unit
    ): File {
        val cleanBase64 = base64Str.replace("[\\n\\r\\s]".toRegex(), "")
        val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
        val totalSize = bytes.size.toLong()
        val file = File(outputPath)

        file.parentFile?.mkdirs()

        FileOutputStream(file).use { fos ->
            val chunkSize = 8192
            var offset = 0

            while (offset < bytes.size) {
                val length = minOf(chunkSize, bytes.size - offset)
                fos.write(bytes, offset, length)
                offset += length
                onProgress(offset.toLong(), totalSize)
            }
            fos.flush()
        }

        return file
    }

    // ==================== 辅助方法 ====================

    /**
     * 计算 Base64 编码后的大小
     * @param fileSize 原始文件大小（字节）
     * @return Base64 编码后的大小（字符数）
     */
    fun calculateBase64Size(fileSize: Long): Long {
        // Base64 编码会使数据大小增加约 33%
        return ((fileSize + 2) / 3) * 4
    }

    /**
     * 验证 Base64 字符串是否有效
     * @param base64Str Base64 字符串
     * @return 是否有效
     */
    fun isValidBase64(base64Str: String): Boolean {
        return try {
            val cleanStr = base64Str.replace("[\\n\\r\\s]".toRegex(), "")
            Base64.decode(cleanStr, Base64.DEFAULT)
            true
        } catch (e: IllegalArgumentException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 获取文件的 MIME 类型
     * @param base64Str Base64 字符串（前几个字节）
     * @return MIME 类型
     */
    fun getMimeTypeFromBase64(base64Str: String): String {
        return try {
            val cleanStr = base64Str.replace("[\\n\\r\\s]".toRegex(), "")
            val bytes = Base64.decode(cleanStr.substring(0, minOf(100, cleanStr.length)), Base64.DEFAULT)
            getMimeTypeFromBytes(bytes)
        } catch (e: Exception) {
            "application/octet-stream"
        }
    }

    private fun getMimeTypeFromBytes(bytes: ByteArray): String {
        return when {
            bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
                    bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte() -> "application/zip"
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
                    bytes[2] == 0xFF.toByte() -> "image/jpeg"
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                    bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"
            bytes.size >= 4 && bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() &&
                    bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte() -> "application/pdf"
            else -> "application/octet-stream"
        }
    }
}

// ==================== 使用示例 ====================

class Base64FileExample {

    // 示例1：基本使用
    fun example1() {
        try {
            // 文件转 Base64
            val base64String = Base64FileUtil.fileToBase64("/sdcard/test.zip")
            println("Base64 字符串长度: ${base64String.length}")

            // Base64 转文件
            val outputFile = Base64FileUtil.base64ToFile(base64String, "/sdcard/restored.zip")
            println("文件已保存到: ${outputFile.absolutePath}")

            // 验证文件是否相同
            val originalFile = File("/sdcard/test.zip")
            val restoredFile = File("/sdcard/restored.zip")
            println("文件大小相同: ${originalFile.length() == restoredFile.length()}")

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // 示例2：带进度的大文件处理
    fun example2() {
        val inputPath = "/sdcard/large_video.mp4"
        val outputPath = "/sdcard/restored_video.mp4"

        try {
            // 转换带进度显示
            val base64String = Base64FileUtil.fileToBase64WithProgress(inputPath) { current, total ->
                val progress = (current.toFloat() / total * 100).toInt()
                println("编码进度: $progress% ($current/$total bytes)")
            }

            println("编码完成，Base64 长度: ${base64String.length}")

            // 恢复带进度显示
            Base64FileUtil.base64ToFileWithProgress(base64String, outputPath) { current, total ->
                val progress = (current.toFloat() / total * 100).toInt()
                println("解码进度: $progress% ($current/$total bytes)")
            }

            println("文件恢复完成")

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // 示例3：处理 ZIP 文件
    fun example3() {
        try {
            val zipFilePath = "/sdcard/data.zip"
            val restoredPath = "/sdcard/data_restored.zip"

            // 转换 ZIP 文件
            val base64 = Base64FileUtil.fileToBase64(zipFilePath, Base64.NO_WRAP)

            // 检查 MIME 类型
            val mimeType = Base64FileUtil.getMimeTypeFromBase64(base64)
            println("检测到的文件类型: $mimeType")

            // 验证 Base64 有效性
            val isValid = Base64FileUtil.isValidBase64(base64)
            println("Base64 有效: $isValid")

            // 恢复文件
            if (isValid) {
                Base64FileUtil.base64ToFile(base64, restoredPath)
                println("ZIP 文件恢复成功")
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // 示例4：作为函数参数传递
    fun processFileAsBase64() {
        // 发送 Base64 到服务器
        fun sendToServer(base64Data: String) {
            // 模拟发送
            println("发送数据到服务器，长度: ${base64Data.length}")
        }

        // 从服务器接收 Base64
        fun receiveFromServer(): String {
            // 模拟接收
            return Base64FileUtil.fileToBase64("/sdcard/test.zip")
        }

        try {
            // 转换并发送
            val base64Data = Base64FileUtil.fileToBase64("/sdcard/test.zip", Base64.NO_WRAP)
            sendToServer(base64Data)

            // 接收并恢复
            val receivedData = receiveFromServer()
            Base64FileUtil.base64ToFile(receivedData, "/sdcard/received.zip")

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

// ==================== 扩展函数 ====================

/**
 * File 扩展函数
 */
fun File.toBase64(base64Flags: Int = Base64.DEFAULT): String {
    return Base64FileUtil.fileToBase64(this, base64Flags)
}

/**
 * String 扩展函数
 */
fun String.base64ToFile(outputPath: String): File {
    return Base64FileUtil.base64ToFile(this, outputPath)
}

// 使用扩展函数的示例
fun extensionExample() {
    val file = File("/sdcard/test.zip")

    // 使用扩展函数转换
    val base64 = file.toBase64(Base64.NO_WRAP)

    // 使用扩展函数恢复
    val restoredFile = base64.base64ToFile("/sdcard/restored.zip")
}
