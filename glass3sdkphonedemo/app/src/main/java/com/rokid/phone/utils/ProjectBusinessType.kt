package com.rokid.phone.utils

import androidx.annotation.StringDef
import com.rokid.phone.utils.OTA_UPDATE_STATUS.Companion.OTA_CHECK_FAILED
import com.rokid.phone.utils.OTA_UPDATE_STATUS.Companion.OTA_CORE_SYSTEM_START
import com.rokid.phone.utils.OTA_UPDATE_STATUS.Companion.OTA_CORE_SYSTEM_UPDATE_FAILED
import com.rokid.phone.utils.OTA_UPDATE_STATUS.Companion.OTA_FILE_MOVE
import com.rokid.phone.utils.OTA_UPDATE_STATUS.Companion.OTA_FILE_MOVE_FAILED
import com.rokid.phone.utils.OTA_UPDATE_STATUS.Companion.OTA_FILE_MOVE_SUCCESS
import com.rokid.phone.utils.OTA_UPDATE_STATUS.Companion.OTA_FILE_SYSTEM_UPDATE
import com.rokid.phone.utils.ProjectBusinessType.Companion.APP_INSTALL
import com.rokid.phone.utils.ProjectBusinessType.Companion.APP_INSTALL_RESPONSE
import com.rokid.phone.utils.ProjectBusinessType.Companion.GB28181_ACTION
import com.rokid.phone.utils.ProjectBusinessType.Companion.GET_SYSTEM_INFO
import com.rokid.phone.utils.ProjectBusinessType.Companion.SET_BRIGHTNESS
import com.rokid.phone.utils.ProjectBusinessType.Companion.SET_VOLUME
import com.rokid.phone.utils.ProjectBusinessType.Companion.SET_WORD_TIPS
import com.rokid.phone.utils.ProjectBusinessType.Companion.START_UPLOAD_OVER_ALBUM
import com.rokid.phone.utils.ProjectBusinessType.Companion.SYNC_FILE
import com.rokid.phone.utils.ProjectBusinessType.Companion.SYSTEM_INFO_RESPONSE
import com.rokid.phone.utils.ProjectBusinessType.Companion.SYSTEM_OTA_UPDATE
import com.rokid.phone.utils.ProjectBusinessType.Companion.SYSTEM_OTA_UPDATE_PROGRESS
import com.rokid.phone.utils.ProjectBusinessType.Companion.SYSTEM_OTA_UPDATE_STATUS
import com.rokid.phone.utils.ProjectBusinessType.Companion.USER_INFO

/** 限定手机端与眼镜端之间支持的业务消息类型。 */
@Retention(AnnotationRetention.SOURCE)
@StringDef(
    SYSTEM_OTA_UPDATE,
    GET_SYSTEM_INFO,
    SYSTEM_INFO_RESPONSE,
    SYSTEM_OTA_UPDATE_STATUS,
    USER_INFO,
    SYSTEM_OTA_UPDATE_PROGRESS,
    SET_VOLUME,
    SET_BRIGHTNESS,
    SET_WORD_TIPS,
    SYNC_FILE,
    APP_INSTALL,
    APP_INSTALL_RESPONSE,
    START_UPLOAD_OVER_ALBUM,
    GB28181_ACTION

)
annotation class ProjectBusinessType {

    companion object {
        const val SYSTEM_OTA_UPDATE = "SYSTEM_OTA_UPDATE"
        const val GET_SYSTEM_INFO = "GET_SYSTEM_INFO"
        const val SYSTEM_INFO_RESPONSE = "SYSTEM_INFO_RESPONSE"
        const val SYSTEM_OTA_UPDATE_STATUS = "SYSTEM_OTA_UPDATE_STATUS"
        const val USER_INFO = "USER_INFO"
        const val SYSTEM_OTA_UPDATE_PROGRESS = "SYSTEM_OTA_UPDATE_PROGRESS"
        const val SET_VOLUME = "SET_VOLUME"
        const val SET_BRIGHTNESS = "SET_BRIGHTNESS"
        const val SET_WORD_TIPS = "SET_WORD_TIPS"
        const val SYNC_FILE = "SYNC_FILE"
        const val APP_INSTALL = "APP_INSTALL"
        const val APP_INSTALL_RESPONSE = "APP_INSTALL_RESPONSE"
        const val START_UPLOAD_OVER_ALBUM = "START_UPLOAD_OVER_ALBUM"
        const val GET_GLASS_ALBUM_SYNC_COUNT = "GET_GLASS_ALBUM_SYNC_COUNT"
        const val ON_GLASS_ALBUM_SYNC_COUNT_RESPONSE = "ON_GLASS_ALBUM_SYNC_COUNT_RESPONSE"

        const val SYNC_ALBUM_PHONE_FILE = "SYNC_ALBUM_PHONE_FILE"
        const val SYNC_ALBUM_GLASS_FILE = "SYNC_ALBUM_GLASS_FILE"
        const val SYNC_ALBUM_GLASS_FILE_NO_EXIST_FAILED = "SYNC_ALBUM_GLASS_FILE_NO_EXIST_FAILED"

        const val SEND_CONFIG = "SEND_CONFIG"
        const val GB28181_ACTION = "GB28181_ACTION"

        const val POWER_UPDATE = "POWER_UPDATE"

        const val REBOOT = "REBOOT"

        const val SET_CUSTOM_WAKE = "SET_CUSTOM_WAKE"
        const val SET_CUSTOM_WAKE_SUCCESS = "SET_CUSTOM_WAKE_SUCCESS"
        const val GET_CUSTOM_WAKE = "GET_CUSTOM_WAKE"

        const val SET_ZOOM_CAMERA = "SET_ZOOM_CAMERA"
        const val SET_ZOOM_CAMERA_SUCCESS = "SET_ZOOM_CAMERA_SUCCESS"
    }
}







@Retention(AnnotationRetention.SOURCE)
@StringDef(
    OTA_FILE_MOVE,
    OTA_FILE_MOVE_FAILED,
    OTA_FILE_SYSTEM_UPDATE,
    OTA_FILE_MOVE_SUCCESS,
    OTA_CORE_SYSTEM_START,
    OTA_CORE_SYSTEM_UPDATE_FAILED,
    OTA_CHECK_FAILED

)
annotation class OTA_UPDATE_STATUS {

    companion object {
        const val OTA_FILE_MOVE = "OTA_MOVE"
        const val OTA_FILE_MOVE_FAILED = "OTA_FILE_MOVE_FAILED"
        const val OTA_FILE_MOVE_SUCCESS= "OTA_FILE_MOVE_SUCCESS"
        const val OTA_FILE_SYSTEM_UPDATE = "OTA_FILE_SYSTEM_UPDATE"
        const val OTA_CORE_SYSTEM_START = "OTA_CORE_SYSTEM_START"
        const val OTA_CORE_SYSTEM_REBOOT = "OTA_CORE_SYSTEM_REBOOT"
        const val OTA_CORE_SYSTEM_UPDATE_FAILED = "OTA_CORE_SYSTEM_UPDATE_FAILED"
        const val OTA_CHECK_FAILED = "OTA_CHECK_FAILED"
    }
}





