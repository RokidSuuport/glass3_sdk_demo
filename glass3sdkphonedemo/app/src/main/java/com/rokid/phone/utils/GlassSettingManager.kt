package com.rokid.phone.utils

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rokid.phone.MyApplication


import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException

/**
 *
 * @Author: sunchao
 * @CreateDate: 2025/6/5 16:07
 * 眼镜配置统一管理
 *
 * {"key": "settings_photo_width", "value": "4032"},
 *   {"key": "settings_photo_height", "value": "3024"},
 *   {"key": "settings_video_duration", "value": "1"},//分
 *   {"key": "settings_video_fps", "value": "30"},
 *   {"key": "settings_video_width", "value": "2240"},
 *   {"key": "settings_video_height", "value": "1680"},
 *   {"key": "settings_sound_effect", "value": "AdiMode1"},
 *   {"key": "settings_voice_control", "value": "open"},
 *   {"key": "settings_screen_turnOff", "value": "false"},
 *   {"key": "settings_screen_offTimeout", "value": "5"},//秒
 *   {"key": "settings_interaction_longPressFun", "value": "video"},
 *   {"key": "settings_language", "value": "zh-CN"}
 *   音效切换-"settings_sound_effect" 洪亮-"AdiMode0"，韵律-"AdiMode1"，播客-"AdiMode2"
 *   语音唤醒-"settings_voice_control"  打开-"open"，关闭-"close"
 *   长按功能-"settings_interaction_longPressFun"  录像-"video"，录音-"audio"
 *   {"key": "settings_country_code", "value": "CN"},
 *   {"key": "settings_developer_mode", "value": "on"},
 *   开发者模式-"settings_developer_mode"
 *   开启-"on"，关闭-"off"
 */
object GlassSettingManager {

    private const val TAG = "GlassSettingManager"

    private val Context.dataStoreWithScreen: DataStore<androidx.datastore.preferences.core.Preferences> by preferencesDataStore(name = "glass_settings_with_screen")
    private val Context.dataStoreWithoutScreen: DataStore<androidx.datastore.preferences.core.Preferences> by preferencesDataStore(name = "glass_settings_without_screen")

    private lateinit var dataStore: DataStore<Preferences>
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val settingsCache = mutableMapOf<String, String>()
    private var isInitialized = false
    private lateinit var appContext: Context // Store application context to avoid leaks

    // Configuration Keys
    object PrefKeys {
        val PHOTO_WIDTH = stringPreferencesKey("settings_photo_width")
        val PHOTO_HEIGHT = stringPreferencesKey("settings_photo_height")
        val VIDEO_DURATION = stringPreferencesKey("settings_video_duration")
        val VIDEO_FPS = stringPreferencesKey("settings_video_fps")
        val VIDEO_WIDTH = stringPreferencesKey("settings_video_width")
        val VIDEO_HEIGHT = stringPreferencesKey("settings_video_height")
        val SOUND_EFFECT = stringPreferencesKey("settings_sound_effect")
        val VOICE_CONTROL = stringPreferencesKey("settings_voice_control")
        val SCREEN_TURN_OFF = stringPreferencesKey("settings_screen_turnOff")
        val SCREEN_OFF_TIMEOUT = stringPreferencesKey("settings_screen_offTimeout")
        val INTERACTION_LONG_PRESS_FUN = stringPreferencesKey("settings_interaction_longPressFun")
        val LANGUAGE = stringPreferencesKey("settings_language")
        val COUNTRY_CODE = stringPreferencesKey("settings_country_code")
        val DEVELOPER_MODE = stringPreferencesKey("settings_developer_mode")
    }


    object PrefValues {
        val INTERACTION_LONG_PRESS_FUN_VIDEO = "video"
        val INTERACTION_LONG_PRESS_FUN_AUDIO = "audio"
        
