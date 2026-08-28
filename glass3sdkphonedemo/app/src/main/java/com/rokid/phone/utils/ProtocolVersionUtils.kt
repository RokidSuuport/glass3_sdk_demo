package com.rokid.phone.utils

import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.security.phone.sdk.base.utils.log.L
import com.rokid.security.sdk.base.common.ProtocolInfo
import com.rokid.security.sdk.base.common.ProtocolVersion
import kotlin.text.toFloat

object ProtocolVersionUtils {
    private const val TAG = "ProtocolVersionUtils"
    private val msDeviceInfoService by lazy {
        PSecuritySDK.getAbsDeviceInfoService()
    }

    /**
     * 眼镜SDK协议版本1.0
     */
    fun isGlassVersion1(): Boolean{
        val glassProtocolVersion = msDeviceInfoService?.getGlassDeviceInfo()?.version ?: "-1"
        L.i(TAG, "isGlassVersion1: glassProtocolVersion = $glassProtocolVersion")
        return glassProtocolVersion == ProtocolVersion.VERSION_1
    }

    /**
     * 眼镜端协议版本低于手机端
     */
    fun isGlassVersionLow(): Boolean{
        val glassProtocolVersion = msDeviceInfoService?.getGlassDeviceInfo()?.version ?: "-1"
        val phoneProtocolVersion = ProtocolInfo.PROTOCOL_VERSION
        L.i(TAG, "isGlassVersioLow: glassProtocolVersion = $glassProtocolVersion, phoneProtocolVersion = $phoneProtocolVersion")
        return glassProtocolVersion.toFloat() < phoneProtocolVersion.toFloat()
    }

}
