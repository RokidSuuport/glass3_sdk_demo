package com.rokid.phone.utils

import com.rokid.phone.utils.demoString
import com.rokid.phone.R

import android.content.Context
import android.os.Environment
import com.rokid.security.phone.sdk.base.utils.log.L
import java.io.File
import java.io.IOException


/**
 * 统一创建和访问 Demo 使用的文件目录。
 * Create and access the Demo's file directories consistently.
 */
object FolderUtils {
    // 应用文件根目录名称
    // Name of the application's root file directory.
    private const val APP_NAME = "PhoneSdk"
    private const val DIR_VIDEO = "Videos"
    private const val DIR_IMAGE = "Images"
    private const val DIR_AUDIO = "Audios"
    private const val DIR_DOCUMENT = "Documents"
    private const val DIR_TEMP = "Temp"
    private const val DIR_SYSTEM = "System"

    /**
     * 获取应用主目录
     * Get the application's main directory.
     * @param context 上下文
     * Context.
     * @return 主目录文件对象
     * File object for the main directory.
     */
    fun getAppMainDirectory(context: Context): File {
        // 优先使用内部存储
        // Prefer internal storage.
        val directory =File(context.filesDir, APP_NAME)
        createDirectoryIfNotExists(directory)
        return directory
    }

    /**
     * 获取视频文件夹
     * Get the video directory.
     * @param context 上下文
     * Context.
     * @return 视频文件夹文件对象
     * File object for the video directory.
     */
    fun getVideoDirectory(context: Context): File {
        val directory = File(getAppMainDirectory(context), DIR_VIDEO)
        createDirectoryIfNotExists(directory)
        return directory
    }

    /**
     * 获取图片文件夹
     * Get the image directory.
     * @param context 上下文
     * Context.
     * @return 图片文件夹文件对象
     * File object for the image directory.
     */
    fun getImageDirectory(context: Context): File {
        val directory = File(getAppMainDirectory(context), DIR_IMAGE)
        createDirectoryIfNotExists(directory)
        return directory
    }

    /**
     * 获取音频文件夹
     * Get the audio directory.
     * @param context 上下文
     * Context.
     * @return 音频文件夹文件对象
     * File object for the audio directory.
     */
    fun getAudioDirectory(context: Context): File {
        val directory = File(getAppMainDirectory(context), DIR_AUDIO)
        createDirectoryIfNotExists(directory)
        return directory
    }

    /**
     * 获取文档文件夹
     * Get the document directory.
     * @param context 上下文
     * Context.
     * @return 文档文件夹文件对象
     * File object for the document directory.
     */
    fun getDocumentDirectory(context: Context): File {
        val directory = File(getAppMainDirectory(context), DIR_DOCUMENT)
        createDirectoryIfNotExists(directory)
        return directory
    }

    /**
     * 获取临时文件夹
     * Get the temporary directory.
     * @param context 上下文
     * Context.
     * @return 临时文件夹文件对象
     * File object for the temporary directory.
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
     * Get the application's main directory path.
     * @param context 上下文
     * Context.
     * @return 主目录路径字符串
     * Main directory path string.
     */
    fun getAppMainPath(context: Context): String {
        return getAppMainDirectory(context).path
    }

    /**
     * 获取视频文件夹路径
     * Get the video directory path.
     * @param context 上下文
     * Context.
     * @return 视频文件夹路径字符串
     * Video directory path string.
     */
    fun getVideoPath(context: Context): String {
        return getVideoDirectory(context).path
    }

    /**
     * 获取图片文件夹路径
     * Get the image directory path.
     * @param context 上下文
     * Context.
     * @return 图片文件夹路径字符串
     * Image directory path string.
     */
    fun getImagePath(context: Context): String {
        return getImageDirectory(context).path
    }


    fun getImagePath(context: Context, name: String): String {
        val file = File(getImageDirectory(context), name)

        return try {
            if (file.exists()) {
                // 检查文件类型和权限
                // Check the file type and permissions.
                if (file.isDirectory) throw IOException(demoString(R.string.demo_path_is_a_directory_not_a_file, file.path))
                if (!file.canRead()) throw IOException(demoString(R.string.demo_file_is_not_readable, file.path))
            } else {
                // 创建空文件
                // Create an empty file.
                if (!file.createNewFile()) {
                    throw IOException(demoString(R.string.demo_failed_to_create_file, file.path))
                }
            }
            file.path
        } catch (e: IOException) {
            L.e("FolderUtils", "获取图片路径失败: ${e.message}")
            throw kotlin.RuntimeException(demoString(R.string.demo_unable_to_get_or_create_image_file), e)
        }
    }

    /**
     * 获取音频文件夹路径
     * Get the audio directory path.
     * @param context 上下文
     * Context.
     * @return 音频文件夹路径字符串
     * Audio directory path string.
     */
    fun getAudioPath(context: Context): String {
        return getAudioDirectory(context).path
    }

    /**
     * 获取文档文件夹路径
     * Get the document directory path.
     * @param context 上下文
     * Context.
     * @return 文档文件夹路径字符串
     * Document directory path string.
     */
    fun getDocumentPath(context: Context): String {
        return getDocumentDirectory(context).path
    }

    /**
     * 获取临时文件夹路径
     * Get the temporary directory path.
     * @param context 上下文
     * Context.
     * @return 临时文件夹路径字符串
     * Temporary directory path string.
     */
    fun getTempPath(context: Context): String {
        return getTempDirectory(context).path
    }

    /**
     * 检查外部存储是否可写
     * Check whether external storage is writable.
     * @return 可写返回true，否则返回false
     * true if writable, otherwise false.
     */
    private fun isExternalStorageWritable(): Boolean {
        val state = Environment.getExternalStorageState()
        return Environment.MEDIA_MOUNTED == state
    }

    /**
     * 如果目录不存在则创建
     * Create the directory if it does not exist.
     * @param directory 目录文件对象
     * File object for the directory.
     * @return 创建成功或已存在返回true，否则返回false
     * true if creation succeeds or the directory already exists, otherwise false.
     */
    private fun createDirectoryIfNotExists(directory: File): Boolean {
        return directory.exists() || directory.mkdirs()
    }
}