        // 开发者模式
        val DEVELOPER_MODE_ON = "on"
        val DEVELOPER_MODE_OFF = "off"
    }

    // Default Values based on comments
    private object Defaults {
        const val PHOTO_WIDTH = "4032"
        const val PHOTO_HEIGHT = "3024"
        const val VIDEO_DURATION = "1" //分
        const val VIDEO_FPS = "30"
        const val VIDEO_WIDTH = "2400"
        const val VIDEO_HEIGHT = "1800"
        const val SOUND_EFFECT = "AdiMode1" // 洪亮-"AdiMode0"，韵律-"AdiMode1"，播客-"AdiMode2"
        const val VOICE_CONTROL = "open" // 打开-"open"，关闭-"close"
        const val SCREEN_TURN_OFF = "false"
        const val SCREEN_OFF_TIMEOUT = "10" //秒
        const val INTERACTION_LONG_PRESS_FUN = "video" // 录像-"video"，录音-"audio"
        const val LANGUAGE = "zh-CN"
        const val COUNTRY_CODE = "CN" // 默认国家代码
        const val DEVELOPER_MODE = "on" // 默认开启开发者模式
    }

    private val allSettingKeysWithDefaults: Map<Preferences.Key<String>, String> = mapOf(
        kotlin.Pair(PrefKeys.PHOTO_WIDTH, Defaults.PHOTO_WIDTH),
        kotlin.Pair(PrefKeys.PHOTO_HEIGHT, Defaults.PHOTO_HEIGHT),
        kotlin.Pair(PrefKeys.VIDEO_DURATION, Defaults.VIDEO_DURATION),
        kotlin.Pair(PrefKeys.VIDEO_FPS, Defaults.VIDEO_FPS),
        kotlin.Pair(PrefKeys.VIDEO_WIDTH, Defaults.VIDEO_WIDTH),
        kotlin.Pair(PrefKeys.VIDEO_HEIGHT, Defaults.VIDEO_HEIGHT),
        kotlin.Pair(PrefKeys.SOUND_EFFECT, Defaults.SOUND_EFFECT),
        kotlin.Pair(PrefKeys.VOICE_CONTROL, Defaults.VOICE_CONTROL),
        kotlin.Pair(PrefKeys.SCREEN_TURN_OFF, Defaults.SCREEN_TURN_OFF),
        kotlin.Pair(PrefKeys.SCREEN_OFF_TIMEOUT, Defaults.SCREEN_OFF_TIMEOUT),
        kotlin.Pair(PrefKeys.INTERACTION_LONG_PRESS_FUN, Defaults.INTERACTION_LONG_PRESS_FUN),
        kotlin.Pair(PrefKeys.LANGUAGE, if (LanguageManager.isCurrentLanguageChinese()) "zh-CN" else "en-US"),
        kotlin.Pair(PrefKeys.COUNTRY_CODE, DeviceUtils.getSimCountryIso(MyApplication.instance)),
        kotlin.Pair(PrefKeys.DEVELOPER_MODE, Defaults.DEVELOPER_MODE)
    )

    fun init(context: Context) {
        if (isInitialized && ::appContext.isInitialized && appContext == context.applicationContext) {
            Log.d(TAG, "Already initialized with the same context.")
            // Optionally, re-check screen status if it can change dynamically post-init
            // val currentHasScreen = GlassDeviceManager.getInstance().getDeviceHaveScreen()
            // val storedHasScreen = if (dataStore == appContext.dataStoreWithScreen) true else false
            // if (currentHasScreen != storedHasScreen) updateScreenStatus(context)
            return
        }
        appContext = context.applicationContext // Use application context
//        val hasScreen = GlassDeviceManager.getInstance().getDeviceHaveScreen()
        dataStore =  appContext.dataStoreWithScreen
//        Log.d(TAG, "Initializing GlassSettingManager. Has screen: $hasScreen. DataStore: ${if (hasScreen) "withScreen" else "withoutScreen"}")
        loadSettingsToCache()
        isInitialized = true
    }

