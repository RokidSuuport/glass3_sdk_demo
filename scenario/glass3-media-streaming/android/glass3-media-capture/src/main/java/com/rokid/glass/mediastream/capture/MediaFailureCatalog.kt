package com.rokid.glass.mediastream.capture

object MediaFailureCatalog {
    @JvmStatic
    fun forCode(code: MediaErrorCode): MediaFailure = when (code) {
        MediaErrorCode.PERMISSION_REQUIRED -> MediaFailure(
            code = code,
            userMessage = "缺少相机或录音权限",
            suggestedAction = "引导授权，不自动循环重试",
        )

        MediaErrorCode.SDK_NOT_READY -> MediaFailure(
            code = code,
            userMessage = "Glass SDK 未连接完成",
            suggestedAction = "重新绑定一次，失败后提示重试",
        )

        MediaErrorCode.SDK_DISCONNECTED -> MediaFailure(
            code = code,
            userMessage = "SDK 运行中断开",
            suggestedAction = "释放媒体并重新绑定一次",
        )

        MediaErrorCode.CAMERA_IN_USE -> MediaFailure(
            code = code,
            userMessage = "相机被其他功能占用",
            suggestedAction = "提示关闭扫码、录像或其他应用",
        )

        MediaErrorCode.CAMERA_START_TIMEOUT -> MediaFailure(
            code = code,
            userMessage = "相机启动超时",
            suggestedAction = "清理后允许手动重试",
        )

        MediaErrorCode.VIDEO_FRAME_TIMEOUT -> MediaFailure(
            code = code,
            userMessage = "启动后没有 NV21 帧",
            suggestedAction = "停止相机并提示检查服务/占用",
        )

        MediaErrorCode.AUDIO_START_FAILED -> MediaFailure(
            code = code,
            userMessage = "麦克风启动失败",
            suggestedAction = "清理音频并提示权限/占用",
        )

        MediaErrorCode.AUDIO_DATA_TIMEOUT -> MediaFailure(
            code = code,
            userMessage = "启动后没有 PCM 数据",
            suggestedAction = "停止音频并允许重试",
        )

        MediaErrorCode.SERVER_UNREACHABLE -> MediaFailure(
            code = code,
            userMessage = "无法连接 PC",
            suggestedAction = "检查地址、端口、防火墙和网络",
        )

        MediaErrorCode.RECEIVER_NOT_READY -> MediaFailure(
            code = code,
            userMessage = "浏览器接收端未就绪",
            suggestedAction = "保持等待或提示先开始接收",
        )

        MediaErrorCode.WEBRTC_NEGOTIATION_FAILED -> MediaFailure(
            code = code,
            userMessage = "WebRTC 协商失败",
            suggestedAction = "清理连接并允许重试",
        )

        MediaErrorCode.NETWORK_DISCONNECTED -> MediaFailure(
            code = code,
            userMessage = "传输中网络断开",
            suggestedAction = "有界自动重连并显示次数",
        )
    }
}
