package com.rokid.glass.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

object ApkInstallUtil {
    private const val TAG = "ApkInstallUtil"
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    fun isApkFile(filePath: String): Boolean {
        return filePath.endsWith(".apk", ignoreCase = true)
    }

    /**
     * APK 下载/接收完成后调用，自动拉起系统安装器执行覆盖安装。
     *
     * 注意：普通三方应用无法完全静默安装 APK，系统仍可能要求用户确认安装。
     * 如果设备侧把当前应用做成系统/特权应用并授予安装权限，系统安装器可能直接完成更新。
     */
    fun installApk(context: Context, apkPath: String): Boolean {
        val apkFile = File(apkPath)
        if (!apkFile.exists() || !apkFile.isFile) {
            Log.e(TAG, "installApk failed, file not found: $apkPath")
            return false
        }

        if (!isApkFile(apkPath)) {
            Log.d(TAG, "skip non-apk file: $apkPath")
            return false
        }

        if (!context.packageManager.canRequestPackageInstalls()) {
            openInstallPermissionSettings(context)
            return false
        }

        return try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "installApk failed: $apkPath", e)
            false
        }
    }

    private fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