    private fun loadSettingsToCache() {
        coroutineScope.launch {
            try {
                val preferences = dataStore.data
                    .catch { exception ->
                        if (exception is IOException) {
                            Log.d(TAG,  "Error reading preferences for ${dataStore}.", exception)
                            emit(emptyPreferences())
                        } else {
                            throw exception
                        }
                    }
                    .first()
                settingsCache.clear() // Clear previous cache before loading
                allSettingKeysWithDefaults.forEach { (prefKey: Preferences.Key<String>, defaultValue: String) ->
                    settingsCache[prefKey.name] = preferences[prefKey] ?: defaultValue
                }
                Log.d(TAG, "Settings loaded into cache: $settingsCache")
            } catch (e: Exception) {
                Log.d(TAG, "Failed to load settings into cache from ${dataStore}", e)
                // Populate cache with defaults on failure
                settingsCache.clear()
                allSettingKeysWithDefaults.forEach { (prefKey: Preferences.Key<String>, defaultValue: String) ->
                    settingsCache[prefKey.name] = defaultValue
                }
            }
        }
    }

    private fun checkInitialized(): Boolean {
        if (!isInitialized) {
            Log.d(TAG,  "GlassSettingManager not initialized. Call init() first.")
            // Avoid auto-initializing here as context might not be available or correct.
            // The application should ensure init() is called at an appropriate time.
            return false
        }
        return true
    }

    private fun getStringSetting(key: Preferences.Key<String>, defaultValue: String): String {
        if (!checkInitialized()) return defaultValue
        return settingsCache[key.name] ?: defaultValue.also {
            Log.d(TAG,  "Key ${key.name} not found in cache, returning default.")
        }
    }

    private fun setStringSetting(key: Preferences.Key<String>, value: String) {
        if (!checkInitialized()) return
        settingsCache[key.name] = value
        coroutineScope.launch {
            try {
                dataStore.edit { preferences ->
                    preferences[key] = value
                }
                Log.d(TAG, "Setting ${key.name} updated to $value in ${dataStore}")
            } catch (e: Exception) {
                Log.d(TAG, "Failed to save setting ${key.name} to DataStore ${dataStore}", e)
            }
        }
    }

    // Getter and Setter for each setting
    fun getPhotoWidth(): String = getStringSetting(PrefKeys.PHOTO_WIDTH, Defaults.PHOTO_WIDTH)
    fun setPhotoWidth(value: String) {
        setStringSetting(PrefKeys.PHOTO_WIDTH, value)
        sendConfigToGlass(listOf(GlassSettingItem(PrefKeys.PHOTO_WIDTH.name, value)))
    }

    fun getPhotoHeight(): String = getStringSetting(PrefKeys.PHOTO_HEIGHT, Defaults.PHOTO_HEIGHT)
    fun setPhotoHeight(value: String) {
        setStringSetting(PrefKeys.PHOTO_HEIGHT, value)
        sendConfigToGlass(listOf(GlassSettingItem(PrefKeys.PHOTO_HEIGHT.name, value)))
    }

    fun getVideoDuration(): String = getStringSetting(PrefKeys.VIDEO_DURATION, Defaults.VIDEO_DURATION)
    fun setVideoDuration(value: String) {
        setStringSetting(PrefKeys.VIDEO_DURATION, value)
        sendConfigToGlass(listOf(GlassSettingItem(PrefKeys.VIDEO_DURATION.name, value)))
    }

    fun getVideoFps(): String = getStringSetting(PrefKeys.VIDEO_FPS, Defaults.VIDEO_FPS)
    fun setVideoFps(value: String) {
        setStringSetting(PrefKeys.VIDEO_FPS, value)
        sendConfigToGlass(listOf(GlassSettingItem(PrefKeys.VIDEO_FPS.name, value)))
    }

