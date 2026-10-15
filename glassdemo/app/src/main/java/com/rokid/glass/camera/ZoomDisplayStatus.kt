package com.rokid.glass.camera

/** 仅控制界面等待提示，不改变 SDK 请求或帧确认。 / UI waiting state only; never changes SDK evidence. */
internal class ZoomDisplayStatus {
    enum class Status { LOADING, AVAILABLE, UNAVAILABLE }
    var status = Status.LOADING
        private set
    var remainingMs = 3000L
        private set
    private var initialized = false
    private var pendingRevision: Long? = null
    private var pendingSince = 0L

    fun update(revision: Long?, ready: Boolean, appliedZoom: Float, nowMs: Long): Status {
        if (ready && appliedZoom.isFinite()) {
            status = Status.AVAILABLE
            remainingMs = 0L
            initialized = false
            return status
        }
        if (!initialized || revision != pendingRevision) {
            initialized = true
            pendingRevision = revision
            pendingSince = nowMs
        }
        remainingMs = (3000L - (nowMs - pendingSince)).coerceAtLeast(0L)
        status = if (remainingMs == 0L) Status.UNAVAILABLE else Status.LOADING
        return status
    }
}
