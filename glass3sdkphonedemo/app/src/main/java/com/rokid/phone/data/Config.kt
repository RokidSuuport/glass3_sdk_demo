package com.rokid.phone.data

/**
 * 描述需要同步到眼镜端启动器的应用配置。
 * 手机端从 assets 读取配置后，通过 SDK 下发到眼镜端。
 */
data class Config(
    val configVersion: Int = 0,//当前版本
    val envType: String = "",//当前环境
    val showToHide: List<String> = emptyList(),//默认显示的配置成隐藏
    val hideToShow: List<String> = emptyList(),//默认隐藏的配置成显示
    val voiceAction: List<VoiceAction> = emptyList()//语音指令
)

data class VoiceAction(
    val text: String = "",
    val pinyin: String = ""
)
