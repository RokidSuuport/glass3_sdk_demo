package com.rokid.glass.mediastream.capture

import android.content.Context
import com.rokid.glass.mediastream.capture.internal.AudioSourceFactory
import com.rokid.glass.mediastream.capture.internal.CaptureController
import com.rokid.glass.mediastream.capture.internal.CaptureCoordinator
import com.rokid.glass.mediastream.capture.internal.VideoSourceFactory
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
            return GlassMediaCapture(
                CaptureCoordinator(
                    sdkConnection = GlassSdkConnection(applicationContext),
                    videoSourceFactory = VideoSourceFactory { startupTimeoutMs ->
                        GlassNv21VideoSource(startupTimeoutMs = startupTimeoutMs)
                    },
                    audioSourceFactory = AudioSourceFactory { startupTimeoutMs ->
                        GlassPcmAudioSource(startupTimeoutMs = startupTimeoutMs)
                    },
                ),
            )
        }

        @JvmSynthetic
        internal fun create(controller: CaptureController): GlassMediaCapture =
            GlassMediaCapture(controller)
    }

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

    fun stop() {
        controller.stop()
    }

    fun release() {
        controller.release()
    }

    fun currentStatus(): CaptureStatus = controller.currentStatus()
}
