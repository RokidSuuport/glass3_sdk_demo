package com.rokid.phone.system.model

import androidx.annotation.StringDef
import com.rokid.phone.base.viewmodel.interfaces.UiEvent
import com.rokid.phone.base.viewmodel.interfaces.UiIntent
import com.rokid.phone.base.viewmodel.interfaces.UiState
import com.rokid.phone.utils.OTA_UPDATE_STATUS
import com.rokid.phone.utils.RKSystemInfo


data class SystemState(
    val updateStatus: String = UpdateStatus.UPDATE_UN,
    val process: Float = 0F,
) : UiState



sealed class SystemIntent : UiIntent {

     class getSystemInfo(): SystemIntent()
    data class checkUpdate(val  mSystemInfo: RKSystemInfo): SystemIntent()
    data class startUpdate (val path: String,val fileMd5: String) : SystemIntent()
    class cancelDownload(): SystemIntent()

}


sealed class SystemEvent : UiEvent {
    data class NeedUpdate(val need: Boolean,val content:String,var fileMd5: String,val version: String) : SystemEvent()
    data class SystemInfo(val info: RKSystemInfo) : SystemEvent()
    data class UpdateState(@OTA_UPDATE_STATUS val msg: String) : SystemEvent()
}





@Retention(AnnotationRetention.SOURCE)
@StringDef(
    UpdateStatus.UPDATE_UN,
    UpdateStatus.UPDATE_ING_DOWNLOAD,
    UpdateStatus.UPDATE_ING_SEND,
    UpdateStatus.UPDATE_FAILED,
    UpdateStatus.UPDATE_SUCCESS,
)
annotation class UpdateStatus {
    companion object {
        const val UPDATE_UN = "UPDATE_UN"
        const val UPDATE_ING_DOWNLOAD = "UPDATE_ING_DOWNLOAD"
        const val UPDATE_ING_SEND = "UPDATE_ING_SEND"
        const val UPDATE_FAILED = "UPDATE_FAILED"
        const val UPDATE_SUCCESS = "UPDATE_SUCCESS"
    }
}
