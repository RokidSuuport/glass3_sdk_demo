package com.rokid.phone.data

/**
 * 注意，这个配置是与Launcher同步的 com.rokid.os.sprite.launcher.data.Config
 * 读取assets中的配置，下发到眼镜，ipc到Launcher
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