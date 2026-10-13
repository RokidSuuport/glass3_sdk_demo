package com.rokid.glass.speech

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.rokid.glesse.R
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.tts.TtsQueueMode

/** UI/lifecycle adapter. The shared bridge owns Binder registration and this page's request IDs. */
class MessageTtsController(context: Context, private val report: (String) -> Unit) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val bridge = MessageTtsRuntime.bridge
    @Volatile private var active = false
    @Volatile private var closed = false
    @Volatile private var generation = 0
    // Keep this lambda here; the process bridge holds only a WeakReference to it.
    private val eventSink: (MessageTtsBridge.Event) -> Unit = { event -> reportEvent(event) }
    private val owner = bridge.openOwner(eventSink)

    fun onStart() {
        active = true
        bridge.reconcile(owner)
    }

    fun speak(online: Boolean, text: String, queueMode: Int) {
        if (!active || closed) return
        if (text.isBlank() || queueMode != TtsQueueMode.ADD && queueMode != TtsQueueMode.FLUSH) return
        bridge.speak(owner, online, text, queueMode) {
            if (!GlassSdk.isReady()) {
                emit(context.getString(R.string.demo_sdk_initialization_is_incomplete_try_again_later))
                false
            } else !online || canAttemptOnline()
        }
    }

    fun cancelOwned(online: Boolean) {
        if (active && !closed) bridge.cancelOwned(owner, online)
    }

    fun onStop() {
        active = false
        generation++
        bridge.cancelOwned(owner, reportEmpty = false)
    }

    fun close() {
        if (closed) return
        active = false
        closed = true
        generation++
        handler.removeCallbacksAndMessages(null)
        bridge.closeOwner(owner)
    }

    private fun reportEvent(event: MessageTtsBridge.Event) {
        val engine = context.getString(if (event.online) R.string.demo_online_tts_2 else R.string.demo_offline_tts)
        val text = when (event.kind) {
            MessageTtsBridge.Kind.ACCEPTED -> return // The following query/callback reports the actual state.
            MessageTtsBridge.Kind.STATE -> context.getString(R.string.message_tts_session_state,
                engine, event.id, stateName(event.state), event.error)
            MessageTtsBridge.Kind.QUERY -> context.getString(R.string.message_tts_query_state,
                engine, event.id, stateName(event.observed), stateName(event.state))
            MessageTtsBridge.Kind.CANCEL -> context.getString(R.string.message_tts_cancel_result,
                engine, event.id, event.accepted.toString())
            MessageTtsBridge.Kind.API_REQUIRED -> context.getString(R.string.message_tts_session_api_required, engine)
            MessageTtsBridge.Kind.LISTENER_FAILED -> context.getString(R.string.message_tts_listener_failed, engine)
            MessageTtsBridge.Kind.INVALID_SESSION -> context.getString(R.string.message_tts_invalid_session, engine)
            MessageTtsBridge.Kind.INVALIDATED -> context.getString(R.string.message_tts_session_invalidated,
                engine, event.id, event.detail)
            MessageTtsBridge.Kind.PENDING_CANCELED -> context.getString(R.string.message_tts_start_canceled, engine)
            MessageTtsBridge.Kind.NO_SESSIONS -> context.getString(R.string.message_tts_no_owned_session, engine)
            MessageTtsBridge.Kind.LIMIT -> context.getString(R.string.message_tts_queue_full)
            MessageTtsBridge.Kind.FAILURE -> context.getString(R.string.message_tts_operation_failed, event.detail)
        }
        emit(text)
    }

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
