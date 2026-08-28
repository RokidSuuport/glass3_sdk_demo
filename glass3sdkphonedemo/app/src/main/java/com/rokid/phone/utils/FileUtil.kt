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
     * 否则获取当前APP内部存储目录  /data/user/0/package_name/cache/
     */
    fun getAppPath(context: Context, dirName: String = ""): File {
        return if (sdCardIsAvailable(context)) {
            // 当前APP外部存储目录 storage/emulated/0/Android/data/package_name/files/
            val path = context.getExternalFilesDir(dirName)
            createOrExistsDir(path)
            path!!
        } else {
            // 当前APP内部存储目录    /data/user/0/package_name/cache/***
            val file = File(context.cacheDir, dirName)
            file.mkdirs()
            file
        }
    }

    /**
     * 判断目录是否存在，不存在则判断是否创建成功
     * @param file 文件
     * @return `true`: 存在或创建成功<br></br>`false`: 不存在或创建失败
     */
    fun createOrExistsDir(file: File?): Boolean {
        return file != null && if (file.exists()) file.isDirectory else file.mkdirs()
    }

    /**
     * @param context 上下文
     * @param dirName 如果为空,返回 /data/user/0/package_name/cache
     *                如果非空,返回 /data/user/0/package_name/cache/dirName
     */
    fun getAppInnerCacheDir(context: Context, dirName: String? = null): File {
        if (dirName == null) {
            return context.cacheDir
        }
        //当前APP内部存储目录
        val file = File(context.cacheDir, dirName)
        if (!file.exists()) {
            file.mkdirs()
        }
        return file
    }

    /**
     * SD卡是否可用
     */
    private fun sdCardIsAvailable(context: Context): Boolean {
        return if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            context.getExternalFilesDir("")?.canWrite() == true
        } else false
    }


    /**
     * 获取文件夹中所有文件的路径（包括子目录）
     * @param dirPath 目标文件夹路径
     * @param includeSubDir 是否包含子目录中的文件（默认包含）
     * @return 所有文件的绝对路径列表
     */
    fun getAllAlbumInfo(
        dirPath: String,
        includeSubDir: Boolean = true
    ): MutableList<AlbumInfo> {
        val dir = File(dirPath)
        // 快速判断：文件夹不存在或不是目录，直接返回空列表
        if (!dir.exists() || !dir.isDirectory) {
            return mutableListOf()
        }

        val albumInfos = mutableListOf<AlbumInfo>()
        val files = dir.listFiles() ?: return mutableListOf()

        for (file in files) {
            if (file.isFile) {
                // 是文件：创建AlbumInfo对象并设置路径和时间
                val imageUri = FileProvider.getUriForFile(MyApplication.instance, "${MyApplication.instance.packageName}.fileprovider", file)
                val albumInfo = AlbumInfo().apply {
                    fileBrowserPath = imageUri.toString().replace("content:", "https:")
                    filePath = file.absolutePath
                    fileTime = file.lastModified() // 获取文件最后修改时间（毫秒时间戳）
                    fileMd5 = getFileMD5(file)?: ""
                }
                albumInfos.add(albumInfo)
            } else if (includeSubDir && file.isDirectory) {
                // 是目录且需要递归：添加子目录的文件信息
                albumInfos.addAll(getAllAlbumInfo(file.absolutePath, includeSubDir))
            }
        }
        return albumInfos
    }



    fun getFileLastModifiedTime(filePath: String): Long {
        val file = File(filePath)
        return if (file.exists() && file.isFile) {
            file.lastModified() // 返回最后修改时间（毫秒时间戳）
        } else {
            -1L
        }
    }

}
