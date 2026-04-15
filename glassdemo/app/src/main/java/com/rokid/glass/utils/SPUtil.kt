package com.rokid.glass.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Author: zhangshengwei
 * Date: 2025/6/24
 */
class SPUtil private constructor(context: Context) {
    companion object {
        private const val SP_NAME = "app_shared_prefs"

        @Volatile
        private var instance: SPUtil? = null

        /**
         * 获取工具类实例（单例）
         */
        fun getInstance(context: Context): SPUtil {
            return instance ?: synchronized(this) {
                instance ?: SPUtil(context.applicationContext).also { instance = it }
            }
        }
    }

    private val sp: SharedPreferences by lazy {
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
    }

    // region 基本数据类型存储

    /**
     * 存储字符串
     */
    fun putString(key: String, value: String) {
        sp.edit { putString(key, value) }
    }

    /**
     * 获取字符串
     */
    fun getString(key: String, defaultValue: String = ""): String {
        return sp.getString(key, defaultValue) ?: defaultValue
    }

    /**
     * 存储布尔值
     */
    fun putBoolean(key: String, value: Boolean) {
        sp.edit { putBoolean(key, value) }
    }

    /**
     * 获取布尔值
     */
    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return sp.getBoolean(key, defaultValue)
    }

    /**
     * 存储整数
     */
    fun putInt(key: String, value: Int) {
        sp.edit { putInt(key, value) }
    }

    /**
     * 获取整数
     */
    fun getInt(key: String, defaultValue: Int = 0): Int {
        return sp.getInt(key, defaultValue)
    }

    /**
     * 存储长整型
     */
    fun putLong(key: String, value: Long) {
        sp.edit { putLong(key, value) }
    }

    /**
     * 获取长整型
     */
    fun getLong(key: String, defaultValue: Long = 0L): Long {
        return sp.getLong(key, defaultValue)
    }

    /**
     * 存储浮点型
     */
    fun putFloat(key: String, value: Float) {
        sp.edit { putFloat(key, value) }
    }

    /**
     * 获取浮点型
     */
    fun getFloat(key: String, defaultValue: Float = 0f): Float {
        return sp.getFloat(key, defaultValue)
    }

    /**
     * 存储字符串集合
     */
    fun putStringSet(key: String, value: Set<String>) {
        sp.edit { putStringSet(key, value) }
    }

    /**
     * 获取字符串集合
     */
    fun getStringSet(key: String, defaultValue: Set<String> = emptySet()): Set<String> {
        return sp.getStringSet(key, defaultValue) ?: defaultValue
    }

    // endregion

    // region 便捷操作

    /**
     * 移除键值对
     */
    fun remove(key: String) {
        sp.edit { remove(key) }
    }

    /**
     * 清除所有数据
     */
    fun clear() {
        sp.edit { clear() }
    }

    /**
     * 检查是否包含某个键
     */
    fun contains(key: String): Boolean {
        return sp.contains(key)
    }

    // endregion

    // region 委托属性支持

    /**
     * 字符串委托属性
     */
    fun stringPreference(key: String, defaultValue: String = "") =
        object : ReadWriteProperty<Any?, String> {
            override fun getValue(thisRef: Any?, property: KProperty<*>): String {
                return getString(key, defaultValue)
            }

            override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
                putString(key, value)
            }
        }

    /**
     * 布尔值委托属性
     */
    fun booleanPreference(key: String, defaultValue: Boolean = false) =
        object : ReadWriteProperty<Any?, Boolean> {
            override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean {
                return getBoolean(key, defaultValue)
            }

            override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
                putBoolean(key, value)
            }
        }

    // 同理可添加其他类型的委托属性...

    // endregion
}