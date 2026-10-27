package com.rokid.glass.mediastream.capture

import android.content.Context
import com.rokid.glass.mediastream.capture.internal.AudioSourceFactory
import com.rokid.glass.mediastream.capture.internal.CaptureController
import com.rokid.glass.mediastream.capture.internal.CaptureCoordinator
import com.rokid.glass.mediastream.capture.internal.VideoSourceFactory
import com.rokid.glass.mediastream.capture.internal.AsyncCaptureController
import com.rokid.glass.mediastream.capture.internal.ExecutorCaptureLifecycleQueue
import com.rokid.glass.mediastream.capture.internal.audio.GlassPcmAudioSource
import com.rokid.glass.mediastream.capture.internal.sdk.GlassSdkConnection
import com.rokid.glass.mediastream.capture.internal.video.GlassNv21VideoSource

class GlassMediaCapture private constructor(
    private val controller: CaptureController,
) {
    companion object {
        @JvmStatic
        fun create(context: Context): GlassMediaCapture {
            val applicationContext = requireNotNull(context.applicationContext) {
                "An application context is required"
            }
            val lifecycle = ExecutorCaptureLifecycleQueue()
            return GlassMediaCapture(AsyncCaptureController(
                delegate = CaptureCoordinator(
                    sdkConnection = GlassSdkConnection(applicationContext),
                    videoSourceFactory = VideoSourceFactory { startupTimeoutMs ->
                        GlassNv21VideoSource(startupTimeoutMs = startupTimeoutMs)
                    },
                    audioSourceFactory = AudioSourceFactory { startupTimeoutMs ->
                        GlassPcmAudioSource(startupTimeoutMs = startupTimeoutMs)
                    },
                    dispatchOperation = { operation -> lifecycle.schedule(action = operation) },
                    deferCleanupWait = true,
                ),
                queue = lifecycle,
            ))
        }

        @JvmSynthetic
        internal fun create(controller: CaptureController): GlassMediaCapture =
            GlassMediaCapture(AsyncCaptureController(controller, ExecutorCaptureLifecycleQueue()))
    }

    /** Queues capture startup; null listeners disable that medium. Callbacks must stay lightweight. */
    fun start(
        videoListener: VideoFrameListener?,
        audioListener: AudioFrameListener?,
    ) = start(
        options = CaptureOptions(),
        videoListener = videoListener,
        audioListener = audioListener,
        statusListener = null,
    )

    @JvmOverloads
    fun start(
        options: CaptureOptions,
        videoListener: VideoFrameListener?,
        audioListener: AudioFrameListener?,
        statusListener: CaptureStatusListener? = null,
    ) {
        controller.start(options, videoListener, audioListener, statusListener)
    }

    /**
     * Cancels pending startup and requests cleanup without blocking the caller. Observe IDLE before
     * using the released media elsewhere. A subsequent start waits for cleanup and a recovery window.
     */
    fun stop() {
        controller.stop()
    }

    /** Permanently closes this capture asynchronously; RELEASED means queued cleanup has completed. */
    fun release() {
        controller.release()
    }

    fun currentStatus(): CaptureStatus = controller.currentStatus()
}
