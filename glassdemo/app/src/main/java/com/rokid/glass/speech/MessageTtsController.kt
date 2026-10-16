package com.rokid.glass.speech

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.rokid.glesse.R
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.tts.TtsQueueMode

/** 页面生命周期及主线程日志适配；共享 Manager 由 GlassSdk 解绑流程释放。
 * Adapt page lifecycle and main-thread logs; GlassSdk releases shared Managers on unbind.
 */
class MessageTtsController(context: Context, private val report: (String) -> Unit) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var active = false
    @Volatile private var closed = false
    @Volatile private var generation = 0
    private val lifecycle = MessageTtsLifecycle(
        MessageTtsManagerEngine(true), MessageTtsManagerEngine(false),
        execute = { worker.execute(it) },
        report = { online, id, state, error ->
            emit(this.context.getString(R.string.message_tts_session_state,
                engineName(online), id, stateName(state), error))
        },
        failure = { error ->
            com.rokid.security.glass3.open.sdk.uitls.log.L.e("MessageTts", "Manager operation failed: $error")
            emit(this.context.getString(R.string.message_tts_operation_failed, error.javaClass.simpleName))
        },
        invalidSession = { online ->
            emit(this.context.getString(R.string.message_tts_invalid_session, engineName(online)))
        }
    )

    fun onStart() {
        if (closed) return
        active = true
        lifecycle.start()
    }

    fun speak(online: Boolean, text: String, queueMode: Int) {
        if (!active || closed) return
        if (text.isBlank() || queueMode != TtsQueueMode.ADD && queueMode != TtsQueueMode.FLUSH) return
        lifecycle.speak(online, text, queueMode) {
            if (!GlassSdk.isReady()) {
                emit(context.getString(R.string.demo_sdk_initialization_is_incomplete_try_again_later))
                false
            } else !online || canAttemptOnline()
        }
    }

    fun cancelAll(online: Boolean) {
        lifecycle.cancelAll(online) {
            emit(context.getString(R.string.message_tts_cancel_all_requested, engineName(online)))
        }
    }

    fun onStop() {
        active = false
        generation++
        handler.removeCallbacksAndMessages(null)
        lifecycle.stop()
    }

    fun close() {
        if (closed) return
        active = false
        closed = true
        generation++
        lifecycle.close()
        handler.removeCallbacksAndMessages(null)
        // shutdown 会执行已提交的退出清理；不使用 shutdownNow 丢弃清理任务。
        // shutdown drains queued cleanup; shutdownNow could discard it.
        worker.shutdown()
    }

    private fun engineName(online: Boolean) =
        context.getString(if (online) R.string.demo_online_tts_2 else R.string.demo_offline_tts)

    private fun canAttemptOnline(): Boolean {
        val route = runCatching { GlassSdk.getGlassDeviceService()?.getNetworkType() }.getOrNull()
        val internet: Boolean? = if (route == 1) runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            if (manager == null) null else manager.activeNetwork?.let { network ->
                manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } ?: false
        }.getOrNull() else null
        if (!SpeechNetworkPolicy.canAttempt(route, internet)) {
            emit(context.getString(R.string.message_tts_offline_route))
            return false
        }
        return true
    }

    private fun emit(text: String) {
        val submitted = generation
        handler.post { if (active && !closed && generation == submitted) report(text) }
    }

    private fun stateName(state: Int) = when (state) {
        0 -> "QUEUED"
        1 -> "STARTED"
        2 -> "COMPLETED"
        3 -> "CANCELED"
        4 -> "ERROR"
        else -> "UNKNOWN"
    }
}
