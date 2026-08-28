package com.rokid.phone.utils

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import android.util.Log
import java.util.Locale

/** 管理应用内语言选择与持久化。 */
object LanguageManager {
    private const val TAG = "LanguageManager"
    private const val PREF_NAME = "language_pref"
    private const val KEY_LANGUAGE = "current_language"

    // 支持的语言类型
    enum class Language(val code: String, val displayName: String) {
        CHINESE("zh", "中文"),
        ENGLISH("en", "English"),
        FOLLOWSYSTEM("system", "FollowSystem")
    }

    // 默认语言为中文
    private var currentLanguage: Language = Language.FOLLOWSYSTEM

    /**
     * 初始化语言管理器
     * @param context 应用上下文
     */
    fun init(context: Context) {
        // 从本地存储中获取保存的语言设置
        val savedLanguageCode = SPUtil.getInstance(context).getString( KEY_LANGUAGE, Language.FOLLOWSYSTEM.code)
        currentLanguage = Language.values().find { it.code == savedLanguageCode } ?: Language.FOLLOWSYSTEM
        Log.d(TAG, "初始化语言: $currentLanguage")
    }

    /**
     * 获取当前选择的语言
     * @return 当前语言
     */
    fun getCurrentLanguage(): Language {
        return currentLanguage
    }

    /**
     * 设置应用语言
     * @param context 上下文
     * @param language 要设置的语言
     */
    fun setLanguage(context: Context, language: Language) {
        currentLanguage = language
        // 保存语言设置到本地存储
        SPUtil.getInstance(context).putString(KEY_LANGUAGE, language.code)
        Log.d(TAG, "设置语言: $language, 需要重启Activity生效")
        // 语言的实际应用将在Activity重启后的attachBaseContext中完成
    }

    fun wrapContext(context: Context): Context {
        val lang = Language.values().find { it.code == currentLanguage.code } ?: Language.FOLLOWSYSTEM

        val locale = when (lang) {
            Language.CHINESE -> Locale.SIMPLIFIED_CHINESE
            Language.ENGLISH -> Locale.ENGLISH
            Language.FOLLOWSYSTEM -> {if (LanguageUtils.isCurrentLanguageChinese()) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH}
        }

        val resources = context.resources
        val configuration = resources.configuration
        configuration.setLocale(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val localeList = LocaleList(locale)
            LocaleList.setDefault(localeList)
            configuration.setLocales(localeList)
        }
        @Suppress("DEPRECATION")
        resources.updateConfiguration(configuration, resources.displayMetrics)
        return context
    }

//    /**
//     * 应用语言设置到Context
//     * @param context 上下文
//     * @param language 要应用的语言
//     * @return 更新后的上下文
//     */
//    private fun applyLanguageToContext(context: Context, language: Language): Context {
//        val locale = when (language) {
//            Language.CHINESE -> Locale.SIMPLIFIED_CHINESE
//            Language.ENGLISH -> Locale.ENGLISH
//            Language.FOLLOWSYSTEM -> {if (LanguageUtils.isCurrentLanguageChinese()) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH}
//        }
//
//        val configuration = Configuration(context.resources.configuration)
//
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
//            val localeList = LocaleList(locale)
//            configuration.setLocales(localeList)
//        } else {
//            configuration.locale = locale
//        }
//
//        return context.createConfigurationContext(configuration)
//    }

    /**
     * 获取所有支持的语言
     * @return 语言列表
     */
    fun getSupportedLanguages(): List<Language> {
        return Language.values().toList()
    }

    fun isCurrentLanguageChinese(): Boolean {
        return currentLanguage == Language.CHINESE ||  (currentLanguage == Language.FOLLOWSYSTEM && LanguageUtils.isCurrentLanguageChinese())
    }
}
