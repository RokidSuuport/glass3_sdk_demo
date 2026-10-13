package com.rokid.glass.media

import com.rokid.glass.utils.demoString
import com.rokid.glesse.R
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.system.server.message.callback.IResultCallback
import java.util.concurrent.atomic.AtomicReference

/**
 * 消息页的视频发送；离页只停止本页启动的任务。
 * Video transmission for the message page; leaving stops only its own task.
 */
internal class MessageMediaActions(private val log: (String) -> Unit) {
    private val videoRequest = AtomicReference<Any?>(null)
    private var ownsVideo = false

    fun perform(id: Int) {
        runCatching {
            when (id) {
                R.id.btSendVideo -> {
                    val service = checkNotNull(GlassSdk.getGlassMessageService()) { "Message service not connected" }
                    val request = Any()
                    // 沿用原按钮允许重试的行为：旧服务未认证时可能不回调，不能永久锁住按钮。
                    videoRequest.set(request)
                    ownsVideo = true
                    log(demoString(R.string.demo_starting_video_transmission))
                    try { service.sendVideoStreamDataV2(object : IResultCallback.Default() {
                        override fun onSuccess(result: Boolean) {
                            if (videoRequest.get() !== request) return
                            if (!result) videoRequest.compareAndSet(request, null)
                            log(demoString(R.string.demo_sendvideostreamdata_onsuccess_status_10))
                        }
                        override fun onFailed(code: Int, errormsg: String?) {
                            // 失败后可重试；迟到旧结果不能清掉新一轮发送。
                            if (videoRequest.compareAndSet(request, null))
                                log(demoString(R.string.demo_sendvideostreamdata_onfailed_code_errormsg_status_11, code, errormsg))
                        }
                    }) } catch (error: Exception) { videoRequest.compareAndSet(request, null); throw error }
                }
                R.id.btStopSendVideo -> { stopVideo(); log(demoString(R.string.demo_stopping_video_transmission)) }
            }
        }.onFailure { log("Media: ${it.message.orEmpty()}") }
    }

    private fun stopVideo() {
        videoRequest.set(null)
        if (!ownsVideo) return
        ownsVideo = false
        GlassSdk.getGlassMessageService()?.stopVideoStreamData()
    }
    fun stopOwnedOperations() {
        runCatching(::stopVideo).onFailure { log("Media cleanup: ${it.javaClass.simpleName}") }
    }
}
