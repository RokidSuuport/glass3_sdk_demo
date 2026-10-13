package com.rokid.glass.speech

import android.os.DeadObjectException
import android.os.IBinder
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.uitls.log.L
import com.rokid.security.system.server.tts.listener.ITtsSessionListener
import com.rokid.security.system.server.tts.offline.IOfflineTtsSessionListener
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Process-scoped: no Activity, Context or UI callback is strongly owned by this runtime. */
internal object MessageTtsRuntime {
    private val worker = Executors.newSingleThreadScheduledExecutor()
    val bridge = MessageTtsBridge(::endpoint, object : MessageTtsQueue {
        override fun execute(task: () -> Unit) { worker.execute(task) }
        override fun schedule(delayMillis: Long, task: () -> Unit): () -> Unit {
            val future = worker.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
            return { future.cancel(false); Unit }
        }
    }) { event ->
        L.i("MessageTts", "flow=message_tts event=${event.kind} engine=${if (event.online) "online" else "offline"}" +
            " owner=${event.owner} session=${event.id} state=${event.state} observed=${event.observed}" +
            " error=${event.error} accepted=${event.accepted} detail=${event.detail}")
    }

    private fun endpoint(online: Boolean): MessageTtsEndpoint? = if (online) {
        GlassSdk.getGlassTtsService()?.let { service ->
            BoundEndpoint(service.asBinder(), { service.ttsApiVersion }, service::speakTts,
                service::cancelTts, service::getTtsSessionState) { onState ->
                val listener = object : ITtsSessionListener.Stub() {
                    override fun onTtsSessionState(id: Long, state: Int, code: Int, message: String?) {
                        onState(id, state, code)
                    }
                    // This is the downstream engine connection, not the system-service Binder epoch.
                    override fun onServiceConnectState(connect: Boolean) = Unit
                }
                try { service.registerTtsSessionListener(listener) } catch (error: Exception) {
                    runCatching { service.unregisterTtsSessionListener(listener) }
                    throw error
                }
                val unregister: () -> Unit = { service.unregisterTtsSessionListener(listener) }
                unregister
            }
        }
    } else {
        GlassSdk.getGlassOfflineTtsService()?.let { service ->
            BoundEndpoint(service.asBinder(), { service.offlineTtsApiVersion }, service::speak,
                service::cancel, service::getSessionState) { onState ->
                val listener = object : IOfflineTtsSessionListener.Stub() {
                    override fun onSessionState(id: Long, state: Int, code: Int, message: String?) {
                        onState(id, state, code)
                    }
                }
                try { service.registerSessionListener(listener) } catch (error: Exception) {
                    // Registration is serialized process-wide, so this cannot remove a newer bridge listener.
                    runCatching { service.unregisterSessionListener(listener) }
                    throw error
                }
                val unregister: () -> Unit = { service.unregisterSessionListener(listener) }
                unregister
            }
        }
    }

    private class BoundEndpoint(
        private val binder: IBinder,
        private val getVersion: () -> Int,
        private val submit: (String, Int) -> Long,
        private val stop: (Long) -> Boolean,
        private val state: (Long) -> Int,
        private val subscribe: ((Long, Int, Int) -> Unit) -> (() -> Unit)
    ) : MessageTtsEndpoint {
        override val identity: Any get() = binder
        override fun version() = getVersion()
        override fun isAlive() = binder.isBinderAlive
        override fun isDead(error: Throwable) = error is DeadObjectException || !isAlive()
        override fun watchDeath(onDeath: () -> Unit): () -> Unit {
            val recipient = IBinder.DeathRecipient(onDeath)
            binder.linkToDeath(recipient, 0)
            return { binder.unlinkToDeath(recipient, 0); Unit }
        }
        override fun register(onState: (Long, Int, Int) -> Unit) = subscribe(onState)
        // Always use this Binder. No SDK Manager fallback and no current/global cancellation.
        override fun speak(text: String, queueMode: Int) = submit(text, queueMode)
        override fun cancel(id: Long) = stop(id)
        override fun query(id: Long) = state(id)
    }
}
