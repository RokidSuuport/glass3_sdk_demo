package com.rokid.glass.utils

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.rokid.glass.MyApplication
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ImageFileUtils {
    private const val TAG = "ImageFileUtils"
    private const val ALBUM_NAME = "album"

    fun imageFileName(date: Date): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(date)
        return "IMG_$timestamp.jpg"
    }

    fun saveJpeg(bytes: ByteArray): File? {
        var bitmap: Bitmap? = null
        return try {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            val imageFile = createImageFile() ?: return null
            val saved = FileOutputStream(imageFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)
            }
            if (!saved) {
                imageFile.delete()
                return null
            }
            scanPublicFile(imageFile)
            imageFile
        } catch (error: Exception) {
            Log.e(TAG, "保存图像失败: ${error.message}", error)
            null
        } finally {
            bitmap?.recycle()
        }
    }

    private fun createImageFile(): File? {
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val albumDir = File(picturesDir, ALBUM_NAME)
        if ((!albumDir.exists() && !albumDir.mkdirs()) || !albumDir.isDirectory) return null
        return File(albumDir, imageFileName(Date()))
    }

    private fun scanPublicFile(file: File) {
        val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
            data = Uri.fromFile(file)
        }
        MyApplication.getContext().sendBroadcast(intent)
    }
}
