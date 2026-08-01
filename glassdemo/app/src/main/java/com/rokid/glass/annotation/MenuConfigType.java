package com.rokid.glass.annotation;

import androidx.annotation.Keep;

@Keep
public interface MenuConfigType {
    interface MenuInfoType {
        String MENU_FACE_RECOG = "menu_face_recog";   // 人脸识别
        String MENU_LPR_RECOG = "menu_lpr_recog";   // 车牌识别
        String RECEIVE_MSG = "receive_msg";   // 接收消息
        String SEND_MSG = "send_msg";   // 发送消息
        String ONLINE_ASR_TTS = "online_asr_tts";   // Online-Speech ASR/TTS Demo
        String SDK_TAKE_PHOTO = "sdk_take_photo";   // sdk拍照录像界面
    }
}
