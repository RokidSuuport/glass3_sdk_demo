package com.rokid.glass.mediastream.sender.permission

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

internal fun requiredMediaPermissions(
    videoEnabled: Boolean,
    audioEnabled: Boolean,
): List<String> = buildList {
    if (videoEnabled) add(Manifest.permission.CAMERA)
    if (audioEnabled) add(Manifest.permission.RECORD_AUDIO)
}

internal fun interface PermissionGrantChecker {
    fun isGranted(permission: String): Boolean
}

internal fun interface PermissionLauncher {
    fun launch(permissions: Array<String>)
}

/**
 * 统一处理相机/录音动态权限，并保证同一时间只存在一个待处理请求。
 *
 * Activity 只需要在创建时调用 [register]，开始媒体功能前调用 [request]。这里不会请求
 * INTERNET、ACCESS_NETWORK_STATE 或 MODIFY_AUDIO_SETTINGS，因为它们不是运行时危险权限。
 */
internal class MediaPermissionCoordinator(
    private val permissionChecker: PermissionGrantChecker,
    private val permissionLauncher: PermissionLauncher,
) {
    private data class PendingRequest(
        val permissions: List<String>,
        val onGranted: () -> Unit,
        val onDenied: () -> Unit,
    )

    private val lock = Any()
    private var pendingRequest: PendingRequest? = null

    /**
     * @return `false` 表示已有权限弹窗等待结果，本次操作未替换原请求。
     */
    fun request(
        videoEnabled: Boolean,
        audioEnabled: Boolean,
        onGranted: () -> Unit,
        onDenied: () -> Unit,
    ): Boolean {
        val missingPermissions = requiredMediaPermissions(videoEnabled, audioEnabled)
            .filterNot(permissionChecker::isGranted)
        val shouldLaunch = synchronized(lock) {
            if (pendingRequest != null) return false
            if (missingPermissions.isEmpty()) {
                false
            } else {
                pendingRequest = PendingRequest(missingPermissions, onGranted, onDenied)
                true
            }
        }
        if (!shouldLaunch) {
            onGranted()
            return true
        }

        try {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        } catch (error: Throwable) {
            synchronized(lock) { pendingRequest = null }
            throw error
        }
        return true
    }

    fun onPermissionResult(results: Map<String, Boolean>) {
        val pending = synchronized(lock) {
            pendingRequest.also { pendingRequest = null }
        } ?: return
        val granted = pending.permissions.all { permission ->
            results[permission] == true || permissionChecker.isGranted(permission)
        }
        if (granted) pending.onGranted() else pending.onDenied()
    }

    companion object {
        fun register(activity: ComponentActivity): MediaPermissionCoordinator {
            lateinit var coordinator: MediaPermissionCoordinator
            val launcher = activity.registerForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { result -> coordinator.onPermissionResult(result) }
            coordinator = MediaPermissionCoordinator(
                permissionChecker = PermissionGrantChecker { permission ->
                    ContextCompat.checkSelfPermission(activity, permission) ==
                        PackageManager.PERMISSION_GRANTED
                },
                permissionLauncher = PermissionLauncher(launcher::launch),
            )
            return coordinator
        }
    }
}
