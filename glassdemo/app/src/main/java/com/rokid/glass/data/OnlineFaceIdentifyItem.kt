package com.rokid.glass.data

import android.graphics.Bitmap
import com.rokid.security.glass3.sdk.base.data.recog.offline.bean.FaceModel
import com.rokid.security.sdk.base.common.outside.RecognizePersonInfo

/**
 * Created by wjm on 2025/7/28
 */
data class OnlineFaceIdentifyItem(
    var isSelected: Boolean = false,
    var faceModel: FaceModel,
    var trackId: String,
    var bitmap: Bitmap? = null,
    var personInfo: RecognizePersonInfo? = null,
    var loadState:  Int = 0, // 0 加载中, 1 加载完成, 2 加载失败,
    var errormsg: String? = null

)

object LoadState{
    const val  LOADING = 0
    const val  LOAD_COMPLETE = 1
    const val  LOAD_FAIL = 2
}