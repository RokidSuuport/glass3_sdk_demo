package com.rokid.phone.notification.service

import android.app.KeyguardManager
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.rokid.phone.data.GlobalData
import com.rokid.phone.utils.SPUtil
import com.rokid.phone.utils.SpKeyConstant.PREF_KEY_ENABLE_ALL_NOTIFICATION
import com.rokid.phone.utils.SpKeyConstant.PREF_KEY_ENABLE_NOTIFICATION_MESSAGE
import com.rokid.phone.utils.SpKeyConstant.PREF_KEY_ONLY_LOCKSCREEN_NOTIFY
import com.rokid.security.phone.sdk.api.PSecuritySDK

import com.rokid.security.sdk.base.common.notifacation.NotificationMessage

class MessageNotificationListenerService : NotificationListenerService() {

    private val TAG: String = "MessageNotificationListenerService"


    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "[Notification] MessageNotificationListenerService onCreate()")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "[Notification] MessageNotificationListenerService onDestroy()")
    }

    private fun isDeviceLocked(context: Context): Boolean {
        val keyguardManager = context.getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        return keyguardManager.isKeyguardLocked
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        val appsList = SPUtil.getInstance(this).getListFromPreferences()
        val packageName = sbn!!.packageName
        val postTime = sbn.postTime
        val notificationTitle = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)
        val notificationText = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)
        val isMediaMessage = sbn.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)

        val isEnableNotification = SPUtil.getInstance(this).getBoolean( PREF_KEY_ENABLE_NOTIFICATION_MESSAGE, false)

//        val isHasScreen = GlassDeviceManager.getInstance().getDeviceHaveScreen()

        val isOnlyScreen = SPUtil.getInstance(this).getBoolean( PREF_KEY_ONLY_LOCKSCREEN_NOTIFY, false)
        val isDeviceLocked = isDeviceLocked(baseContext)

        val isEnableAllNotification = SPUtil.getInstance(this).getBoolean(PREF_KEY_ENABLE_ALL_NOTIFICATION, false)

//        val isWearing = DeviceInfoManager.getWearingStatus() == "1"

        Log.d(TAG, "[Notification] onNotificationPosted() get message from : $packageName, title=$notificationTitle, isMediaMessage=$isMediaMessage, isEnableNotification=$isEnableNotification, isOnlyScreen=$isOnlyScreen, isDeviceLocked=$isDeviceLocked, isEnableAllNotification=$isEnableAllNotification notificationText:"+notificationText+" ")

        // 发送消息给眼镜
        if (isEnableNotification && GlobalData.btConnectState.value && !isMediaMessage  && !notificationTitle.isNullOrEmpty() && !notificationText.isNullOrEmpty()) {
            Log.d(TAG, "[Notification] PASS");
            // 过滤AI服务消息
//            if (packageName == "com.rokid.sprite.aiapp" && notificationTitle == GlobalConstant.AI_SERVICE_NAME) {
//                return
//            }
//            if (isOnlyScreen && !isDeviceLocked){
//                Log.d(TAG, "[Notification] Not in lockscreen, so return")
//                return
//            }

            if (!isEnableAllNotification && !appsList.contains(packageName)){
                Log.d(TAG, "[Notification] Not in appsList, so return")
                return
            }
//
            if (packageName == "android" && notificationTitle.contains("USB", true)){
                Log.d(TAG, "[Notification] ignore android connect usb message")
                return
            }

            // 精简消息：眼镜端默认通知条展示空间有限，超长截断（标题20字/正文60字）
            val shortTitle = notificationTitle.toString().let { if (it.length > 20) it.substring(0, 20) + "…" else it }
            val shortText = notificationText.toString().let { if (it.length > 60) it.substring(0, 60) + "…" else it }
            val messageBean = NotificationMessage(packageName, getAppNameByPackageName(packageName),
                shortTitle, shortText, postTime)
            PSecuritySDK.getAbsNotificationService()?.sendNotification(messageBean)
//            PSecuritySDK.getAbsNotificationService()?.se
            //            val args = Caps()
//            args.write(BtcCst.Ntf.Ntf_SendNewMsg)
//            args.write(gson.toJson(messageBean))
//            //RKLogger.dWithTag(TAG, "[Notification] onNotificationPosted() send the message to glass, json : ${gson.toJson(messageBean)}")
//            ConnectManager.getInstance().send(BtcCst.Ntf.MODULE_NAME, args, null)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        val appsList = SPUtil.getInstance(this).getListFromPreferences()
        val packageName = sbn!!.packageName
        if (appsList.contains(packageName)) {
            val notificationTitle = sbn!!.notification.extras.getString("android.title")
            val notificationText = sbn!!.notification.extras.getString("android.text")
            Log.d(TAG, "[Notification] onNotificationPosted() removed notification from: $packageName Title: $notificationTitle Text: $notificationText")
        }
    }

    override fun onListenerConnected() {
        Log.d(TAG, "[Notification] NotificationListenerService Connected")
    }

    override fun onListenerDisconnected() {
        Log.d(TAG,"[Notification] NotificationListenerService Disconnected, try restart it...")
        restartNotificationListenerService()
    }

    private fun restartNotificationListenerService() {
        val componentName = ComponentName(baseContext, MessageNotificationListenerService::class.java)
        val pm = baseContext.packageManager

        // 先禁用 Service
        pm.setComponentEnabledSetting(
            componentName,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )

        // 重新启用 Service
        pm.setComponentEnabledSetting(
            componentName,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    private fun getAppNameByPackageName(packageName: String): String {
        return try {
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            ""
        }
    }

}