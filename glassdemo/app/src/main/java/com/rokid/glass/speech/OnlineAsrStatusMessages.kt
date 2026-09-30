package com.rokid.glass.speech

object OnlineAsrStatusMessages {
    const val serviceUnavailable = "在线 ASR 服务不可用，请检查眼镜系统服务和开发版授权"
    const val connecting = "正在启动语音转文本..."
    const val connected = "在线 ASR 服务连接成功，等待语音识别启动"
    const val started = "语音转文本开始，请开始说话"
    const val connectionFailed = "在线 ASR 服务连接失败：请检查当前网络路径（手机中继／眼镜直连）的联网情况及语音服务鉴权配置"
    const val connectionTimeout = "在线 ASR 服务连接超时：请检查当前网络路径（手机中继／眼镜直连）的联网情况及语音服务鉴权配置"

    fun error(code: Int): String =
        "在线 ASR 识别失败：code=$code，请检查当前网络路径、语音服务鉴权配置及服务权限"
}
