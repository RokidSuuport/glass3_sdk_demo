package com.rokid.phone.utils

import android.content.Context
import android.os.Environment
import com.rokid.security.phone.sdk.base.utils.log.L
import java.io.File
import java.io.IOException


/** 统一创建和访问 Demo 使用的文件目录。 */
object FolderUtils {
    private const val APP_NAME = "PhoneSdk" // 应用文件根目录名称
    private const val DIR_VIDEO = "Videos"
    private const val DIR_IMAGE = "Images"
    private const val DIR_AUDIO = "Audios"
    private const val DIR_DOCUMENT = "Documents"
    private const val DIR_TEMP = "Temp"
    private const val DIR_SYSTEM = "System"

    /**
     * 获取应用主目录
     * @param context 上下文
     * @return 主目录文件对象
     */
    fun getAppMainDirectory(context: Context): File {
        // 优先使用内部存储
        val directory =File(context.filesDir, APP_NAME)
        createDirectoryIfNotExists(directory)
        return directory
    }

    /**
     * 获取视频文件夹
     * @param context 上下文
     * @return 视频文件夹文件对象
     */
    fun getVideoDirectory(context: Context): File {
        val directory = File(getAppMainDirectory(context), DIR_VIDEO)
        createDirectoryIfNotExists(directory)
        return directory
    }

    /**
     * 获取图片文件夹
     * @param context 上下文
     * @return 图片文件夹文件对象
     */
    fun getImageDirectory(context: Context): File {
        val directory = File(getAppMainDirectory(context), DIR_IMAGE)
        createDirectoryIfNotExists(directory)
        return directory
    }

    /**
     * 获取音频文件夹
     * @param context 上下文
     * @return 音频文件夹文件对象
     */
    fun getAudioDirectory(context: Context): File {
        val directory = File(getAppMainDirectory(context), DIR_AUDIO)
        createDirectoryIfNotExists(directory)
        return directory
    }

    /**
     * 获取文档文件夹
     * @param context 上下文
     * @return 文档文件夹文件对象
     */
    fun getDocumentDirectory(context: Context): File {
        val directory = File(getAppMainDirectory(context), DIR_DOCUMENT)
        createDirectoryIfNotExists(directory)
        return directory
    }

    /**
     * 获取临时文件夹
     * @param context 上下文
     * @return 临时文件夹文件对象
     */
    fun getTempDirectory(context: Context): File {
        val directory = File(getAppMainDirectory(context), DIR_TEMP)
        createDirectoryIfNotExists(directory)
        return directory
    }

    fun getSystemDirectory(context: Context): File {
        val directory = File(getAppMainDirectory(context), DIR_SYSTEM)
        createDirectoryIfNotExists(directory)
        return directory
    }

    /**
     * 获取应用主目录路径
     * @param context 上下文
     * @return 主目录路径字符串
     */
    fun getAppMainPath(context: Context): String {
        return getAppMainDirectory(context).path
    }

    /**
     * 获取视频文件夹路径
     * @param context 上下文
     * @return 视频文件夹路径字符串
     */
    fun getVideoPath(context: Context): String {
        return getVideoDirectory(context).path
    }

    /**
     * 获取图片文件夹路径
     * @param context 上下文
     * @return 图片文件夹路径字符串
     */
    fun getImagePath(context: Context): String {
        return getImageDirectory(context).path
    }


    fun getImagePath(context: Context, name: String): String {
        val file = File(getImageDirectory(context), name)

        return try {
            if (file.exists()) {
                // 检查文件类型和权限
                if (file.isDirectory) throw IOException("路径指向目录而非文件: ${file.path}")
                if (!file.canRead()) throw IOException("文件不可读: ${file.path}")
            } else {
                // 创建空文件
                if (!file.createNewFile()) {
                    throw IOException("文件创建失败: ${file.path}")
                }
            }
            file.path
        } catch (e: IOException) {
            L.e("FolderUtils", "获取图片路径失败: ${e.message}")
            throw kotlin.RuntimeException("无法获取或创建图片文件", e)
        }
    }

    /**
     * 获取音频文件夹路径
     * @param context 上下文
     * @return 音频文件夹路径字符串
     */
    fun getAudioPath(context: Context): String {
        return getAudioDirectory(context).path
    }

    /**
     * 获取文档文件夹路径
     * @param context 上下文
     * @return 文档文件夹路径字符串
     */
    fun getDocumentPath(context: Context): String {
        return getDocumentDirectory(context).path
    }

    /**
     * 获取临时文件夹路径
     * @param context 上下文
     * @return 临时文件夹路径字符串
     */
    fun getTempPath(context: Context): String {
        return getTempDirectory(context).path
    }

    /**
     * 检查外部存储是否可写
     * @return 可写返回true，否则返回false
     */
    private fun isExternalStorageWritable(): Boolean {
        val state = Environment.getExternalStorageState()
        return Environment.MEDIA_MOUNTED == state
    }

    /**
     * 如果目录不存在则创建
     * @param directory 目录文件对象
     * @return 创建成功或已存在返回true，否则返回false
     */
    private fun createDirectoryIfNotExists(directory: File): Boolean {
        return directory.exists() || directory.mkdirs()
    }
}
