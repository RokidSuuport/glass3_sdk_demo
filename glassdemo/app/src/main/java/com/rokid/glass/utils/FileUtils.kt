package com.rokid.glass.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.util.Log
import android.view.TextureView
import com.rokid.glass.utils.Scopes.workScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileUtils {
    private const val TAG = "FileUtils"

    // 1. 关键修改：获取系统公共 Pictures 目录（替代原私有目录）
    private val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)

    // 2. 保持原有逻辑：创建 album 子目录（路径：/Pictures/album）
    private val albumDir = File(baseDir, "album")

    fun saveBitmap(bitmap: Bitmap) {
        workScope.launch {
            if (!albumDir.exists()) {
                albumDir.mkdirs() // 自动创建多级目录（DCIM 已存在，仅创建 album）
            }
            // 3. 保持原有逻辑：生成时间戳文件名
            val timeStamp = SimpleDateFormat("MMdd_HHmmss", Locale.getDefault()).format(Date())
            val outputFile = File(albumDir, "IMG_$timeStamp.jpg")
            FileOutputStream(outputFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
            }
            // 这里可以对获取到的位图进行处理
            Log.d(TAG, "图片宽度: ${bitmap.width} ,图片高度: ${bitmap.height}  ${Thread.currentThread().name}")
        }
    }


    /**
     * 从 assets 目录读取图片文件
     * @param context Context 对象
     * @param fileName assets 目录下的文件名（包含扩展名，如 "gonglu.png"）
     * @return Bitmap 对象，如果读取失败则返回 null
     */
    fun loadBitmapFromAssets(context: Context, fileName: String): Bitmap? {
        return try {
            context.assets.open(fileName).use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: IOException) {
            Log.e(TAG, "从 assets 读取图片失败：$fileName", e)
            null
        }
    }

    /**
     * 处理纹理视图中的位图数据
     * 使用协程创建定时任务，每隔50毫秒获取一次当前预览帧
     */
    fun handlerBitmap(textureView: TextureView) {
        // 在IO调度器中启动协程
        workScope.launch {
            // 创建无限循环的Flow，每1000毫秒发射一次数据
            infiniteIntervalFlow(1000).collect { value ->
                // 检查纹理视图是否可用
                if (!textureView.isAvailable) {
                    return@collect
                }
                // 获取当前纹理视图的位图
                val bitmap: Bitmap? = textureView.bitmap
                if (bitmap == null) {
                    return@collect
                }
                // 1. 关键修改：获取系统公共 DCIM 目录（替代原私有目录）
                val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                // 2. 保持原有逻辑：创建 album 子目录（路径：/Pictures/album）
                val albumDir = File(baseDir, "album")
                if (!albumDir.exists()) {
                    albumDir.mkdirs() // 自动创建多级目录（DCIM 已存在，仅创建 album）
                }
                // 3. 保持原有逻辑：生成时间戳文件名
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val outputFile = File(albumDir, "IMG_$timeStamp.jpg")
                FileOutputStream(outputFile).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
                }
                // 这里可以对获取到的位图进行处理
                Log.d(TAG, "图片宽度: ${bitmap.width} ,图片高度: ${bitmap.height}  ${Thread.currentThread().name}")
            }
        }
    }

    /**
     * 创建一个无限循环的 Flow，每指定毫秒发送一个递增的数据
     * @param intervalMillis 间隔时间，默认为 100 毫秒
     */
    fun infiniteIntervalFlow(intervalMillis: Long = 100): Flow<Long> = flow {
        var counter = 0L
        while (true) {
            emit(counter)  // 发送当前计数值
            counter++
            delay(intervalMillis)  // 延迟指定毫秒数
        }
    }

}