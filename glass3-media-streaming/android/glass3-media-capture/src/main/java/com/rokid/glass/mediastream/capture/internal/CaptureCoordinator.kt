package com.rokid.glass.mediastream.capture.internal

import com.rokid.glass.mediastream.capture.AudioCaptureMetrics
import com.rokid.glass.mediastream.capture.AudioFrameListener
import com.rokid.glass.mediastream.capture.CaptureOptions
import com.rokid.glass.mediastream.capture.CaptureState
import com.rokid.glass.mediastream.capture.CaptureStatus
import com.rokid.glass.mediastream.capture.CaptureStatusListener
import com.rokid.glass.mediastream.capture.MediaErrorCode
import com.rokid.glass.mediastream.capture.MediaFailure
import com.rokid.glass.mediastream.capture.MediaFailureCatalog
import com.rokid.glass.mediastream.capture.Nv21Frame
import com.rokid.glass.mediastream.capture.PcmFrame
import com.rokid.glass.mediastream.capture.VideoCaptureMetrics
import com.rokid.glass.mediastream.capture.VideoFrameListener
import com.rokid.glass.mediastream.capture.internal.audio.AudioSource
import com.rokid.glass.mediastream.capture.internal.sdk.SdkConnection
import com.rokid.glass.mediastream.capture.internal.video.VideoSource
import com.rokid.glass.mediastream.capture.internal.video.TimeoutScheduler
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal interface CaptureController {
    fun start(
        options: CaptureOptions,
        videoListener: VideoFrameListener?,
        audioListener: AudioFrameListener?,
        statusListener: CaptureStatusListener?,
    )

    fun stop()
    fun release()
    fun currentStatus(): CaptureStatus
    fun isCleanupInProgress(): Boolean = false
    /** Nonblocking cancellation of callbacks/start work; actual resources are released by stop. */
    fun invalidateCallbacks() = Unit
}

internal fun interface VideoSourceFactory {
    fun create(startupTimeoutMs: Long): VideoSource
}

internal fun interface AudioSourceFactory {
    fun create(startupTimeoutMs: Long): AudioSource
}

