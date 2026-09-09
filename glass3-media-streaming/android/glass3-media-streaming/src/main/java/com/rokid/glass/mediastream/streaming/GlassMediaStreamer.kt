package com.rokid.glass.mediastream.streaming

import android.content.Context
import com.rokid.glass.mediastream.streaming.internal.StreamingController
import com.rokid.glass.mediastream.streaming.internal.StreamingRuntimeFactory

/**
 * One-call entry for sending Glass3 NV21 video and PCM audio to the browser receiver.
 *
 * Applications only provide the signaling address and observe [StreamingStatus]. Camera,
 * microphone, signaling, WebRTC negotiation, bounded reconnection and cleanup are owned here.
 */
class GlassMediaStreamer private constructor(
    private val controller: StreamingController,
) {
    companion object {
        @JvmStatic
        fun create(context: Context): GlassMediaStreamer =
            GlassMediaStreamer(StreamingRuntimeFactory.create(context))

        @JvmSynthetic
        internal fun create(controller: StreamingController): GlassMediaStreamer =
            GlassMediaStreamer(controller)
    }

    fun start(serverUrl: String) {
        controller.start(StreamingOptions(serverUrl), null)
    }

    @JvmOverloads
    fun start(
        options: StreamingOptions,
        listener: StreamingStatusListener? = null,
    ) {
        controller.start(options, listener)
    }

    fun stop() = controller.stop()

    fun release() = controller.release()

    fun currentStatus(): StreamingStatus = controller.currentStatus()
}
