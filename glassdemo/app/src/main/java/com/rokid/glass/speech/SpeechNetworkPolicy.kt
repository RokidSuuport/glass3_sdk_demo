package com.rokid.glass.speech

/** Only a known direct route with no local Internet network can be rejected locally.
 * Phone connectivity cannot be inferred from the glasses' ConnectivityManager.
 */
internal object SpeechNetworkPolicy {
    fun canAttempt(route: Int?, localInternet: Boolean?): Boolean =
        route != 1 || localInternet != false

    fun hint(route: Int?): String = when (route) {
        0 -> "当前使用手机中继，请保持手机端 App 连接，并确保手机能访问语音服务；手机网络状态尚未确认"
        1 -> "当前使用眼镜直连，请确保眼镜能访问语音服务"
        else -> "当前语音网络路径未知，将尝试请求，由服务回调或超时判断结果"
    }
}
