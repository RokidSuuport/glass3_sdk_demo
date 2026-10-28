package com.rokid.phone.utils

import android.content.Context
import android.os.Environment
import androidx.core.content.FileProvider
import com.rokid.phone.MyApplication
import com.rokid.phone.system.AlbumInfo
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.also
import kotlin.apply
import kotlin.collections.joinToString
import kotlin.text.format
import kotlin.text.replace

object FileUtil {


    fun getFileMD5(file: File): String? {
        if (!file.isFile) return null
        var inputStream: FileInputStream? = null
        try {
            val digest = MessageDigest.getInstance("MD5")
            inputStream = FileInputStream(file)
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
            val hashBytes = digest.digest()
            return hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            inputStream?.close()
        }
    }

    /**
     * 如果可以得到当前APP外部存储目录则返回  /storage/emulated/0/Android/data/package_name/files
     * Return /storage/emulated/0/Android/data/package_name/files if the application's external storage directory is available.
     * 否则获取当前APP内部存储目录  /data/user/0/package_name/cache/
     * Otherwise, use the application's internal storage directory: /data/user/0/package_name/cache/.
     */
    fun getAppPath(context: Context, dirName: String = ""): File {
        return if (sdCardIsAvailable(context)) {
            // 当前APP外部存储目录 storage/emulated/0/Android/data/package_name/files/
            // Application external storage directory: storage/emulated/0/Android/data/package_name/files/.
            val path = context.getExternalFilesDir(dirName)
            createOrExistsDir(path)
            path!!
        } else {
            // 当前APP内部存储目录    /data/user/0/package_name/cache/***
            // Application internal storage directory: /data/user/0/package_name/cache/***.
            val file = File(context.cacheDir, dirName)
            file.mkdirs()
            file
        }
    }

    /**
     * 判断目录是否存在，不存在则判断是否创建成功
     * Check whether the directory exists or can be created successfully.
     * @param file 文件
     * File.
     * @return `true`: 存在或创建成功<br></br>`false`: 不存在或创建失败
     * `true` if it exists or is created successfully; `false` if it does not exist or creation fails.
     */
    fun createOrExistsDir(file: File?): Boolean {
        return file != null && if (file.exists()) file.isDirectory else file.mkdirs()
    }

    /**
     * @param context 上下文
     * Context.
     * @param dirName 如果为空,返回 /data/user/0/package_name/cache
     * If empty, return /data/user/0/package_name/cache.
     *                如果非空,返回 /data/user/0/package_name/cache/dirName
     *                If nonempty, return /data/user/0/package_name/cache/dirName.
     */
    fun getAppInnerCacheDir(context: Context, dirName: String? = null): File {
        if (dirName == null) {
            return context.cacheDir
        }
        //当前APP内部存储目录
        // Application internal storage directory.
        val file = File(context.cacheDir, dirName)
        if (!file.exists()) {
            file.mkdirs()
        }
        return file
    }

    /**
     * SD卡是否可用
     * Whether the SD card is available.
     */
    private fun sdCardIsAvailable(context: Context): Boolean {
        return if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            context.getExternalFilesDir("")?.canWrite() == true
        } else false
    }


    /**
     * 获取文件夹中所有文件的路径（包括子目录）
     * Get paths of all files in the directory, including subdirectories.
     * @param dirPath 目标文件夹路径
     * Target directory path.
     * @param includeSubDir 是否包含子目录中的文件（默认包含）
     * Whether to include files in subdirectories (included by default).
     * @return 所有文件的绝对路径列表
     * List of absolute paths for all files.
     */
    fun getAllAlbumInfo(
        dirPath: String,
        includeSubDir: Boolean = true
    ): MutableList<AlbumInfo> {
        val dir = File(dirPath)
        // 快速判断：文件夹不存在或不是目录，直接返回空列表
        // Return an empty list immediately if the directory does not exist or is not a directory.
        if (!dir.exists() || !dir.isDirectory) {
            return mutableListOf()
        }

        val albumInfos = mutableListOf<AlbumInfo>()
        val files = dir.listFiles() ?: return mutableListOf()

        for (file in files) {
            if (file.isFile) {
                // 是文件：创建AlbumInfo对象并设置路径和时间
                // For a file, create an AlbumInfo object and set its path and timestamp.
                val imageUri = FileProvider.getUriForFile(MyApplication.instance, "${MyApplication.instance.packageName}.fileprovider", file)
                val albumInfo = AlbumInfo().apply {
                    fileBrowserPath = imageUri.toString().replace("content:", "https:")
                    filePath = file.absolutePath
                    // 获取文件最后修改时间（毫秒时间戳）
                    // Get the file's last modification time as a millisecond timestamp.
                    fileTime = file.lastModified()
                    fileMd5 = getFileMD5(file)?: ""
                }
                albumInfos.add(albumInfo)
            } else if (includeSubDir && file.isDirectory) {
                // 是目录且需要递归：添加子目录的文件信息
                // For a directory requiring recursion, add the file information from its subdirectories.
                albumInfos.addAll(getAllAlbumInfo(file.absolutePath, includeSubDir))
            }
        }
        return albumInfos
    }



    fun getFileLastModifiedTime(filePath: String): Long {
        val file = File(filePath)
        return if (file.exists() && file.isFile) {
            // 返回最后修改时间（毫秒时间戳）
            // Return the last modification time as a millisecond timestamp.
            file.lastModified()
        } else {
            -1L
        }
    }

}
