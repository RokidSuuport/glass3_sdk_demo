package com.rokid.glass.speech

object OnlineAsrStatusMessages {
    const val noNetwork = "在线 ASR 启动失败：当前无可用网络，请先连接 Wi-Fi"
    const val serviceUnavailable = "在线 ASR 服务不可用，请检查眼镜系统服务和开发版授权"
    const val connecting = "正在启动语音转文本..."
    const val connected = "在线 ASR 服务连接成功，等待语音识别启动"
    const val started = "语音转文本开始，请开始说话"
    const val connectionFailed = "在线 ASR 服务连接失败：请检查网络及灵眸账号鉴权状态"
    const val connectionTimeout = "在线 ASR 服务连接超时：请检查网络及灵眸账号鉴权状态"

    fun error(code: Int): String =
        "在线 ASR 识别失败：code=$code，请检查网络、灵眸账号鉴权状态及服务权限"
}
