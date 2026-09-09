package com.rokid.glass.mediastream.sender.capture

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Environment
import java.io.File
import java.io.FileOutputStream

/**
 * 将调用方已经复制并独立持有的 NV21 帧保存为应用专属 JPEG 文件。
 *
 * 本类不接收或持有 SDK 的帧对象，避免相机回调结束后继续访问被复用的缓冲区。
 * 调用方应在相机回调内完成字节复制，再在自己的 I/O 线程调用 [write]。
 */
internal class Nv21SnapshotWriter(context: Context) {

    private val applicationContext = context.applicationContext

    /**
     * 将一帧完整 NV21 数据压缩为 JPEG，并仅在文件完整且非空时返回。
     */
    fun write(data: ByteArray, width: Int, height: Int): File {
        validateFrame(data = data, width = width, height = height)

        val picturesDirectory = requireNotNull(
            applicationContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
        ) { "应用专属 Pictures 目录不可用" }
        check(picturesDirectory.isDirectory || picturesDirectory.mkdirs()) {
            "无法创建应用专属 Pictures 目录"
        }

        val output = File.createTempFile(
            "glass3-frame-${System.currentTimeMillis()}-",
            ".jpg",
            picturesDirectory,
        ).absoluteFile

        try {
            val compressed = FileOutputStream(output).use { stream ->
                val result = YuvImage(
                    data,
                    ImageFormat.NV21,
                    width,
                    height,
                    null,
                ).compressToJpeg(
                    Rect(0, 0, width, height),
                    JPEG_QUALITY,
                    stream,
                )
                stream.flush()
                result
            }

            check(compressed) { "NV21 帧压缩为 JPEG 失败" }
            check(output.isFile && output.length() > 0L) { "JPEG 文件为空" }
            return output
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    private fun validateFrame(data: ByteArray, width: Int, height: Int) {
        require(width > 0 && height > 0) { "NV21 尺寸必须为正数" }
        require(width % 2 == 0 && height % 2 == 0) { "NV21 尺寸必须为偶数" }

        // Int 正数相乘先提升为 Long，避免尺寸乘法在 Int 中溢出。
        // NV21 包含一份 Y 平面和半份交错 VU 平面，总长度为像素数的 3/2。
        val pixelCount = width.toLong() * height.toLong()
        val expectedLength = pixelCount + pixelCount / 2L
        require(expectedLength <= Int.MAX_VALUE.toLong()) { "NV21 帧长度超出 ByteArray 上限" }
        require(data.size.toLong() == expectedLength) {
            "NV21 数据长度不匹配：期望 $expectedLength，实际 ${data.size}"
        }
    }

    private companion object {
        const val JPEG_QUALITY = 90
    }
}
