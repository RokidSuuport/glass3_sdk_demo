package com.rokid.glass.speech

import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.tts.TtsSessionCallback

/** 仅使用全局监听，避免单次回调在页面退出后仍被 Manager 持有。
 * Use removable global listeners so Managers do not retain per-request page callbacks.
 */
internal class MessageTtsManagerEngine(private val online: Boolean) : MessageTtsLifecycle.Engine {
    private var listener: TtsSessionCallback? = null

    override fun addListener(listener: (Long, Int, Int) -> Unit) {
        removeListener()
        val callback = object : TtsSessionCallback() {
            override fun onStateChanged(sessionId: Long, state: Int, errorCode: Int, errorMessage: String?) {
                listener(sessionId, state, errorCode)
            }

            override fun onError(sessionId: Long, code: Int, message: String?) {
                // 提交失败可能只触发 onError；有效会话的错误已由 onStateChanged 报告。
                // Submission failures may only call onError; valid session errors arrive above.
                if (sessionId <= 0L) listener(sessionId, 4, code)
            }
        }
        this.listener = callback
        if (online) GlassSdk.getGlassTtsManager().addListener(callback)
        else GlassSdk.getGlassOfflineTtsManager().addListener(callback)
    }

    override fun removeListener() {
        val callback = listener ?: return
        if (online) GlassSdk.getGlassTtsManager().removeListener(callback)
        else GlassSdk.getGlassOfflineTtsManager().removeListener(callback)
        listener = null
    }

    override fun speak(text: String, queueMode: Int): Long =
        if (online) GlassSdk.getGlassTtsManager().speak(text, queueMode)
        else GlassSdk.getGlassOfflineTtsManager().speak(text, queueMode)

    override fun cancelAll() {
        if (online) GlassSdk.getGlassTtsManager().cancelAll()
        else GlassSdk.getGlassOfflineTtsManager().cancelAll()
    }
}
