package com.rokid.glass.mediastream.sender.capture

import com.rokid.glass.mediastream.capture.CaptureState

/** 页面会话回调的代际门控，媒体帧和状态回调可能来自不同线程。 */
internal class CaptureCallbackGate {
    private var generation = 0L
    private var acceptingFrames = false
    private var stopping = false

    @Synchronized fun begin(): Long {
        acceptingFrames = true
        stopping = false
        return ++generation
    }

    // stop() only requests asynchronous cleanup. Keep observing this session's
    // STOPPING / IDLE / ERROR while immediately rejecting old frames and snapshots.
    @Synchronized fun stop() {
        acceptingFrames = false
        stopping = true
    }

    @Synchronized fun invalidate() {
        generation++
        acceptingFrames = false
        stopping = true
    }

    @Synchronized fun current(): Long = generation
    @Synchronized fun acceptsFrame(token: Long): Boolean = acceptingFrames && token == generation

    @Synchronized fun acceptsStatus(token: Long, state: CaptureState): Boolean =
        token == generation && (!stopping ||
            (state != CaptureState.PREPARING && state != CaptureState.CAPTURING))
}
