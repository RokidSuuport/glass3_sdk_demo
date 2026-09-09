package com.rokid.glass.mediastream.transport.webrtc.audio

internal class MutableNanoClock(
    private var nowNs: Long = 0L,
) : NanoClock {
    override fun nowNs(): Long = nowNs

    fun advanceBy(durationNs: Long) {
        nowNs += durationNs
    }
}

internal class ControlledPcmWaitCondition(
    private val clock: MutableNanoClock,
) : PcmWaitCondition {
    val awaitRequestsNs = mutableListOf<Long>()
    var signalCount = 0
        private set
    var onAwait: (requestedNs: Long) -> Long = { requestedNs ->
        clock.advanceBy(requestedNs)
        0L
    }

    override fun awaitNanos(waitForDataNs: Long): Long {
        awaitRequestsNs += waitForDataNs
        return onAwait(waitForDataNs)
    }

    override fun signalAll() {
        signalCount += 1
    }
}