internal class CaptureCoordinator(
    private val sdkConnection: SdkConnection,
    private val videoSourceFactory: VideoSourceFactory,
    private val audioSourceFactory: AudioSourceFactory,
    private val bindTimeoutScheduler: TimeoutScheduler = CaptureBindTimeoutScheduler,
    private val dispatchOperation: (() -> Unit) -> Unit = { it() },
    private val deferCleanupWait: Boolean = false,
) : CaptureController {
    private class Session(
        val generation: Long,
        val options: CaptureOptions,
        val videoListener: VideoFrameListener?,
        val audioListener: AudioFrameListener?,
        val statusListener: CaptureStatusListener?,
        val videoSource: VideoSource?,
        val audioSource: AudioSource?,
    ) {
        var sdkBindAttempted = false
        var videoStartAttempted = false
        var audioStartAttempted = false
        var firstVideoFrameReceived = false
        var firstAudioFrameReceived = false
        var bindTimeout: TimeoutScheduler.Cancellable? = null
    }

    private data class StatusNotification(
        val generation: Long,
        val listener: CaptureStatusListener?,
        val status: CaptureStatus,
    )

    private data class PendingStart(
        val options: CaptureOptions,
        val videoListener: VideoFrameListener?,
        val audioListener: AudioFrameListener?,
        val statusListener: CaptureStatusListener?,
    )

    private data class CleanupCompletion(
        val notification: StatusNotification?,
        val pendingStart: PendingStart?,
    )

    private data class CleanupPlan(
        val session: Session,
        val finalState: CaptureState,
        val stoppingNotification: StatusNotification?,
    )

    private val stateLock = ReentrantLock(true)
    private val lifecycleIdle = stateLock.newCondition()
    private val statusNotificationsIdle = stateLock.newCondition()
    private val operationLock = ReentrantLock(true)
    private val statusNotificationDepth = ThreadLocal.withInitial { 0 }
    private val mediaNotificationDepth = ThreadLocal.withInitial { 0 }
    private var inFlightStatusNotifications = 0
    private var generation = 0L
    private var state = CaptureState.IDLE
    private var failure: MediaFailure? = null
    private var session: Session? = null
    private var cleaningUp = false
    private var desiredTerminalState: CaptureState? = null
    private var pendingStart: PendingStart? = null
    private var lastVideoMetrics = VideoCaptureMetrics()
    private var lastAudioMetrics = AudioCaptureMetrics()

    override fun start(
        options: CaptureOptions,
        videoListener: VideoFrameListener?,
        audioListener: AudioFrameListener?,
        statusListener: CaptureStatusListener?,
    ) {
        val startedSession = stateLock.withLock {
            while (cleaningUp) {
                if (isInCallback()) {
                    check(desiredTerminalState != CaptureState.RELEASED) {
                        "Capture has been released"
                    }
                    if (pendingStart != null) return
                    require(videoListener != null || audioListener != null) {
                        "At least one media listener is required"
                    }
                    pendingStart = PendingStart(
                        options,
                        videoListener,
                        audioListener,
                        statusListener,
                    )
                    return
                }
                lifecycleIdle.awaitUninterruptibly()
            }
            check(state != CaptureState.RELEASED) { "Capture has been released" }
            if (session != null) return
            require(videoListener != null || audioListener != null) {
                "At least one media listener is required"
            }

            generation += 1
            val newSession = Session(
                generation = generation,
                options = options,
                videoListener = videoListener,
                audioListener = audioListener,
                statusListener = statusListener,
                videoSource = videoListener?.let {
                    videoSourceFactory.create(options.startupTimeoutMs)
                },
                audioSource = audioListener?.let {
                    audioSourceFactory.create(options.startupTimeoutMs)
                },
            )
            session = newSession
            state = CaptureState.PREPARING
            failure = null
            lastVideoMetrics = VideoCaptureMetrics()
            lastAudioMetrics = AudioCaptureMetrics()
            awaitObsoleteStatusNotificationsLocked()
            newSession
        }

        notifyStatus(
            StatusNotification(
                startedSession.generation,
                startedSession.statusListener,
                statusSnapshot(startedSession, CaptureState.PREPARING, null),
            ),
        )

        operationLock.withLock {
            val mayBind = stateLock.withLock {
                if (!isCurrentLocked(startedSession.generation)) false else {
                    startedSession.sdkBindAttempted = true
                    startedSession.bindTimeout = bindTimeoutScheduler.schedule(options.startupTimeoutMs) {
                        fail(startedSession.generation, MediaFailureCatalog.forCode(MediaErrorCode.SDK_NOT_READY).copy(
                            technicalMessage = "Glass SDK did not bind within ${options.startupTimeoutMs}ms",
                        ), bindingDeadline = true)
                    }
                    true
                }
            }
            if (!mayBind) return
            try {
                sdkConnection.bind(sdkListener(startedSession.generation))
            } catch (error: Throwable) {
                fail(
                    startedSession.generation,
                    failure(
                        MediaErrorCode.SDK_NOT_READY,
                        "Glass SDK bind threw before capture startup completed",
                        error,
                    ),
                )
            }
        }
    }

    override fun stop() {
        stopOrRelease(CaptureState.IDLE)
    }

    override fun release() {
        stopOrRelease(CaptureState.RELEASED)
    }

    override fun currentStatus(): CaptureStatus = stateLock.withLock {
        val activeSession = session
        if (activeSession == null) {
            CaptureStatus(state, lastVideoMetrics, lastAudioMetrics, failure)
        } else {
            statusSnapshot(activeSession, state, failure)
        }
    }

    override fun isCleanupInProgress(): Boolean = stateLock.withLock { cleaningUp }

    override fun invalidateCallbacks() {
        stateLock.withLock {
            session?.let {
                generation++
                it.bindTimeout?.cancel()
                it.bindTimeout = null
            }
        }
    }

    private fun sdkListener(callbackGeneration: Long) = object : SdkConnection.Listener {
        override fun onReady() {
            val current = stateLock.withLock {
                session?.takeIf { isCurrentLocked(callbackGeneration) }?.also {
                    it.bindTimeout?.cancel()
                    it.bindTimeout = null
                }
            } ?: return
            dispatchOperation { startSources(current.generation) }
        }

        override fun onFailure(failure: MediaFailure) {
            fail(callbackGeneration, failure)
        }
    }

    private fun startSources(callbackGeneration: Long) {
        operationLock.withLock {
            val activeSession = stateLock.withLock {
                session?.takeIf { isCurrentLocked(callbackGeneration) }
            } ?: return

            activeSession.videoSource?.let { source ->
                val shouldStart = stateLock.withLock {
                    if (!isCurrentLocked(callbackGeneration)) false else {
                        activeSession.videoStartAttempted = true
                        true
                    }
                }
                if (!shouldStart) return
                try {
                    source.start(
                        activeSession.options.video,
                        VideoFrameListener { frame ->
                            forwardVideoFrame(callbackGeneration, activeSession, frame)
                        },
                        videoEvents(callbackGeneration),
                    )
                } catch (error: Throwable) {
                    fail(
                        callbackGeneration,
                        failure(
                            MediaErrorCode.SDK_DISCONNECTED,
                            "Video source start threw before capture startup completed",
                            error,
                        ),
                    )
                    return
                }
            }

            activeSession.audioSource?.let { source ->
                val shouldStart = stateLock.withLock {
                    if (!isCurrentLocked(callbackGeneration)) false else {
                        activeSession.audioStartAttempted = true
                        true
                    }
                }
                if (!shouldStart) return
                try {
                    source.start(
                        activeSession.options.audio,
                        AudioFrameListener { frame ->
                            forwardAudioFrame(callbackGeneration, activeSession, frame)
                        },
                        audioEvents(callbackGeneration),
                    )
                } catch (error: Throwable) {
                    fail(
                        callbackGeneration,
                        failure(
                            MediaErrorCode.AUDIO_START_FAILED,
                            "Audio source start threw before capture startup completed",
                            error,
                        ),
                    )
                    return
                }
            }
        }
    }

    private fun videoEvents(callbackGeneration: Long) = object : VideoSource.Events {
        override val ownsCleanup = true
        override fun onStarted() = Unit

        override fun onFailure(failure: MediaFailure) {
            fail(callbackGeneration, failure)
        }
    }

    private fun audioEvents(callbackGeneration: Long) = object : AudioSource.Events {
        override val ownsCleanup = true
        override fun onStarted() = Unit

        override fun onFailure(failure: MediaFailure) {
            fail(callbackGeneration, failure)
        }
    }

    private fun forwardVideoFrame(
        callbackGeneration: Long,
        activeSession: Session,
        frame: Nv21Frame,
    ) {
        notifyStatus(markFirstFrame(callbackGeneration, video = true))
        if (isCurrent(callbackGeneration)) {
            withinMediaCallback { activeSession.videoListener?.onVideoFrame(frame) }
        }
    }

    private fun forwardAudioFrame(
        callbackGeneration: Long,
        activeSession: Session,
        frame: PcmFrame,
    ) {
        notifyStatus(markFirstFrame(callbackGeneration, video = false))
        if (isCurrent(callbackGeneration)) {
            withinMediaCallback { activeSession.audioListener?.onAudioFrame(frame) }
        }
    }

    private fun markFirstFrame(
        callbackGeneration: Long,
        video: Boolean,
    ): StatusNotification? = stateLock.withLock {
        val activeSession = session?.takeIf { isCurrentLocked(callbackGeneration) } ?: return null
        if (video) {
            activeSession.firstVideoFrameReceived = true
        } else {
            activeSession.firstAudioFrameReceived = true
        }
        if (state == CaptureState.CAPTURING || !allRequestedFramesReceived(activeSession)) {
            return null
        }
        state = CaptureState.CAPTURING
        StatusNotification(
            callbackGeneration,
            activeSession.statusListener,
            statusSnapshot(activeSession, CaptureState.CAPTURING, null),
        )
    }

    private fun allRequestedFramesReceived(activeSession: Session): Boolean =
        (activeSession.videoListener == null || activeSession.firstVideoFrameReceived) &&
            (activeSession.audioListener == null || activeSession.firstAudioFrameReceived)

    private fun stopOrRelease(finalState: CaptureState) {
        val plan = stateLock.withLock {
            while (cleaningUp) {
                if (finalState == CaptureState.RELEASED) {
                    desiredTerminalState = CaptureState.RELEASED
                    pendingStart = null
                }
                if (isInCallback() || deferCleanupWait) return
                lifecycleIdle.awaitUninterruptibly()
            }
            if (state == CaptureState.RELEASED) return
            val activeSession = session
            if (activeSession == null) {
                if (finalState == CaptureState.RELEASED) {
                    generation += 1
                    state = CaptureState.RELEASED
                    failure = null
                    awaitObsoleteStatusNotificationsLocked()
                }
                return
            }

            cleaningUp = true
            desiredTerminalState = finalState
            pendingStart = null
            generation += 1
            state = CaptureState.STOPPING
            session = null
            awaitObsoleteStatusNotificationsLocked()
            CleanupPlan(
                session = activeSession,
                finalState = finalState,
                stoppingNotification = StatusNotification(
                    generation,
                    activeSession.statusListener,
                    statusSnapshot(activeSession, CaptureState.STOPPING, null),
                ),
            )
        }

        notifyStatus(plan.stoppingNotification)
        completeStop(plan)
    }

    private fun completeStop(plan: CleanupPlan) {
        val cleanupErrors = cleanup(plan.session)
        val finalMetrics = metrics(plan.session)
        val completion = stateLock.withLock {
            lastVideoMetrics = finalMetrics.first
            lastAudioMetrics = finalMetrics.second
            state = desiredTerminalState ?: plan.finalState
            desiredTerminalState = null
            failure = null
            cleaningUp = false
            lifecycleIdle.signalAll()
            val restart = pendingStart.takeUnless { state == CaptureState.RELEASED }
            pendingStart = null
            CleanupCompletion(
                notification = StatusNotification(
                    generation,
                    plan.session.statusListener,
                    CaptureStatus(state, lastVideoMetrics, lastAudioMetrics),
                ),
                pendingStart = restart,
            )
        }
        completion.pendingStart?.let { restart ->
            start(
                restart.options,
                restart.videoListener,
                restart.audioListener,
                restart.statusListener,
            )
        }
        notifyStatus(completion.notification)
        throwIfCleanupFailed(cleanupErrors)
    }

    private fun fail(callbackGeneration: Long, mediaFailure: MediaFailure, bindingDeadline: Boolean = false) {
        val plan = stateLock.withLock {
            val activeSession = session?.takeIf { isCurrentLocked(callbackGeneration) } ?: return
            // cancel(false) cannot retract a timeout task already dispatched on another thread.
            if (bindingDeadline && activeSession.bindTimeout == null) return
            if (cleaningUp) return
            cleaningUp = true
            desiredTerminalState = CaptureState.ERROR
            pendingStart = null
            generation += 1
            // Publish the terminal failure before entering vendor SDK cleanup. Glass camera/audio
            // teardown can block while the system service rebuilds. Higher layers must be able to
            // close signaling immediately instead of keeping a dead peer and room slot alive.
            state = CaptureState.ERROR
            failure = mediaFailure
            session = null
            awaitObsoleteStatusNotificationsLocked()
            CleanupPlan(
                activeSession,
                CaptureState.ERROR,
                stoppingNotification = StatusNotification(
                    generation,
                    activeSession.statusListener,
                    statusSnapshot(activeSession, CaptureState.ERROR, mediaFailure),
                ),
            )
        }

        notifyStatus(plan.stoppingNotification)
        dispatchOperation { completeFailure(plan, mediaFailure) }
    }

    private fun completeFailure(plan: CleanupPlan, mediaFailure: MediaFailure) {
        val cleanupErrors = cleanup(plan.session)
        val finalMetrics = metrics(plan.session)
        val finalFailure = aggregate(mediaFailure, cleanupErrors)
        val completion = stateLock.withLock {
            lastVideoMetrics = finalMetrics.first
            lastAudioMetrics = finalMetrics.second
            state = desiredTerminalState ?: CaptureState.ERROR
            desiredTerminalState = null
            failure = finalFailure.takeUnless { state == CaptureState.RELEASED }
            cleaningUp = false
            lifecycleIdle.signalAll()
            val notification = StatusNotification(
                generation,
                plan.session.statusListener,
                CaptureStatus(state, lastVideoMetrics, lastAudioMetrics, failure),
            )
            val restart = pendingStart.takeUnless { state == CaptureState.RELEASED }
            pendingStart = null
            CleanupCompletion(notification, restart)
        }
        completion.pendingStart?.let { start(it.options, it.videoListener, it.audioListener, it.statusListener) }
        notifyStatus(completion.notification)
    }

    private fun cleanup(activeSession: Session): List<Throwable> = operationLock.withLock {
        activeSession.bindTimeout?.cancel()
        activeSession.bindTimeout = null
        val errors = mutableListOf<Throwable>()
        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (error: Throwable) {
                errors += error
            }
        }

        if (activeSession.audioStartAttempted) {
            attempt { activeSession.audioSource?.stop() }
        }
        if (activeSession.videoStartAttempted) {
            attempt { activeSession.videoSource?.stop() }
        }
        if (activeSession.sdkBindAttempted) {
            attempt(sdkConnection::unbind)
        }
        errors
    }

    private fun metrics(activeSession: Session): Pair<VideoCaptureMetrics, AudioCaptureMetrics> =
        safeVideoMetrics(activeSession) to safeAudioMetrics(activeSession)

    private fun statusSnapshot(
        activeSession: Session,
        snapshotState: CaptureState,
        snapshotFailure: MediaFailure?,
    ): CaptureStatus = CaptureStatus(
        state = snapshotState,
        videoMetrics = safeVideoMetrics(activeSession),
        audioMetrics = safeAudioMetrics(activeSession),
        failure = snapshotFailure,
    )

    private fun safeVideoMetrics(activeSession: Session): VideoCaptureMetrics =
        if (!activeSession.videoStartAttempted) {
            lastVideoMetrics
        } else {
            runCatching { activeSession.videoSource?.metrics() }.getOrNull() ?: lastVideoMetrics
        }

    private fun safeAudioMetrics(activeSession: Session): AudioCaptureMetrics =
        if (!activeSession.audioStartAttempted) {
            lastAudioMetrics
        } else {
            runCatching { activeSession.audioSource?.metrics() }.getOrNull() ?: lastAudioMetrics
        }

    private fun aggregate(mediaFailure: MediaFailure, cleanupErrors: List<Throwable>): MediaFailure {
        if (cleanupErrors.isEmpty()) return mediaFailure
        val existingCause = mediaFailure.cause
        if (existingCause != null) {
            cleanupErrors.forEach { cleanupError ->
                if (cleanupError !== existingCause) existingCause.addSuppressed(cleanupError)
            }
            return mediaFailure
        }
        val firstCleanupError = cleanupErrors.first()
        cleanupErrors.drop(1).forEach { cleanupError ->
            if (cleanupError !== firstCleanupError) firstCleanupError.addSuppressed(cleanupError)
        }
        return mediaFailure.copy(cause = firstCleanupError)
    }

    private fun throwIfCleanupFailed(cleanupErrors: List<Throwable>) {
        val firstError = cleanupErrors.firstOrNull() ?: return
        cleanupErrors.drop(1).forEach { cleanupError ->
            if (cleanupError !== firstError) firstError.addSuppressed(cleanupError)
        }
        throw firstError
    }

    private fun notifyStatus(notification: StatusNotification?) {
        val listener = notification?.listener ?: return
        val accepted = stateLock.withLock {
            if (notification.generation != generation) {
                false
            } else {
                inFlightStatusNotifications += 1
                true
            }
        }
        if (!accepted) return
        val previousDepth = statusNotificationDepth.get() ?: 0
        statusNotificationDepth.set(previousDepth + 1)
        try {
            runCatching { listener.onStatusChanged(notification.status) }
        } finally {
            statusNotificationDepth.set(previousDepth)
            stateLock.withLock {
                inFlightStatusNotifications -= 1
                statusNotificationsIdle.signalAll()
            }
        }
    }

    private fun awaitObsoleteStatusNotificationsLocked() {
        val reentrantDepth = statusNotificationDepth.get() ?: 0
        while (inFlightStatusNotifications > reentrantDepth) {
            statusNotificationsIdle.awaitUninterruptibly()
        }
    }

    private fun isCurrent(callbackGeneration: Long): Boolean = stateLock.withLock {
        isCurrentLocked(callbackGeneration)
    }

    private fun isInCallback(): Boolean =
        (statusNotificationDepth.get() ?: 0) > 0 || (mediaNotificationDepth.get() ?: 0) > 0

    private inline fun withinMediaCallback(action: () -> Unit) {
        val previous = mediaNotificationDepth.get() ?: 0
        mediaNotificationDepth.set(previous + 1)
        try { action() } finally { mediaNotificationDepth.set(previous) }
    }

    private fun isCurrentLocked(callbackGeneration: Long): Boolean =
        !cleaningUp && session?.generation == callbackGeneration && generation == callbackGeneration

    private fun failure(
        code: MediaErrorCode,
        technicalMessage: String,
        cause: Throwable,
    ): MediaFailure = MediaFailureCatalog.forCode(code).copy(
        technicalMessage = technicalMessage + ": " +
            (cause.message ?: cause.javaClass.simpleName),
        cause = cause,
    )
}

private object CaptureBindTimeoutScheduler : TimeoutScheduler {
    private val executor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "Glass3-SdkBindTimeout").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    override fun schedule(delayMs: Long, action: () -> Unit): TimeoutScheduler.Cancellable {
        val future = executor.schedule(action, delayMs, TimeUnit.MILLISECONDS)
        return TimeoutScheduler.Cancellable { future.cancel(false) }
    }
}
