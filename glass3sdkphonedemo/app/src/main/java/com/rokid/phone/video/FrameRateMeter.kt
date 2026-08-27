package com.rokid.phone.video

/** Calculates a frame rate from the real elapsed sampling time. */
class FrameRateMeter(private val samplePeriodMs: Long = 1_000L) {
    private var startedAtMs: Long? = null
    private var frameCount = 0

    @Synchronized
    fun recordFrame(nowMs: Long = System.currentTimeMillis()): Float? {
        val startedAt = startedAtMs
        if (startedAt == null) {
            startedAtMs = nowMs
            frameCount = 0
            return null
        }
        frameCount++
        val elapsedMs = nowMs - startedAt
        if (elapsedMs < samplePeriodMs) return null

        val fps = frameCount * 1_000f / elapsedMs.coerceAtLeast(1L)
        startedAtMs = nowMs
        frameCount = 0
        return fps
    }

    @Synchronized
    fun reset() {
        startedAtMs = null
        frameCount = 0
    }
}
