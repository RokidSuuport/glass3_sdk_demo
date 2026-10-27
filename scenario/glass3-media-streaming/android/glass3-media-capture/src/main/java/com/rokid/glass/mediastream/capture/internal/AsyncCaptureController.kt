package com.rokid.glass.mediastream.capture.internal

import com.rokid.glass.mediastream.capture.*
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal interface CaptureLifecycleQueue {
    fun schedule(delayMs: Long = 0, action: () -> Unit): Cancellation
    fun shutdown()
    fun interface Cancellation { fun cancel() }
}

/** Only lifecycle work is queued, never frames. Coalescing normally needs at most two slots. */
internal class ExecutorCaptureLifecycleQueue : CaptureLifecycleQueue {
    private val slots = Semaphore(8)
    private val executor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "Glass3-CaptureLifecycle").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }

    override fun schedule(delayMs: Long, action: () -> Unit): CaptureLifecycleQueue.Cancellation {
        if (!slots.tryAcquire()) throw RejectedExecutionException("Capture lifecycle queue is full")
        val reserved = AtomicBoolean(true)
        fun freeSlot() { if (reserved.compareAndSet(true, false)) slots.release() }
        val future = try {
            executor.schedule({ freeSlot(); action() }, delayMs, TimeUnit.MILLISECONDS)
        } catch (error: Throwable) { freeSlot(); throw error }
        return CaptureLifecycleQueue.Cancellation { future.cancel(false); freeSlot() }
    }

    override fun shutdown() = executor.shutdown()
}

/**
 * Public lifecycle is nonblocking. Desired state replaces obsolete queued requests, while actual
 * vendor operations remain serialized. STOPPING lasts until cleanup returns; ERROR can be visible
 * while cleanup is still in progress. A delayed restart never occupies the worker by sleeping.
 */
