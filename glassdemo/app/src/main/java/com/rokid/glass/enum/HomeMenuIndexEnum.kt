package com.rokid.glass.enum

import com.rokid.glesse.R



/**
 * Description:
 * Author:Lc
 * Date:2025/5/25
 */
enum class HomeMenuIndexEnum(val code: Int, val chineseName: String) {
    FACE_RECOG(0,
        R.string.item_face_recog.toString()),
    FACE_ONLINE_RECOG(1,
      R.string.item_face_online_recog.toString()),
    LPR_RECOG(2,
        R.string.item_plate_recog.toString()),
    IDCARD_RECOG(3,
       R.string.item_idcard_recog.toString()),
    AI_CHAT(4,
       R.string.item_ai_chat.toString()),
    TRANSLATE(5,
      R.string.item_translate.toString()),
    TAKE_PHOTO(6,
        R.string.item_take_photo.toString())
}