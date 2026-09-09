package com.rokid.glass.mediastream.capture.internal.audio

import android.os.IBinder
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.callback.AbsAudioCallback
import com.rokid.security.system.server.media.IMediaServer
import java.util.IdentityHashMap

internal interface RokidAudioService {
    fun startAudioRecord(
        callback: RokidAudioCallback,
        onDisconnected: (Throwable?) -> Unit,
    )

    fun stopAudioRecord(callback: RokidAudioCallback)
}

internal fun interface RokidAudioCallback {
    fun onAudioStream(buffer: ByteArray?, bufferLen: Int)
}

internal class RokidAudioServiceGateway(
    private val mediaServiceProvider: () -> RokidAudioService? = {
        GlassSdk.getGlassMediaService()?.let(::GlassSdkAudioService)
    },
    private val nanoTime: () -> Long = System::nanoTime,
) : AudioServiceGateway {
    private data class Session(
        val service: RokidAudioService,
        val sdkCallback: RokidAudioCallback,
    )

    private val lock = Any()
    private val sessions = IdentityHashMap<AudioServiceGateway.Callback, Session>()

    override fun start(callback: AudioServiceGateway.Callback): Boolean {
        val service = runCatching(mediaServiceProvider).getOrNull() ?: return false
        val sdkCallback = RokidAudioCallback { buffer, bufferLen ->
            if (buffer != null && bufferLen > 0) {
                callback.onAudioStream(
                    buffer = buffer,
                    bufferLen = minOf(bufferLen, buffer.size),
                    timestampNs = nanoTime(),
                )
            }
        }
        val session = Session(service, sdkCallback)
        synchronized(lock) {
            check(!sessions.containsKey(callback)) { "Audio callback is already active" }
            sessions[callback] = session
        }

        return try {
            service.startAudioRecord(sdkCallback) { cause ->
                val current = synchronized(lock) { sessions[callback] }
                if (current === session) callback.onDisconnected(cause)
            }
            true
        } catch (_: Throwable) {
            synchronized(lock) {
                if (sessions[callback] === session) sessions.remove(callback)
            }
            runCatching { service.stopAudioRecord(sdkCallback) }
            false
        }
    }

    override fun stop(callback: AudioServiceGateway.Callback) {
        val session = synchronized(lock) { sessions.remove(callback) } ?: return
        runCatching { session.service.stopAudioRecord(session.sdkCallback) }
    }
}

private class GlassSdkAudioService(
    private val mediaService: IMediaServer,
) : RokidAudioService {
    private data class SdkSession(
        val callback: AbsAudioCallback,
        val deathRecipient: IBinder.DeathRecipient,
    )

    private val lock = Any()
    private val sessions = IdentityHashMap<RokidAudioCallback, SdkSession>()

    override fun startAudioRecord(
        callback: RokidAudioCallback,
        onDisconnected: (Throwable?) -> Unit,
    ) {
        val binder = mediaService.asBinder()
        val deathRecipient = IBinder.DeathRecipient { onDisconnected(null) }
        val sdkCallback = object : AbsAudioCallback() {
            override fun onAudioStream(buffer: ByteArray?, bufferLen: Int) {
                callback.onAudioStream(buffer, bufferLen)
            }
        }
        val session = SdkSession(sdkCallback, deathRecipient)
        synchronized(lock) {
            check(!sessions.containsKey(callback)) { "Rokid audio callback is already active" }
            sessions[callback] = session
        }
        try {
            binder.linkToDeath(deathRecipient, 0)
            mediaService.startAudioRecord(sdkCallback)
        } catch (error: Throwable) {
            synchronized(lock) {
                if (sessions[callback] === session) sessions.remove(callback)
            }
            runCatching { binder.unlinkToDeath(deathRecipient, 0) }
            throw error
        }
    }

    override fun stopAudioRecord(callback: RokidAudioCallback) {
        val session = synchronized(lock) { sessions.remove(callback) } ?: return
        try {
            mediaService.stopAudioRecord(session.callback)
        } finally {
            runCatching {
                mediaService.asBinder().unlinkToDeath(session.deathRecipient, 0)
            }
        }
    }
}