internal class AsyncCaptureController(
    private val delegate: CaptureController,
    private val queue: CaptureLifecycleQueue,
    private val restartDelayMs: Long = 5_000,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : CaptureController {
    private data class Request(
        val generation: Long,
        val options: CaptureOptions,
        val video: VideoFrameListener?,
        val audio: AudioFrameListener?,
        val status: CaptureStatusListener?,
    )
    private sealed interface Action {
        data class Start(val request: Request) : Action
        data object Stop : Action
        data object Release : Action
    }
    private val lock = Any()
    private var generation = 0L
    private var desired: Request? = null
    private var active: Request? = null
    private var statusListener: CaptureStatusListener? = null
    private var cleanupRequired = false
    private var releaseRequested = false
    private var closed = false
    private var running = false
    private var pending: CaptureLifecycleQueue.Cancellation? = null
    private var stoppedAtMs: Long? = null
    private var status = CaptureStatus(CaptureState.IDLE)

    override fun start(options: CaptureOptions, videoListener: VideoFrameListener?, audioListener: AudioFrameListener?, statusListener: CaptureStatusListener?) {
        require(videoListener != null || audioListener != null) { "At least one media listener is required" }
        val notification = synchronized(lock) {
            check(!releaseRequested) { "Capture has been released" }
            if (desired != null || (active != null && !cleanupRequired)) return
            generation++
            desired = Request(generation, options, videoListener, audioListener, statusListener)
            this.statusListener = statusListener
            status = CaptureStatus(CaptureState.PREPARING)
            scheduleLocked()
            generation to status
        }
        notify(notification)
    }

    override fun stop() = requestStop(release = false)
    override fun release() = requestStop(release = true)

    private fun requestStop(release: Boolean) {
        val notification = synchronized(lock) {
            if (closed || releaseRequested) return
            generation++
            desired = null
            delegate.invalidateCallbacks()
            cleanupRequired = active != null
            releaseRequested = release
            status = status.copy(state = if (active != null || release) CaptureState.STOPPING else CaptureState.IDLE, failure = null)
            scheduleLocked()
            generation to status
        }
        notify(notification)
    }

    override fun currentStatus(): CaptureStatus {
        val refresh = synchronized(lock) { active != null }
        val metrics = if (refresh) runCatching { delegate.currentStatus() }.getOrNull() else null
        return synchronized(lock) {
            if (metrics == null || active == null) status else status.copy(
                videoMetrics = metrics.videoMetrics, audioMetrics = metrics.audioMetrics,
            )
        }
    }

    override fun isCleanupInProgress(): Boolean = synchronized(lock) {
        !closed && (cleanupRequired || releaseRequested || delegate.isCleanupInProgress())
    }

    private fun scheduleLocked(delayMs: Long = 0) {
        if (closed || running) return
        pending?.cancel()
        pending = queue.schedule(delayMs, ::drain)
    }

    private fun drain() {
        synchronized(lock) { pending = null; if (closed) return; running = true }
        try {
            while (true) {
                val action = synchronized(lock) {
                    // Fatal coordinator cleanup is already queued on this same worker. Never wait
                    // for it here: its terminal notification will wake this state machine again.
                    if (delegate.isCleanupInProgress()) return
                    when {
                        active != null && (cleanupRequired || desired !== active) -> Action.Stop
                        releaseRequested -> Action.Release
                        desired != null && active == null -> {
                            val remaining = stoppedAtMs?.let { restartDelayMs - (nowMs() - it) } ?: 0
                            if (remaining > 0) {
                                running = false
                                scheduleLocked(remaining)
                                return
                            }
                            Action.Start(checkNotNull(desired)).also { active = desired }
                        }
                        else -> return
                    }
                }
                when (action) {
                    is Action.Start -> startDelegate(action.request)
                    Action.Stop -> {
                        val error = runCatching(delegate::stop).exceptionOrNull()
                        if (delegate.isCleanupInProgress()) return
                        val finalMetrics = runCatching(delegate::currentStatus).getOrNull()
                        val notification = synchronized(lock) {
                            active = null
                            cleanupRequired = false
                            stoppedAtMs = nowMs()
                            finalMetrics?.let { status = status.copy(videoMetrics = it.videoMetrics, audioMetrics = it.audioMetrics) }
                            if (error != null) status = failureStatus(error)
                            else if (desired == null && !releaseRequested && status.state != CaptureState.ERROR) {
                                status = status.copy(state = CaptureState.IDLE)
                            }
                            generation to status
                        }
                        notify(notification)
                    }
                    Action.Release -> {
                        val error = runCatching(delegate::release).exceptionOrNull()
                        if (delegate.isCleanupInProgress()) return
                        val notification = synchronized(lock) {
                            closed = true
                            status = if (error == null) status.copy(state = CaptureState.RELEASED) else failureStatus(error)
                            generation to status
                        }
                        try { notify(notification) } finally {
                            synchronized(lock) { statusListener = null }
                            queue.shutdown()
                        }
                        return
                    }
                }
            }
        } finally {
            synchronized(lock) {
                running = false
                val workChanged = releaseRequested || cleanupRequired || (desired != null && desired !== active)
                if (!closed && pending == null && workChanged && !delegate.isCleanupInProgress()) scheduleLocked()
            }
        }
    }

    private fun startDelegate(request: Request) {
        try {
            delegate.start(request.options,
                request.video?.let { listener -> VideoFrameListener { if (isCurrent(request)) listener.onVideoFrame(it) } },
                request.audio?.let { listener -> AudioFrameListener { if (isCurrent(request)) listener.onAudioFrame(it) } },
                CaptureStatusListener { onDelegateStatus(request, it) },
            )
        } catch (error: Throwable) { onDelegateStatus(request, failureStatus(error)) }
    }

    private fun isCurrent(request: Request): Boolean = synchronized(lock) {
        !releaseRequested && generation == request.generation && desired === request
    }

    private fun onDelegateStatus(request: Request, update: CaptureStatus) {
        val notification = synchronized(lock) {
            if (active !== request) return
            if (generation != request.generation || releaseRequested) {
                scheduleLocked()
                return
            }
            if (update.state == CaptureState.ERROR) {
                desired = null
                cleanupRequired = true
                status = update
            } else if (update.state == CaptureState.CAPTURING || update.state == CaptureState.PREPARING) {
                status = update
            }
            scheduleLocked()
            generation to status
        }
        notify(notification)
    }

    private fun notify(notification: Pair<Long, CaptureStatus>) {
        val listener = synchronized(lock) {
            statusListener.takeIf { generation == notification.first && status == notification.second }
        }
        runCatching { listener?.onStatusChanged(notification.second) }
    }

    private fun failureStatus(error: Throwable): CaptureStatus = CaptureStatus(CaptureState.ERROR,
        failure = MediaFailureCatalog.forCode(MediaErrorCode.SDK_DISCONNECTED).copy(
            technicalMessage = "Capture lifecycle operation failed: ${error.message ?: error.javaClass.simpleName}", cause = error,
        ),
    )
}
