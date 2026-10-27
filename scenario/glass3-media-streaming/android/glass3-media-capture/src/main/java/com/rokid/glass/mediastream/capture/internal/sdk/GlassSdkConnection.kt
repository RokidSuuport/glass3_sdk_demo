package com.rokid.glass.mediastream.capture.internal.sdk

import android.content.Context
import com.rokid.glass.mediastream.capture.MediaErrorCode
import com.rokid.glass.mediastream.capture.MediaFailure
import com.rokid.glass.mediastream.capture.MediaFailureCatalog
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.client.IServiceConnectionCallback

internal interface SdkConnection {
    fun bind(listener: Listener)
    fun unbind()

    interface Listener {
        fun onReady()
        fun onFailure(failure: MediaFailure)
    }
}

internal interface GlassSdkGateway {
    fun isReady(): Boolean
    fun bind(callback: IServiceConnectionCallback)
    fun unbind()
}

private class RokidGlassSdkGateway(applicationContext: Context) : GlassSdkGateway {
    private val context = applicationContext

    override fun isReady(): Boolean = GlassSdk.isReady()

    override fun bind(callback: IServiceConnectionCallback) {
        GlassSdk.bindSecurityService(context, callback)
    }

    override fun unbind() {
        GlassSdk.unbindSecurityService()
    }
}

internal class GlassSdkConnection(
    private val gateway: GlassSdkGateway,
) : SdkConnection {
    constructor(context: Context) : this(
        RokidGlassSdkGateway(
            requireNotNull(context.applicationContext) { "An application context is required" },
        ),
    )

    private val lock = Any()
    private var generation = 0L
    private var listener: SdkConnection.Listener? = null
    private var readyReported = false
    private var terminalReported = false
    private var ownsBind = false
    private var cancelledOwnedBindGeneration: Long? = null

    override fun bind(listener: SdkConnection.Listener) {
        val callbackGeneration = synchronized(lock) {
            check(this.listener == null) { "Glass SDK connection is already active" }
            generation += 1
            this.listener = listener
            readyReported = false
            terminalReported = false
            ownsBind = false
            cancelledOwnedBindGeneration = null
            generation
        }

        val alreadyReady = try {
            gateway.isReady()
        } catch (error: Throwable) {
            fail(callbackGeneration, error, "Glass SDK readiness check failed")
            return
        }
        if (alreadyReady) {
            reportReady(callbackGeneration)
            return
        }

        val callback = serviceCallback(callbackGeneration)
        synchronized(lock) {
            if (!isCurrentLocked(callbackGeneration)) return
            ownsBind = true
        }
        try {
            gateway.bind(callback)
        } catch (error: Throwable) {
            fail(callbackGeneration, error, "Glass SDK security service bind failed")
        }
    }

    override fun unbind() {
        val shouldUnbind = synchronized(lock) {
            if (listener == null && !ownsBind) return
            cancelledOwnedBindGeneration = generation.takeIf { ownsBind && !readyReported }
            generation += 1
            listener = null
            readyReported = false
            terminalReported = false
            ownsBind.also { ownsBind = false }
        }
        if (shouldUnbind) gateway.unbind()
    }

    private fun serviceCallback(callbackGeneration: Long) =
        object : IServiceConnectionCallback {
            override fun onServiceConnected() {
                reportReady(callbackGeneration)
            }

            override fun onServiceDisconnected() {
                fail(callbackGeneration, null, "Glass SDK security service disconnected")
            }

            override fun onBindingDied() {
                fail(callbackGeneration, null, "Glass SDK security service binding died")
            }
        }

    private fun reportReady(callbackGeneration: Long) {
        val currentListener = synchronized(lock) {
            if (!isCurrentLocked(callbackGeneration) || readyReported || terminalReported) {
                if (cancelledOwnedBindGeneration == callbackGeneration && listener == null) {
                    cancelledOwnedBindGeneration = null
                    // SDK 2.2.0-E ignores unbind before it becomes ready. Finish only our cancelled
                    // bind; holding the lock prevents a new local generation from borrowing it.
                    runCatching(gateway::unbind)
                }
                return
            }
            readyReported = true
            listener
        }
        currentListener?.onReady()
    }

    private fun fail(callbackGeneration: Long, cause: Throwable?, technicalMessage: String) {
        val notification = synchronized(lock) {
            if (!isCurrentLocked(callbackGeneration) || terminalReported) return
            terminalReported = true
            val code = if (readyReported) {
                MediaErrorCode.SDK_DISCONNECTED
            } else {
                MediaErrorCode.SDK_NOT_READY
            }
            val failure = MediaFailureCatalog.forCode(code).copy(
                technicalMessage = technicalMessage +
                    cause?.message?.let { ": $it" }.orEmpty(),
                cause = cause,
            )
            listener to failure
        }
        notification.first?.onFailure(notification.second)
    }

    private fun isCurrentLocked(callbackGeneration: Long): Boolean =
        listener != null && generation == callbackGeneration
}
