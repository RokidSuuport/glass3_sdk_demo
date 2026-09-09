package com.rokid.glass.mediastream.capture.internal.video

import com.rokid.glass.mediastream.capture.MediaErrorCode
import com.rokid.glass.mediastream.capture.MediaFailure
import com.rokid.glass.mediastream.capture.MediaFailureCatalog
import com.rokid.glass.mediastream.capture.VideoCaptureMetrics
import com.rokid.glass.mediastream.capture.VideoCaptureOptions
import com.rokid.glass.mediastream.capture.VideoFrameListener
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal interface VideoSource {
    fun start(options: VideoCaptureOptions, listener: VideoFrameListener, events: Events)
    fun stop()
    fun metrics(): VideoCaptureMetrics

    interface Events {
        fun onStarted()
        fun onFailure(failure: MediaFailure)
    }
}

internal interface TimeoutScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): Cancellable

    fun interface Cancellable {
        fun cancel()
    }
}

internal class GlassNv21VideoSource(
    private val cameraGateway: CameraShareGateway = RokidCameraShareGateway(),
    private val pool: FrameSlotPool = FrameSlotPool(capacity = 2),
    private val startupTimeoutMs: Long = DEFAULT_STARTUP_TIMEOUT_MS,
    private val timeoutScheduler: TimeoutScheduler = ExecutorTimeoutScheduler,
) : VideoSource {
    private class DeferredWork(
        private val executeAction: () -> Unit,
        private val cancelAction: () -> Unit = {},
    ) {
        fun execute() = executeAction()
        fun cancel() = cancelAction()
    }

    private data class StopPlan(
        val pendingWork: List<DeferredWork>,
    )

    private data class FailurePlan(
        val pendingWork: List<DeferredWork>,
        val events: VideoSource.Events?,
    )

    private val stateLock = ReentrantLock(true)
    private val lifecycleIdle = stateLock.newCondition()
    private val notificationsDrained = stateLock.newCondition()
    private val gatewayLifecycleLock = ReentrantLock(true)
    private val notificationDepth = ThreadLocal.withInitial { 0 }
    private val pendingWork = mutableListOf<DeferredWork>()
    private var active = false
    private var stopping = false
    private var generation = 0L
    private var gatewayStartGeneration: Long? = null
    private var opened = false
    private var startedReported = false
    private var listener: VideoFrameListener? = null
    private var events: VideoSource.Events? = null
    private var watchdogEpoch = 0L
    private var watchdog: TimeoutScheduler.Cancellable? = null
    private var inFlightNotifications = 0
    private var metricsWidth = 0
    private var metricsHeight = 0
    private var frameCount = 0L
    private var droppedFrameCount = 0L
    private var bytesReceived = 0L
    private var firstFrameTimestampNs = 0L
    private var lastFrameTimestampNs = 0L

    init {
        require(startupTimeoutMs > 0L) { "startupTimeoutMs must be positive" }
    }

    override fun start(
        options: VideoCaptureOptions,
        listener: VideoFrameListener,
        events: VideoSource.Events,
    ) {
        val callbackGeneration = stateLock.withLock {
            check(!active && !stopping) { "Video source is already started or stopping" }
            active = true
            generation += 1
            val startedGeneration = generation
            gatewayStartGeneration = startedGeneration
            opened = false
            startedReported = false
            this.listener = listener
            this.events = events
            resetMetricsLocked()
            armOpenWatchdogLocked(startedGeneration)
            startedGeneration
        }

        var startError: Throwable? = null
        gatewayLifecycleLock.withLock {
            val mayStart = stateLock.withLock { isCurrentLocked(callbackGeneration) }
            if (mayStart) {
                try {
                    cameraGateway.start(options, createCameraCallback(callbackGeneration))
                } catch (error: Throwable) {
                    startError = error
                }
            }
        }

        val workAfterStart = stateLock.withLock {
            if (gatewayStartGeneration == callbackGeneration) {
                gatewayStartGeneration = null
            }
            if (startError == null && isCurrentLocked(callbackGeneration)) {
                drainPendingWorkLocked()
            } else {
                emptyList()
            }
        }

        val error = startError
        if (error != null) {
            failAndStop(
                callbackGeneration,
                failure(
                    code = MediaErrorCode.SDK_DISCONNECTED,
                    technicalMessage = "CameraShare start failed: " +
                        (error.message ?: error.javaClass.simpleName),
                    cause = error,
                ),
            )
        } else {
            executeDeferredWork(workAfterStart)
        }
    }

    override fun stop() {
        val plan = stateLock.withLock {
            while (stopping) lifecycleIdle.awaitUninterruptibly()
            if (!active) null else {
                stopping = true
                StopPlan(clearActiveStateLocked())
            }
        }

        if (plan != null) {
            plan.pendingWork.forEach(DeferredWork::cancel)
            gatewayLifecycleLock.withLock {
                runCatching(cameraGateway::stop)
            }
            finishStopping()
        }
        awaitExternalNotifications()
    }

    override fun metrics(): VideoCaptureMetrics = stateLock.withLock {
        VideoCaptureMetrics(
            width = metricsWidth,
            height = metricsHeight,
            frameCount = frameCount,
            droppedFrames = droppedFrameCount,
            bytesReceived = bytesReceived,
            fps = calculateFpsLocked(),
        )
    }

    private fun createCameraCallback(callbackGeneration: Long): CameraShareGateway.Callback =
        object : CameraShareGateway.Callback {
            override fun onOpened() {
                handleOpened(callbackGeneration)
            }

            override fun onFrame(data: ByteArray, width: Int, height: Int, timestampNs: Long) {
                handleFrame(callbackGeneration, data, width, height, timestampNs)
            }

            override fun onClosed() {
                deferTerminalCallback(
                    callbackGeneration,
                    failure(
                        code = MediaErrorCode.SDK_DISCONNECTED,
                        technicalMessage = "CameraShare closed unexpectedly",
                    ),
                )
            }

            override fun onError(code: Int, message: String) {
                deferTerminalCallback(
                    callbackGeneration,
                    failure(
                        code = mapSdkError(code, message),
                        technicalMessage = "CameraShare code=$code, message=$message",
                    ),
                )
            }
        }

    private fun handleOpened(callbackGeneration: Long) {
        val immediateWork = stateLock.withLock {
            if (!isCurrentLocked(callbackGeneration) || opened) return
            opened = true
            armFrameWatchdogLocked(callbackGeneration)
            startedWorkLocked(callbackGeneration)
        }
        immediateWork?.execute()
    }

    private fun handleFrame(
        callbackGeneration: Long,
        data: ByteArray,
        width: Int,
        height: Int,
        timestampNs: Long,
    ) {
        val expectedBytes = expectedNv21ByteCount(width, height)
        if (expectedBytes == null || data.size < expectedBytes) {
            reportInvalidFrame(callbackGeneration, data.size, expectedBytes, width, height)
            return
        }

        val immediateStartedWork = stateLock.withLock {
            if (!isCurrentLocked(callbackGeneration)) return
            if (!opened) opened = true
            armFrameWatchdogLocked(callbackGeneration)
            startedWorkLocked(callbackGeneration)
        }
        immediateStartedWork?.execute()
        if (!stateLock.withLock { isCurrentLocked(callbackGeneration) }) return

        val frame = pool.acquireFrame(data, expectedBytes, width, height, timestampNs) ?: run {
            stateLock.withLock {
                if (isCurrentLocked(callbackGeneration)) droppedFrameCount += 1
            }
            return
        }

        val immediateFrameWork = stateLock.withLock {
            if (!isCurrentLocked(callbackGeneration)) {
                frame.close()
                return
            }
            val frameListener = listener ?: run {
                frame.close()
                return
            }
            metricsWidth = width
            metricsHeight = height
            frameCount += 1
            bytesReceived += expectedBytes.toLong()
            if (frameCount == 1L) firstFrameTimestampNs = frame.timestampNs
            lastFrameTimestampNs = frame.timestampNs
            queueOrReturnLocked(
                callbackGeneration,
                DeferredWork(
                    executeAction = {
                        try {
                            deliverIfCurrent(callbackGeneration) {
                                frameListener.onVideoFrame(frame)
                            }
                        } finally {
                            frame.close()
                        }
                    },
                    cancelAction = frame::close,
                ),
            )
        }
        immediateFrameWork?.execute()
    }

    private fun reportInvalidFrame(
        callbackGeneration: Long,
        actualBytes: Int,
        expectedBytes: Int?,
        width: Int,
        height: Int,
    ) {
        val immediateWork = stateLock.withLock {
            if (!isCurrentLocked(callbackGeneration)) return
            val currentEvents = events ?: return
            queueOrReturnLocked(
                callbackGeneration,
                DeferredWork(executeAction = {
                    deliverIfCurrent(callbackGeneration) {
                        currentEvents.onFailure(
                            MediaFailure(
                                MediaErrorCode.VIDEO_FRAME_TIMEOUT,
                                "收到的相机画面数据不完整",
                                "请停止后重试；仍失败时检查相机服务和占用情况",
                                "NV21 length=$actualBytes, expected=${expectedBytes ?: "invalid"}, " +
                                    "size=$width x $height",
                            ),
                        )
                    }
                }),
            )
        }
        immediateWork?.execute()
    }

    private fun deferTerminalCallback(callbackGeneration: Long, failure: MediaFailure) {
        val immediateWork = stateLock.withLock {
            if (!isCurrentLocked(callbackGeneration)) return
            queueOrReturnLocked(
                callbackGeneration,
                DeferredWork(executeAction = { failAndStop(callbackGeneration, failure) }),
            )
        }
        immediateWork?.execute()
    }

    private fun startedWorkLocked(callbackGeneration: Long): DeferredWork? {
        if (startedReported) return null
        val currentEvents = events ?: return null
        startedReported = true
        return queueOrReturnLocked(
            callbackGeneration,
            DeferredWork(executeAction = {
                deliverIfCurrent(callbackGeneration, currentEvents::onStarted)
            }),
        )
    }

    private fun queueOrReturnLocked(
        callbackGeneration: Long,
        work: DeferredWork,
    ): DeferredWork? = if (gatewayStartGeneration == callbackGeneration) {
        pendingWork += work
        null
    } else {
        work
    }

    private fun executeDeferredWork(work: List<DeferredWork>) {
        work.forEachIndexed { index, deferredWork ->
            try {
                deferredWork.execute()
            } catch (error: Throwable) {
                work.drop(index + 1).forEach(DeferredWork::cancel)
                throw error
            }
        }
    }

    private fun onOpenTimeout(callbackGeneration: Long, expectedEpoch: Long) {
        failAndStop(
            callbackGeneration,
            failure(
                code = MediaErrorCode.CAMERA_START_TIMEOUT,
                technicalMessage = "CameraShare did not open within ${startupTimeoutMs}ms",
            ),
            expectedWatchdogEpoch = expectedEpoch,
        )
    }

    private fun onFrameTimeout(callbackGeneration: Long, expectedEpoch: Long) {
        failAndStop(
            callbackGeneration,
            failure(
                code = MediaErrorCode.VIDEO_FRAME_TIMEOUT,
                technicalMessage = "CameraShare produced no valid NV21 frame within ${startupTimeoutMs}ms",
            ),
            expectedWatchdogEpoch = expectedEpoch,
        )
    }

    private fun failAndStop(
        callbackGeneration: Long,
        failure: MediaFailure,
        expectedWatchdogEpoch: Long? = null,
    ) {
        val plan = stateLock.withLock {
            if (!isCurrentLocked(callbackGeneration)) return
            if (expectedWatchdogEpoch != null && watchdogEpoch != expectedWatchdogEpoch) return
            stopping = true
            val currentEvents = events
            val workToCancel = clearActiveStateLocked()
            if (currentEvents != null) inFlightNotifications += 1
            FailurePlan(workToCancel, currentEvents)
        }

        plan.pendingWork.forEach(DeferredWork::cancel)
        gatewayLifecycleLock.withLock {
            runCatching(cameraGateway::stop)
        }
        finishStopping()
        plan.events?.let { currentEvents ->
            deliverReservedNotification {
                currentEvents.onFailure(failure)
            }
        }
    }

    private fun deliverIfCurrent(callbackGeneration: Long, action: () -> Unit): Boolean {
        val reserved = stateLock.withLock {
            if (!isCurrentLocked(callbackGeneration)) false else {
                inFlightNotifications += 1
                true
            }
        }
        if (!reserved) return false
        deliverReservedNotification(action)
        return true
    }

    private fun deliverReservedNotification(action: () -> Unit) {
        val previousDepth = notificationDepth.get() ?: 0
        notificationDepth.set(previousDepth + 1)
        try {
            action()
        } finally {
            notificationDepth.set(previousDepth)
            stateLock.withLock {
                inFlightNotifications -= 1
                if (inFlightNotifications == 0) notificationsDrained.signalAll()
            }
        }
    }

    private fun awaitExternalNotifications() {
        if ((notificationDepth.get() ?: 0) > 0) return
        stateLock.withLock {
            while (inFlightNotifications > 0) notificationsDrained.awaitUninterruptibly()
        }
    }

    private fun finishStopping() {
        stateLock.withLock {
            stopping = false
            lifecycleIdle.signalAll()
        }
    }

    private fun clearActiveStateLocked(): List<DeferredWork> {
        active = false
        generation += 1
        gatewayStartGeneration = null
        invalidateWatchdogLocked()
        listener = null
        events = null
        opened = false
        startedReported = false
        return drainPendingWorkLocked()
    }

    private fun drainPendingWorkLocked(): List<DeferredWork> =
        pendingWork.toList().also { pendingWork.clear() }

    private fun armOpenWatchdogLocked(callbackGeneration: Long) {
        watchdog?.cancel()
        watchdogEpoch += 1
        val expectedEpoch = watchdogEpoch
        watchdog = timeoutScheduler.schedule(startupTimeoutMs) {
            onOpenTimeout(callbackGeneration, expectedEpoch)
        }
    }

    private fun armFrameWatchdogLocked(callbackGeneration: Long) {
        watchdog?.cancel()
        watchdogEpoch += 1
        val expectedEpoch = watchdogEpoch
        watchdog = timeoutScheduler.schedule(startupTimeoutMs) {
            onFrameTimeout(callbackGeneration, expectedEpoch)
        }
    }

    private fun invalidateWatchdogLocked() {
        watchdog?.cancel()
        watchdog = null
        watchdogEpoch += 1
    }

    private fun resetMetricsLocked() {
        metricsWidth = 0
        metricsHeight = 0
        frameCount = 0L
        droppedFrameCount = 0L
        bytesReceived = 0L
        firstFrameTimestampNs = 0L
        lastFrameTimestampNs = 0L
    }

    private fun calculateFpsLocked(): Double {
        if (frameCount < 2L || lastFrameTimestampNs <= firstFrameTimestampNs) return 0.0
        return (frameCount - 1L).toDouble() * NANOS_PER_SECOND.toDouble() /
            (lastFrameTimestampNs - firstFrameTimestampNs).toDouble()
    }

    private fun isCurrentLocked(expectedGeneration: Long): Boolean =
        active && generation == expectedGeneration

    private fun expectedNv21ByteCount(width: Int, height: Int): Int? {
        if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) return null
        val byteCount = try {
            val pixelCount = Math.multiplyExact(width.toLong(), height.toLong())
            Math.multiplyExact(pixelCount, 3L) / 2L
        } catch (_: ArithmeticException) {
            return null
        }
        if (byteCount > Int.MAX_VALUE) return null
        return byteCount.toInt()
    }

    private fun mapSdkError(code: Int, message: String): MediaErrorCode {
        val normalizedMessage = message.lowercase(Locale.ROOT)
        return if (
            code in CAMERA_IN_USE_CODES ||
            CAMERA_IN_USE_MARKERS.any(normalizedMessage::contains)
        ) {
            MediaErrorCode.CAMERA_IN_USE
        } else {
            MediaErrorCode.SDK_DISCONNECTED
        }
    }

    private fun failure(
        code: MediaErrorCode,
        technicalMessage: String,
        cause: Throwable? = null,
    ): MediaFailure = MediaFailureCatalog.forCode(code).copy(
        technicalMessage = technicalMessage,
        cause = cause,
    )

    private companion object {
        const val DEFAULT_STARTUP_TIMEOUT_MS = 12_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        val CAMERA_IN_USE_CODES = setOf(1, 2)
        val CAMERA_IN_USE_MARKERS = listOf(
            "camera in use",
            "camera_in_use",
            "max_cameras_in_use",
            "camera busy",
            "camera occupied",
            "相机占用",
            "相机被占用",
        )
    }
}

private object ExecutorTimeoutScheduler : TimeoutScheduler {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "GlassNv21VideoSourceTimeout").apply { isDaemon = true }
    }

    override fun schedule(delayMs: Long, action: () -> Unit): TimeoutScheduler.Cancellable {
        val future = executor.schedule(action, delayMs, TimeUnit.MILLISECONDS)
        return TimeoutScheduler.Cancellable { future.cancel(false) }
    }
}
