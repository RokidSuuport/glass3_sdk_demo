package com.rokid.glass.mediastream.streaming.internal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rokid.glass.mediastream.capture.AudioFrameListener
import com.rokid.glass.mediastream.capture.CaptureOptions
import com.rokid.glass.mediastream.capture.CaptureStatus
import com.rokid.glass.mediastream.capture.CaptureStatusListener
import com.rokid.glass.mediastream.capture.GlassMediaCapture
import com.rokid.glass.mediastream.capture.MediaErrorCode
import com.rokid.glass.mediastream.capture.MediaFailure
import com.rokid.glass.mediastream.capture.MediaFailureCatalog
import com.rokid.glass.mediastream.capture.VideoFrameListener
import com.rokid.glass.mediastream.streaming.StreamingOptions
import com.rokid.glass.mediastream.streaming.StreamingStatus
import com.rokid.glass.mediastream.streaming.StreamingStatusListener
import com.rokid.glass.mediastream.transport.signaling.SignalingClient
import com.rokid.glass.mediastream.transport.signaling.SignalingMessage
import com.rokid.glass.mediastream.transport.webrtc.MediaPublisher
import com.rokid.glass.mediastream.transport.webrtc.WebRtcPublisher

internal interface StreamingController {
    fun start(options: StreamingOptions, listener: StreamingStatusListener?)
    fun stop()
    fun release()
    fun currentStatus(): StreamingStatus
}

internal interface CaptureSession {
    fun start(
        options: CaptureOptions,
        videoListener: VideoFrameListener?,
        audioListener: AudioFrameListener?,
        statusListener: CaptureStatusListener?,
    )

    fun stop()
    fun release()
    fun currentStatus(): CaptureStatus
}

internal interface SignalingSession {
    interface Listener {
        fun onOpen()
        fun onMessage(message: SignalingMessage)
        fun onClosed(reason: String)
        fun onFailure(error: Throwable)
    }

    fun connect(url: String, roomId: String, listener: Listener)
    fun send(message: SignalingMessage): Boolean
    fun close()
    fun dispose()
}

internal fun interface SignalingSessionFactory {
    fun create(): SignalingSession
}

internal fun interface PublisherSessionFactory {
    fun create(): MediaPublisher
}

internal fun interface Cancellable {
    fun cancel()
}

internal fun interface RetryScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): Cancellable
}

internal fun interface CallbackExecutor {
    fun execute(task: () -> Unit)
}

internal fun interface PermissionChecker {
    fun check(options: StreamingOptions): MediaFailure?
}

internal fun interface StreamingLogger {
    fun error(failure: MediaFailure)
}

internal class GlassCaptureSession(
    private val capture: GlassMediaCapture,
) : CaptureSession {
    override fun start(
        options: CaptureOptions,
        videoListener: VideoFrameListener?,
        audioListener: AudioFrameListener?,
        statusListener: CaptureStatusListener?,
    ) {
        capture.start(options, videoListener, audioListener, statusListener)
    }

    override fun stop() = capture.stop()

    override fun release() = capture.release()

    override fun currentStatus(): CaptureStatus = capture.currentStatus()
}

internal class GlassSignalingSession(
    private val client: SignalingClient,
) : SignalingSession {
    override fun connect(url: String, roomId: String, listener: SignalingSession.Listener) {
        client.connect(
            url,
            roomId,
            object : SignalingClient.Listener {
                override fun onOpen() = listener.onOpen()

                override fun onMessage(message: SignalingMessage) = listener.onMessage(message)

                override fun onClosed(reason: String) = listener.onClosed(reason)

                override fun onFailure(error: Throwable) = listener.onFailure(error)
            },
        )
    }

    override fun send(message: SignalingMessage): Boolean = client.send(message)

    override fun close() = client.close()

    override fun dispose() = client.dispose()
}

internal class HandlerRetryScheduler(
    private val handler: Handler,
) : RetryScheduler {
    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        val runnable = Runnable(task)
        handler.postDelayed(runnable, delayMs)
        return Cancellable { handler.removeCallbacks(runnable) }
    }
}

internal class HandlerCallbackExecutor(
    private val handler: Handler,
) : CallbackExecutor {
    override fun execute(task: () -> Unit) {
        handler.post(task)
    }
}

internal class AndroidPermissionChecker(
    private val context: Context,
) : PermissionChecker {
    override fun check(options: StreamingOptions): MediaFailure? {
        val missingVideo = options.videoEnabled &&
            context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        val missingAudio = options.audioEnabled &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        return if (missingVideo || missingAudio) {
            MediaFailureCatalog.forCode(MediaErrorCode.PERMISSION_REQUIRED)
        } else {
            null
        }
    }
}

internal object AndroidStreamingLogger : StreamingLogger {
    override fun error(failure: MediaFailure) {
        val technical = failure.technicalMessage.ifBlank {
            "${failure.code}: ${failure.userMessage}"
        }
        Log.e(LOG_TAG, technical, failure.cause)
    }

    private const val LOG_TAG = "GlassMediaStream"
}

internal object StreamingRuntimeFactory {
    fun create(context: Context): StreamingController {
        val applicationContext = requireNotNull(context.applicationContext) {
            "An application context is required"
        }
        val mainHandler = Handler(Looper.getMainLooper())
        // GlassMediaCapture owns its serial worker and cancellable recovery window. Wrapping it
        // again would treat an asynchronous stop return as completed cleanup and delay it twice.
        val capture = GlassCaptureSession(GlassMediaCapture.create(applicationContext))
        return StreamingCoordinator(
            capture = capture,
            signalingFactory = SignalingSessionFactory {
                GlassSignalingSession(SignalingClient())
            },
            publisherFactory = PublisherSessionFactory {
                WebRtcPublisher(applicationContext)
            },
            scheduler = HandlerRetryScheduler(mainHandler),
            callbackExecutor = HandlerCallbackExecutor(mainHandler),
            permissionChecker = AndroidPermissionChecker(applicationContext),
            logger = AndroidStreamingLogger,
        )
    }
}
