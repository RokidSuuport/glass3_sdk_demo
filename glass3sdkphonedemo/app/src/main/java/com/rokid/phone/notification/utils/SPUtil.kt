package com.rokid.security.phone.sdk.template.feature.notification.utils

import android.content.Context

//object SPUtil {
//
//    private const val PREFERENCE_NAME = "NotificationPreferences"
//
//    private const val KEY_APPS_LIST = "apps_list"
//
//    const val PREF_KEY_ENABLE_NOTIFICATION_MESSAGE = "pref_key_enable_notification_message"
//
//    const val PREF_KEY_ONLY_LOCKSCREEN_NOTIFY = "pref_key_only_lockscreen_notify"
//
//    const val PREF_KEY_ENABLE_ALL_NOTIFICATION = "pref_key_enable_all_notification"
//
//    fun saveListToPreferences(context: Context, list: List<String>) {
//        val sharedPreferences = context.getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE)
//        sharedPreferences.edit().putStringSet(KEY_APPS_LIST, list.toSet()).apply()
//    }
//
//    fun getListFromPreferences(context: Context): List<String> {
//        val sharedPreferences = context.getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE)
//        return sharedPreferences.getStringSet(KEY_APPS_LIST, emptySet())?.toList() ?: emptyList()
//    }
//
//    // 存储boolean
//    fun saveBoolean(context: Context, key: String, value: Boolean) {
//        val sharedPreferences = context.getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE)
//        sharedPreferences.edit().putBoolean(key, value).apply()
//    }
//
////    // 读取boolean，默认值为 false
////    fun getBoolean(context: Context, key: String, defaultValue: Boolean = false): Boolean {
////        val sharedPreferences = context.getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE)
////        return sharedPreferences.getBoolean(key, defaultValue)
////    }
//
//
//}