    fun getVideoWidth(): String = getStringSetting(PrefKeys.VIDEO_WIDTH, Defaults.VIDEO_WIDTH)

    fun getVideoHeight(): String = getStringSetting(PrefKeys.VIDEO_HEIGHT, Defaults.VIDEO_HEIGHT)
    fun setVideoResolution(width: String, height: String) {
        setStringSetting(PrefKeys.VIDEO_WIDTH, width)
        setStringSetting(PrefKeys.VIDEO_HEIGHT, height)
        sendConfigToGlass(listOf(
            GlassSettingItem(PrefKeys.VIDEO_WIDTH.name, width),
            GlassSettingItem(PrefKeys.VIDEO_HEIGHT.name, height)
        ))
    }

    fun getSoundEffect(): String = getStringSetting(PrefKeys.SOUND_EFFECT, Defaults.SOUND_EFFECT)
    fun setSoundEffect(value: String) {
        setStringSetting(PrefKeys.SOUND_EFFECT, value)
        sendConfigToGlass(listOf(GlassSettingItem(PrefKeys.SOUND_EFFECT.name, value)))
    }

    fun getVoiceControl(): String = getStringSetting(PrefKeys.VOICE_CONTROL, Defaults.VOICE_CONTROL)
    fun setVoiceControl(value: String) {
        setStringSetting(PrefKeys.VOICE_CONTROL, value)
        sendConfigToGlass(listOf(GlassSettingItem(PrefKeys.VOICE_CONTROL.name, value)))
    }

    fun getScreenTurnOff(): String = getStringSetting(PrefKeys.SCREEN_TURN_OFF, Defaults.SCREEN_TURN_OFF)
    fun setScreenTurnOff(value: String) {
        setStringSetting(PrefKeys.SCREEN_TURN_OFF, value)
        sendConfigToGlass(listOf(GlassSettingItem(PrefKeys.SCREEN_TURN_OFF.name, value)))
    }

    fun getScreenOffTimeout(): String = getStringSetting(PrefKeys.SCREEN_OFF_TIMEOUT, Defaults.SCREEN_OFF_TIMEOUT)
    fun setScreenOffTimeout(value: String) {
        setStringSetting(PrefKeys.SCREEN_OFF_TIMEOUT, value)
        sendConfigToGlass(listOf(GlassSettingItem(PrefKeys.SCREEN_OFF_TIMEOUT.name, value)))
    }

    fun getInteractionLongPressFun(): String = getStringSetting(PrefKeys.INTERACTION_LONG_PRESS_FUN, Defaults.INTERACTION_LONG_PRESS_FUN)
    fun setInteractionLongPressFun(value: String) {
        setStringSetting(PrefKeys.INTERACTION_LONG_PRESS_FUN, value)
        sendConfigToGlass(listOf(GlassSettingItem(PrefKeys.INTERACTION_LONG_PRESS_FUN.name, value)))
    }

    fun getLanguage(): String = getStringSetting(PrefKeys.LANGUAGE, if (LanguageManager.isCurrentLanguageChinese()) "zh-CN" else "en-US")

    fun setLanguage(value: String) {
        setStringSetting(PrefKeys.LANGUAGE, value)
        sendConfigToGlass(listOf(GlassSettingItem(PrefKeys.LANGUAGE.name, value)))
    }
    
    /**
     * 获取国家代码 (ISO 3166-1 alpha-2)
     * @return 返回大写的国家代码，例如 "CN"
     */
    fun getCountryCode(): String  {
        val defaultValue = DeviceUtils.getSimCountryIso(MyApplication.instance)
        return defaultValue
    }

    /**
     * 设置国家代码
     * @param value 国家代码 (ISO 3166-1 alpha-2)，例如 "CN"
     */
    fun setCountryCode(value: String) {
        //国家码不需要保存
//        setStringSetting(PrefKeys.COUNTRY_CODE, value)
        sendConfigToGlass(listOf(GlassSettingItem(PrefKeys.COUNTRY_CODE.name, value)))
    }
    
