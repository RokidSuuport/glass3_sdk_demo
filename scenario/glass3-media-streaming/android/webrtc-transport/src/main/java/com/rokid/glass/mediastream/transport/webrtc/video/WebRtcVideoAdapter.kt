package com.rokid.glass.mediastream.transport.webrtc.video

import com.rokid.glass.mediastream.capture.Nv21Frame
import com.rokid.glass.mediastream.capture.VideoFrameListener
import livekit.org.webrtc.NV21Buffer
import livekit.org.webrtc.VideoFrame

internal fun interface VideoFrameTarget {
    fun deliver(frame: VideoFrame)
}

internal class WebRtcVideoAdapter(
    private val target: VideoFrameTarget,
) : VideoFrameListener {
    override fun onVideoFrame(frame: Nv21Frame) {
        val retained = frame.retain()
        val buffer = NV21Buffer(
            retained.data,
            retained.width,
            retained.height,
            retained::close,
        )
        val rtcFrame = VideoFrame(buffer, NO_ROTATION, retained.timestampNs)
        try {
            target.deliver(rtcFrame)
        } finally {
            rtcFrame.release()
        }
    }

    private companion object {
        const val NO_ROTATION = 0
    }
}
