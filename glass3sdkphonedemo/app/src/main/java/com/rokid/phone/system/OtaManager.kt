package com.rokid.phone.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.blankj.utilcode.util.RomUtils
import com.google.gson.Gson
import com.rokid.phone.MyApplication
import com.rokid.phone.R
import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.phone.system.ui.SystemOtaActivity
import com.rokid.phone.system.viewmodel.SystemOtaStatus
import com.rokid.phone.system.viewmodel.SystemViewModel
import com.rokid.phone.utils.OTA_UPDATE_STATUS
import com.rokid.phone.utils.SPUtil
import com.rokid.phone.utils.SpKeyConstant
import com.rokid.phone.utils.SystemGlobalConstant
import com.rokid.security.phone.sdk.base.utils.log.L
import java.util.concurrent.atomic.AtomicBoolean

/**
 * @author yuy
 * @time   2025/12/3 19:50
 *
 * OTA 通知管理器（与前台服务完全分离）
 *
 * 关键设计：
 * - 通知 ID: 2688（高位 ID，避免冲突）
 * - 通知渠道: ota_progress_channel（独立于前台服务）
 * - 不使用 setOngoing(true)，确保可被用户清除
 * - MIUI 专项清除策略（空壳通知 + 延迟 cancel）
 */
object OtaManager {

    private val TAG = "OtaManager"

    private val notificationManager = MyApplication.instance.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // ✅ OTA 专用通知渠道（与前台服务的 "companion_device_channel" 完全隔离）
    private const val OTA_CHANNEL_ID = "ota_progress_channel"

    // ✅ OTA 通知专属 ID（高位 ID，避免与前台服务 ID=1 冲突）
    private const val OTA_NOTIFICATION_ID = 2688

    // MIUI 专项检测
    private val isMiuiDevice by lazy { detectMiui() }

    const val STATE_1 = "镜像下载"
    const val STATE_2 = "镜像同步"
    const val STATE_3 = "系统升级"

    const val STATE_1_ALL = "阶段(1/3): 镜像下载"
    const val STATE_2_ALL = "阶段(2/3): 镜像同步"
    const val STATE_3_ALL = "阶段(3/3): 系统升级"

    var isShow = false
    private var isFirstNotification = AtomicBoolean(true)

    var isState3 = false
    var progressState3 = 0f
    @Volatile
    var isInOTAActivity = false
    @Volatile
    var isOTACompleted = false

    private var mGson = Gson()

    init {
        createNotificationChannel()
        L.i(TAG, "OtaManager 初始化完成 | MIUI 适配: ${if (isMiuiDevice) "已启用" else "未启用"}")
    }

    private fun detectMiui(): Boolean {
        return RomUtils.isXiaomi()
    }

    fun start() {
        isShow = false
        cancelNotification()
        resetNotificationState()
        checkNotificationSettings()
    }

    fun checkNotificationSettings() {
        L.d(TAG, "=== 检查 OTA 通知设置 ===")

        // 检查全局通知权限
        if (!isNotificationEnabled()) {
            L.w(TAG, "❌ 全局通知权限已被禁用！请在系统设置中开启。")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = notificationManager.getNotificationChannel(OTA_CHANNEL_ID)
            if (channel != null) {
                val isEnabled = channel.importance != NotificationManager.IMPORTANCE_NONE
                L.d(TAG, "渠道ID: ${channel.id}")
                L.d(TAG, "渠道名称: ${channel.name}")
                L.d(TAG, "重要性: ${channel.importance} (${getImportanceString(channel.importance)})")
                L.d(TAG, "是否启用: ${if (isEnabled) "✅ 是" else "❌ 否（用户已关闭）"}")
                L.d(TAG, "锁屏可见性: ${channel.lockscreenVisibility}")
                L.d(TAG, "灯光/震动: ${channel.shouldShowLights()}/${channel.shouldVibrate()}")

                if (!isEnabled) {
                    L.w(TAG, "⚠️ 用户已在系统设置中禁用「OTA 进度」通知，请引导用户手动开启。")
                }

                L.d(TAG, "---------------------------------------------------------------------------------------")
            } else {
                L.e(TAG, "❌ OTA 通知渠道不存在！可能未调用 createNotificationChannel()")
            }
        } else {
            L.d(TAG, "✅ 设备 API < 26，无通知渠道概念，仅依赖全局通知开关")
        }
    }

