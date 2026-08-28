package com.rokid.phone.utils
import android.content.res.Resources
import android.os.Build
import android.util.Log
import java.util.Locale
/** 提供系统语言与地区信息的查询方法。 */
object LanguageUtils {

    /**
     * 获取当前系统语言的 ISO 639-1 语言代码（例如 "en", "zh"）
     */
    fun getCurrentLanguageCode(): String {
        return Locale.getDefault().language
    }

    /**
     * 获取当前系统的完整语言环境（例如 "en-US", "zh-CN"）
     */
    fun getCurrentLocale(): Locale {
        return Locale.getDefault()
    }

    /**
     * 获取当前语言名称（例如 "English", "中文"）
     */
    fun getCurrentLanguageDisplayName(): String {
        return Locale.getDefault().displayLanguage
    }

    /**
     * 获取系统语言环境中显示的国家/地区（例如 "US", "CN"）
     */
    fun getCurrentCountryCode(): String {
        return Locale.getDefault().country
    }

    /**
     * 判断当前系统语言是否为中文
     */
    fun isCurrentLanguageChinese(): Boolean {
        // 直接获取系统默认的Locale（不受应用语言设置影响）
        val systemLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Resources.getSystem().configuration.locales[0]
        } else {
            Resources.getSystem().configuration.locale
        }
        Log.d("LanguageUtils", "isCurrentLanguageChinese: ${systemLocale.language}")
        return systemLocale.language == "zh" // 判断是否是中文（包括简体和繁体）
    }
}
