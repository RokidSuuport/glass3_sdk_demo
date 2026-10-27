package com.rokid.glass.mediastream.capture.internal.audio

import com.rokid.glass.mediastream.capture.AudioCaptureMetrics
import com.rokid.glass.mediastream.capture.AudioCaptureOptions
import com.rokid.glass.mediastream.capture.AudioFrameListener
import com.rokid.glass.mediastream.capture.MediaErrorCode
import com.rokid.glass.mediastream.capture.MediaFailure
import com.rokid.glass.mediastream.capture.MediaFailureCatalog
import com.rokid.glass.mediastream.capture.PcmFrame
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal interface AudioSource {
    fun start(options: AudioCaptureOptions, listener: AudioFrameListener, events: Events)
    fun stop()
    fun metrics(): AudioCaptureMetrics

    interface Events {
        val ownsCleanup: Boolean get() = false
        fun onStarted()
        fun onFailure(failure: MediaFailure)
    }
}

internal interface AudioTimeoutScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): Cancellable

    fun interface Cancellable {
        fun cancel()
    }
}

internal class GlassPcmAudioSource(
    private val audioGateway: AudioServiceGateway = RokidAudioServiceGateway(),
    private val startupTimeoutMs: Long = DEFAULT_STARTUP_TIMEOUT_MS,
    private val timeoutScheduler: AudioTimeoutScheduler = ExecutorAudioTimeoutScheduler,
) : AudioSource {
    private class DeferredWork(
        private val executeAction: () -> Unit,
        private val cancelAction: () -> Unit = {},
    ) {
        fun execute() = executeAction()
        fun cancel() = cancelAction()
    }

    private data class StopPlan(
        val callback: AudioServiceGateway.Callback,
        val pendingWork: List<DeferredWork>,
    )

    private data class FailurePlan(
        val stopPlan: StopPlan,
        val events: AudioSource.Events?,
    )

    private val stateLock = ReentrantLock(true)
    private val lifecycleIdle = stateLock.newCondition()
    private val notificationsDrained = stateLock.newCondition()
    private val gatewayLifecycleLock = ReentrantLock(true)
    private val notificationDepth = ThreadLocal.withInitial { 0 }
    private val pendingWork = mutableListOf<DeferredWork>()
    private var active = false
    private var terminalFailure = false
    private var stopping = false
    private var generation = 0L
    private var gatewayStartGeneration: Long? = null
    private var gatewayCallback: AudioServiceGateway.Callback? = null
    private var startedReported = false
    private var options: AudioCaptureOptions? = null
    private var listener: AudioFrameListener? = null
    private var events: AudioSource.Events? = null
    private var watchdogEpoch = 0L
    private var watchdog: AudioTimeoutScheduler.Cancellable? = null
    private var inFlightNotifications = 0
    private var metricsSampleRateHz = 0
    private var metricsChannelCount = 0
    private var metricsBitsPerSample = 0
    private var frameCount = 0L
    private var bytesReceived = 0L

    init {
        require(startupTimeoutMs > 0L) { "startupTimeoutMs must be positive" }
    }

    override fun start(
        options: AudioCaptureOptions,
        listener: AudioFrameListener,
        events: AudioSource.Events,
    ) {
        val callbackGeneration: Long
        val callback: AudioServiceGateway.Callback
        stateLock.withLock {
            while (stopping) lifecycleIdle.awaitUninterruptibly()
            if (active) return
            require(options.sampleRateHz == REQUIRED_SAMPLE_RATE_HZ) {
                "Rokid PCM sample rate must be $REQUIRED_SAMPLE_RATE_HZ Hz"
            }
            require(options.channelCount == REQUIRED_CHANNEL_COUNT) {
                "Rokid PCM must be mono"
            }
            require(options.bitsPerSample == REQUIRED_BITS_PER_SAMPLE) {
                "Rokid PCM must use $REQUIRED_BITS_PER_SAMPLE-bit samples"
            }
            active = true
            terminalFailure = false
            generation += 1
            callbackGeneration = generation
            callback = createGatewayCallback(callbackGeneration)
            gatewayStartGeneration = callbackGeneration
            gatewayCallback = callback
            startedReported = false
            this.options = options
            this.listener = listener
            this.events = events
            resetMetricsLocked(options)
            armDataWatchdogLocked(callbackGeneration)
        }

        var startAccepted = false
        var startError: Throwable? = null
        gatewayLifecycleLock.withLock {
            val mayStart = stateLock.withLock {
                isCurrentLocked(callbackGeneration) && gatewayCallback === callback
            }
            if (mayStart) {
                try {
                    startAccepted = audioGateway.start(callback)
                } catch (error: Throwable) {
                    startError = error
                }
            }
        }

        val workAfterStart = stateLock.withLock {
            if (gatewayStartGeneration == callbackGeneration) {
                gatewayStartGeneration = null
            }
            if (startError == null && startAccepted && isCurrentLocked(callbackGeneration)) {
                val deferred = drainPendingWorkLocked()
                if (deferred.isEmpty()) {
                    listOfNotNull(startedWorkLocked(callbackGeneration))
                } else {
                    deferred
                }
            } else {
                emptyList()
            }
        }

        val error = startError
        if (error != null || !startAccepted) {
            failAndStop(
                callbackGeneration,
                failure(
                    code = MediaErrorCode.AUDIO_START_FAILED,
                    technicalMessage = if (error == null) {
                        "Glass media service rejected audio recording start"
                    } else {
                        "Glass media service audio start failed: " +
                            (error.message ?: error.javaClass.simpleName)
                    },
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
                clearActiveStateLocked()
            }
        }

        if (plan != null) {
            plan.pendingWork.forEach(DeferredWork::cancel)
            gatewayLifecycleLock.withLock {
                runCatching { audioGateway.stop(plan.callback) }
            }
            finishStopping()
        }
        awaitExternalNotifications()
    }

    override fun metrics(): AudioCaptureMetrics = stateLock.withLock {
        AudioCaptureMetrics(
            sampleRateHz = metricsSampleRateHz,
            channelCount = metricsChannelCount,
            bitsPerSample = metricsBitsPerSample,
            frameCount = frameCount,
            bytesReceived = bytesReceived,
        )
    }

    private fun createGatewayCallback(callbackGeneration: Long): AudioServiceGateway.Callback =
        object : AudioServiceGateway.Callback {
            override fun onAudioStream(buffer: ByteArray, bufferLen: Int, timestampNs: Long) {
                handleAudioStream(callbackGeneration, buffer, bufferLen, timestampNs)
            }

            override fun onDisconnected(cause: Throwable?) {
                deferTerminalCallback(
                    callbackGeneration,
                    failure(
                        code = MediaErrorCode.SDK_DISCONNECTED,
                        technicalMessage = "Glass media service disconnected during audio capture" +
                            cause?.message?.let { ": $it" }.orEmpty(),
                        cause = cause,
                    ),
                )
            }
        }

    private fun handleAudioStream(
        callbackGeneration: Long,
        buffer: ByteArray,
        bufferLen: Int,
        timestampNs: Long,
    ) {
        val validLength = minOf(bufferLen, buffer.size)
        if (validLength <= 0) return

        val immediateStartedWork = stateLock.withLock {
            if (!isCurrentLocked(callbackGeneration)) return
            armDataWatchdogLocked(callbackGeneration)
            startedWorkLocked(callbackGeneration)
        }
        immediateStartedWork?.execute()
        if (!stateLock.withLock { isCurrentLocked(callbackGeneration) }) return

        val bytes = buffer.copyOf(validLength)
        val immediateFrameWork = stateLock.withLock {
            if (!isCurrentLocked(callbackGeneration)) return
            val currentOptions = options ?: return
            val frameListener = listener ?: return
            val frame = PcmFrame(
                data = bytes,
                sampleRateHz = currentOptions.sampleRateHz,
                channelCount = currentOptions.channelCount,
                bitsPerSample = currentOptions.bitsPerSample,
                timestampNs = timestampNs,
            )
            frameCount += 1
            bytesReceived += validLength.toLong()
            queueOrReturnLocked(
                callbackGeneration,
                DeferredWork(executeAction = {
                    deliverIfCurrent(callbackGeneration) {
                        frameListener.onAudioFrame(frame)
                    }
                }),
            )
        }
        immediateFrameWork?.execute()
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

    private fun onDataTimeout(callbackGeneration: Long, expectedEpoch: Long) {
        failAndStop(
            callbackGeneration,
            failure(
                code = MediaErrorCode.AUDIO_DATA_TIMEOUT,
                technicalMessage =
                    "Glass media service produced no valid PCM data within ${startupTimeoutMs}ms",
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
            terminalFailure = true
            val currentEvents = events
            invalidateWatchdogLocked()
            val stopPlan = StopPlan(checkNotNull(gatewayCallback), drainPendingWorkLocked())
            if (currentEvents != null) inFlightNotifications += 1
            FailurePlan(stopPlan, currentEvents)
        }

        plan.stopPlan.pendingWork.forEach(DeferredWork::cancel)
        try {
            plan.events?.let { currentEvents ->
                deliverReservedNotification { currentEvents.onFailure(failure) }
            }
        } finally {
            if (plan.events?.ownsCleanup != true) stop()
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

    private fun clearActiveStateLocked(): StopPlan {
        val callback = checkNotNull(gatewayCallback)
        active = false
        generation += 1
        gatewayStartGeneration = null
        gatewayCallback = null
        invalidateWatchdogLocked()
        options = null
        listener = null
        events = null
        startedReported = false
        return StopPlan(callback, drainPendingWorkLocked())
    }

    private fun drainPendingWorkLocked(): List<DeferredWork> =
        pendingWork.toList().also { pendingWork.clear() }

    private fun armDataWatchdogLocked(callbackGeneration: Long) {
        watchdog?.cancel()
        watchdogEpoch += 1
        val expectedEpoch = watchdogEpoch
        watchdog = timeoutScheduler.schedule(startupTimeoutMs) {
            onDataTimeout(callbackGeneration, expectedEpoch)
        }
    }

    private fun invalidateWatchdogLocked() {
        watchdog?.cancel()
        watchdog = null
        watchdogEpoch += 1
    }

    private fun resetMetricsLocked(options: AudioCaptureOptions) {
        metricsSampleRateHz = options.sampleRateHz
        metricsChannelCount = options.channelCount
        metricsBitsPerSample = options.bitsPerSample
        frameCount = 0L
        bytesReceived = 0L
    }

    private fun isCurrentLocked(expectedGeneration: Long): Boolean =
        active && !terminalFailure && generation == expectedGeneration

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
        const val REQUIRED_SAMPLE_RATE_HZ = 16_000
        const val REQUIRED_CHANNEL_COUNT = 1
        const val REQUIRED_BITS_PER_SAMPLE = 16
    }
}

private object ExecutorAudioTimeoutScheduler : AudioTimeoutScheduler {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "GlassPcmAudioSourceTimeout").apply { isDaemon = true }
    }

    override fun schedule(delayMs: Long, action: () -> Unit): AudioTimeoutScheduler.Cancellable {
        val future = executor.schedule(action, delayMs, TimeUnit.MILLISECONDS)
        return AudioTimeoutScheduler.Cancellable { future.cancel(false) }
    }
}