    // 辅助方法：将 importance 转为可读字符串
    private fun getImportanceString(importance: Int): String {
        return when (importance) {
            NotificationManager.IMPORTANCE_NONE -> "NONE (已禁用)"
            NotificationManager.IMPORTANCE_MIN -> "MIN"
            NotificationManager.IMPORTANCE_LOW -> "LOW"
            NotificationManager.IMPORTANCE_DEFAULT -> "DEFAULT"
            NotificationManager.IMPORTANCE_HIGH -> "HIGH"
            NotificationManager.IMPORTANCE_MAX -> "MAX"
            else -> "UNKNOWN ($importance)"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.getNotificationChannel(OTA_CHANNEL_ID)?.let {
                notificationManager.deleteNotificationChannel(OTA_CHANNEL_ID)
            }

            val channel = NotificationChannel(
                OTA_CHANNEL_ID,
                "OTA 进度",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "系统升级进度通知"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    // 在 OtaManager.kt 中添加工具方法
    private fun createContentIntent(): PendingIntent {
        val intent = Intent(MyApplication.instance, SystemOtaActivity::class.java).apply {
            this.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // 可选：传递额外数据
            putExtra("from_notification", true)
        }
        return PendingIntent.getActivity(
            MyApplication.instance,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun isNotificationEnabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            notificationManager.areNotificationsEnabled()
        } else {
            true
        }
    }

    fun isChannelEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = notificationManager.getNotificationChannel(OTA_CHANNEL_ID)
            return channel?.importance ?: NotificationManager.IMPORTANCE_NONE > NotificationManager.IMPORTANCE_NONE
        }
        return notificationManager.areNotificationsEnabled()
    }

    fun showDownloadProgress(progress: Int, showMsg: String) {
        if (!isNotificationEnabled() || !isChannelEnabled()) return
        if (!isShow || progress !in 0..100) return

        val builder = NotificationCompat.Builder(MyApplication.instance, OTA_CHANNEL_ID)
            .setContentTitle("$showMsg $progress %")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setProgress(100, progress, false)
            .setContentIntent(createContentIntent())
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (isFirstNotification.compareAndSet(true, false)) {
            builder
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                .setOnlyAlertOnce(false)
        } else {
            builder.setOnlyAlertOnce(true)
        }

        notificationManager.notify(OTA_NOTIFICATION_ID, builder.build())
        L.v(TAG, "更新 OTA 进度: $showMsg $progress%")
    }

    fun showCompletion() {
        if (!isNotificationEnabled() || !isChannelEnabled() || !isShow) return

        val builder = NotificationCompat.Builder(MyApplication.instance, OTA_CHANNEL_ID)
            .setContentTitle("升级成功，设备将重启")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(createContentIntent())
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setOnlyAlertOnce(false)

        notificationManager.notify(OTA_NOTIFICATION_ID, builder.build())
        L.i(TAG, "显示 OTA 完成通知")

//        Handler(Looper.getMainLooper()).postDelayed({
//            cancelNotification()
//        }, 2000)
    }

    fun showFailure(error: String) {
        if (!isNotificationEnabled() || !isChannelEnabled() || !isShow) return

        val builder = NotificationCompat.Builder(MyApplication.instance, OTA_CHANNEL_ID)
            .setContentTitle("系统升级失败")
            .setContentText(error)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(createContentIntent())
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setOnlyAlertOnce(false)

        notificationManager.notify(OTA_NOTIFICATION_ID, builder.build())
        L.e(TAG, "显示 OTA 失败通知: $error")

//        Handler(Looper.getMainLooper()).postDelayed({
//            cancelNotification()
//        }, 3000)
    }

    // ✅ MIUI 专项清除：确保通知真正消失
    fun cancelNotification() {
        if (isMiuiDevice) {
            // 步骤1: 发送空壳通知覆盖
            val clearNotif = NotificationCompat.Builder(MyApplication.instance, OTA_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("")
                .setContentText("")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setAutoCancel(true)
                .setTimeoutAfter(150)
                .build()

            notificationManager.notify(OTA_NOTIFICATION_ID, clearNotif)

            // 步骤2: 延迟取消
            Handler(Looper.getMainLooper()).postDelayed({
                notificationManager.cancel(OTA_NOTIFICATION_ID)
                L.i(TAG, "MIUI: OTA 通知已清除 (ID=$OTA_NOTIFICATION_ID)")
            }, 200)
        } else {
            notificationManager.cancel(OTA_NOTIFICATION_ID)
            L.i(TAG, "OTA 通知已清除 (ID=$OTA_NOTIFICATION_ID)")
        }
    }

    fun resetNotificationState() {
        isFirstNotification.set(true)
    }

    fun updateState3(message: String, msg: String) {
        try {
            L.d(TAG, "$msg, 收到第三阶段进度通知 $message")

            val status = mGson.fromJson(message, SystemOtaStatus::class.java)

            if (status.status == OTA_UPDATE_STATUS.OTA_CORE_SYSTEM_START) {   // 眼镜升级中

                isState3 = true
                progressState3 = status.process.toFloat()

                showDownloadProgress(status.process, STATE_3_ALL)
            } else if (status.status == OTA_UPDATE_STATUS.OTA_CORE_SYSTEM_REBOOT) {  // 升级完成重启
                showCompletion()
                if (isInOTAActivity) {
                    isOTACompleted = true
                } else { // 不在升级页面，直接还原 SystemViewModel，避免再次进入状态不正确
                    SystemViewModel.release()
                }
            }
        } catch (e: Exception) {
            L.e(TAG, "消息转换失败 error: ${e.message}")
        }
    }

    fun resetState3() {
        isState3 = false
        progressState3 = 0f
    }

    fun checkResetP2P() {
        val isReset = SPUtil.getInstance(MyApplication.instance).getBoolean(
            SystemGlobalConstant.deviceId + SpKeyConstant.OTA_IS_CLOSE_P2P_KEEP_CONNECT,
            false
        )

        if (isReset) {
            PSecuritySDK.getWifiP2PClientService()?.getIP2PConnectControl()?.setKeepP2PConnect(false) {
                L.i(TAG, "关闭 OTA 期间开启的 P2P 常连: $it")
                if (it) {
                    SPUtil.getInstance(MyApplication.instance).putBoolean(
                        SystemGlobalConstant.deviceId + SpKeyConstant.OTA_IS_CLOSE_P2P_KEEP_CONNECT,
                        false
                    )
                }
            }
        }
    }
}