    /**
     * 检查开发者模式是否开启
     * @return 如果开发者模式开启返回 true，否则返回 false
     */
    fun isDeveloperModeEnabled(): Boolean {
        return getStringSetting(PrefKeys.DEVELOPER_MODE, Defaults.DEVELOPER_MODE) == PrefValues.DEVELOPER_MODE_ON
    }
    
    /**
     * 设置开发者模式状态
     * @param enabled 是否启用开发者模式
     */
    fun setDeveloperMode(enabled: Boolean) {
        val value = if (enabled) PrefValues.DEVELOPER_MODE_ON else PrefValues.DEVELOPER_MODE_OFF
        setStringSetting(PrefKeys.DEVELOPER_MODE, value)
        sendConfigToGlass(listOf(GlassSettingItem(PrefKeys.DEVELOPER_MODE.name, value)))
    }

    fun sendAllCurrentSettingsToGlass() {
        if (!checkInitialized()) {
            Log.d(TAG,  "sendAllCurrentSettingsToGlass called before initialization.")
            return
        }
        val itemsToSend = mutableListOf<GlassSettingItem>()
        allSettingKeysWithDefaults.forEach { (prefKey, defaultValue) ->
            val currentValue = settingsCache[prefKey.name] ?: defaultValue
            itemsToSend.add(GlassSettingItem(prefKey.name, currentValue))
        }
        if (itemsToSend.isNotEmpty()) {
            sendConfigToGlass(itemsToSend)
            Log.d(TAG,  "Sent all current settings to glass: ${itemsToSend}{itemsToSend.size} items")
        } else {
            Log.d(TAG,  "No settings to send in sendAllCurrentSettingsToGlass.")
        }
    }

    /**
     * Clears all locally cached settings from both DataStore and the in-memory cache,
     * then reloads the default settings.
     */
    fun clearAllLocalSettings() {
        if (!checkInitialized()) {
            Log.d(TAG,  "clearAllLocalSettings called before initialization.")
            return
        }
        coroutineScope.launch {
            try {
                dataStore.edit { it.clear() }
                Log.d(TAG,  "Successfully cleared DataStore settings.")
                // After clearing DataStore, reload cache which will now only contain defaults
                loadSettingsToCache()
                Log.d(TAG,  "Local settings cleared and cache reset to defaults.")
            } catch (exception: IOException) {
                Log.d(TAG,  "Error clearing DataStore preferences.", exception)
            }
        }
    }

    /**
     * Call this method if the screen availability status might have changed (e.g., device connected/disconnected
     * and GlassDeviceManager.getInstance().getDeviceHaveScreen() would return a different value).
     * This will re-initialize the DataStore instance and reload settings from the correct store.
     */
    fun updateScreenStatus(context: Context) {
//        val currentHasScreen = GlassDeviceManager.getInstance().getDeviceHaveScreen()
//        val newDs = if (currentHasScreen) context.applicationContext.dataStoreWithScreen else context.applicationContext.dataStoreWithoutScreen
//        if (::dataStore.isInitialized && dataStore == newDs) {
//            Log.d(TAG,  "Screen status checked, DataStore instance remains the same.")
//            return
//        }
//        Log.d(TAG, "Updating screen status. Re-initializing GlassSettingManager. New has screen: $currentHasScreen")
//        isInitialized = false // Force re-initialization including DataStore selection and cache reload
//        init(context.applicationContext)
    }

    private fun sendConfigToGlass(glassSettingItems: List<GlassSettingItem>){
//        val caps = Caps()
//        caps.write(BtcCst.Settings.Settings_Update)
//        caps.write(GsonUtils.toJson(glassSettingItems))
//        ConnectManager.getInstance().send(BtcCst.Settings.MODULE_NAME, caps,null)
    }
}