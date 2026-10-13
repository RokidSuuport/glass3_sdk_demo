package com.rokid.glass.annotation;

import androidx.annotation.Keep;

@Keep
public interface MenuConfigType {
    interface MenuInfoType {
        // 人脸识别
        // Face recognition.
        String MENU_FACE_RECOG = "menu_face_recog";
        // 车牌识别
        // License plate recognition.
        String MENU_LPR_RECOG = "menu_lpr_recog";
        // 接收消息
        // Receive messages.
        String RECEIVE_MSG = "receive_msg";
        // 发送消息
        // Send messages.
        String SEND_MSG = "send_msg";
        String DEVICE_TOOLS = "device_tools";
        String ONLINE_ASR_TTS = "online_asr_tts";
        // sdk拍照录像界面
        // SDK photo and video capture screen.
        String SDK_TAKE_PHOTO = "sdk_take_photo";
    }
}
