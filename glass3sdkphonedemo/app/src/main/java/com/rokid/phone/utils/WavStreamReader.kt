package com.rokid.phone.utils

import com.rokid.security.phone.sdk.api.PSecuritySDK

import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 用于从 assets 读取 WAV 文件并流式传输
 */
class WavStreamSender private constructor(private val context: Context) {

    interface Callback {
        fun onProgress(sentBytes: Long, totalBytes: Long)
        fun onCompleted()
        fun onError(e: Exception)
    }

    companion object {
        @Volatile
        private var instance: WavStreamSender? = null

        fun getInstance(context: Context): WavStreamSender {
            return instance ?: synchronized(this) {
                instance ?: WavStreamSender(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * 把 assets 下的 wav 文件复制到 cache 并返回路径（给一些需要路径的 API 用）
     */
    private fun getWavFilePathFromAssets(fileName: String): String {
        val cacheFile = File(context.cacheDir, fileName)

        if (!cacheFile.exists()) {
            context.assets.open(fileName).use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            }
        }

        return cacheFile.absolutePath
    }

    /**
     * 从 assets 读取 wav 文件并流式传输
     */
    fun sendWavFromAssets(fileName: String, callback: Callback?) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val assetFilePath = getWavFilePathFromAssets(fileName)
                val file = File(assetFilePath)
                val totalSize = file.length()
                var sentSize = 0L

                file.inputStream().use { inputStream ->
                    val buffer = ByteArray(1024)
                    var read: Int
                    while (inputStream.read(buffer).also { read = it } != -1) {
                        val data = buffer.copyOf(read)
                        // 这里调用 SDK 的接口发送
                        PSecuritySDK.getMessageService()?.sendAudioStreamDataByClassicBT(data)

                        sentSize += read
                        withContext(Dispatchers.Main) {
                            callback?.onProgress(sentSize, totalSize)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    callback?.onCompleted()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback?.onError(e)
                }
            }
        }
    }
}