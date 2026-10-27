package com.rokid.phone.data

import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow

data class GlassSystemInfo(
    val version: String = "",
    val deviceId: String = "",
    val isCharge: Boolean = false,
    val powerValue: Int = 0,
)

object DeviceStatus {
    private val gson = Gson()

    val powerValue = MutableStateFlow(0)
    val charging = MutableStateFlow(false)
    val systemVersion = MutableStateFlow("")
    val deviceId = MutableStateFlow("")

    fun updateFromJson(json: String, updatePowerOnly: Boolean = false) {
        val info = runCatching { gson.fromJson(json, GlassSystemInfo::class.java) }.getOrNull() ?: return
        if (info.powerValue in 0..100) powerValue.value = info.powerValue
        charging.value = info.isCharge
        if (!updatePowerOnly) {
            if (info.version.isNotBlank()) systemVersion.value = info.version
            if (info.deviceId.isNotBlank()) deviceId.value = info.deviceId
        }
    }

    fun reset() {
        powerValue.value = 0
        charging.value = false
        systemVersion.value = ""
        deviceId.value = ""
    }
}
