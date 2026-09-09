package com.rokid.glass.mediastream.sender.capture

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.rokid.glass.mediastream.capture.AudioFrameListener
import com.rokid.glass.mediastream.capture.CaptureOptions
import com.rokid.glass.mediastream.capture.CaptureState
import com.rokid.glass.mediastream.capture.CaptureStatus
import com.rokid.glass.mediastream.capture.CaptureStatusListener
import com.rokid.glass.mediastream.capture.GlassMediaCapture
import com.rokid.glass.mediastream.capture.MediaErrorCode
import com.rokid.glass.mediastream.capture.MediaFailure
import com.rokid.glass.mediastream.capture.MediaFailureCatalog
import com.rokid.glass.mediastream.capture.PcmFrame
import com.rokid.glass.mediastream.capture.VideoFrameListener
import com.rokid.glass.mediastream.sender.databinding.ActivityMediaCaptureBinding
import com.rokid.glass.mediastream.sender.permission.MediaPermissionCoordinator
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 将底层“已完成清理、但仍抛出聚合异常”的行为限制在页面边界内。
 *
 * [GlassMediaCapture.stop] 和 [GlassMediaCapture.release] 会尽力释放全部资源，然后把清理阶段的
 * 异常重新抛出，方便集成方发现设备或 SDK 问题。页面不能让该异常导致 Activity 崩溃，因此在此
 * 统一转交给稳定错误展示或 Logcat。
 */
internal fun runCaptureCleanup(
    action: () -> Unit,
    onFailure: (Throwable) -> Unit,
): Boolean = try {
    action()
    true
} catch (error: Throwable) {
    onFailure(error)
    false
}

internal fun captureCleanupFailure(error: Throwable): MediaFailure =
    MediaFailureCatalog.forCode(MediaErrorCode.SDK_DISCONNECTED).copy(
        technicalMessage = "Media capture cleanup failed: ${error.message.orEmpty()}",
        cause = error,
    )

/**
 * 验证 Glass3 原始 NV21 视频和 PCM 音频是否真实可用。
 *
 * 除实时格式、帧数和音量外，本页还提供两个端到端检查：把“下一帧”保存为 JPEG，以及把
 * PCM 录成标准 WAV 后在停止采集时播放。SDK 帧不会跨越回调生命周期：只有用户请求截图时
 * 才在回调内复制一次 NV21；PCM 录音也只向有界队列提交副本，所有磁盘操作都在后台完成。
 */
class MediaCaptureActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMediaCaptureBinding
    private lateinit var capture: GlassMediaCapture
    private lateinit var permissionCoordinator: MediaPermissionCoordinator

    private val screenModel = MediaCaptureScreenModel()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val snapshotWriter by lazy { Nv21SnapshotWriter(applicationContext) }
    private val snapshotExecutor = boundedIoExecutor("Glass3-SnapshotIo", capacity = 1)
    private val recordingExecutor = boundedIoExecutor("Glass3-RecordingIo", capacity = 2)
    private val captureGeneration = AtomicLong(0L)
    private val hasVideoFrame = AtomicBoolean(false)
    private val latestDbfs = AtomicReference<Double?>(null)
    private val actualPcmFormat = AtomicReference<PcmFormat?>(null)
    private val snapshotState = AtomicReference(SnapshotState.UNAVAILABLE)
    private val recordingState = AtomicReference(RecordingState.IDLE)
    private val playbackState = AtomicReference(PlaybackState.UNAVAILABLE)
    private val activeRecorder = AtomicReference<WavRecorder?>(null)

    @Volatile
    private var currentStatus = CaptureStatus(CaptureState.IDLE)

    @Volatile
    private var videoSelected = true

    @Volatile
    private var audioSelected = true

    private var mediaPlayer: MediaPlayer? = null
    private var wavFile: File? = null
    private var jpegFile: File? = null
    private var localMessage: String? = null

    @Volatile
    private var destroyed = false

    @Volatile
    private var foreground = false

    private var metricRefreshScheduled = false

    private val metricRefresh = object : Runnable {
        override fun run() {
            metricRefreshScheduled = false
            if (destroyed || !::capture.isInitialized) return
            currentStatus = try {
                capture.currentStatus()
            } catch (error: Throwable) {
                Log.e(LOG_TAG, "读取媒体采集统计失败", error)
                localMessage = "读取实时统计失败，请停止后重试"
                renderCurrentState()
                return
            }
            renderCurrentState()
            if (currentStatus.state == CaptureState.CAPTURING) scheduleMetricRefresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        capture = GlassMediaCapture.create(applicationContext)
        permissionCoordinator = MediaPermissionCoordinator.register(this)
        binding.startButton.setOnClickListener { requestPermissionsAndStart() }
        binding.stopButton.setOnClickListener { stopCapture() }
        binding.saveFrameButton.setOnClickListener { requestSnapshot() }
        binding.startRecordingButton.setOnClickListener { startRecording() }
        binding.stopRecordingButton.setOnClickListener { stopRecording() }
        binding.playRecordingButton.setOnClickListener { playRecording() }
        binding.stopPlaybackButton.setOnClickListener { stopPlayback() }

        renderCurrentState()
        binding.videoEnabled.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        foreground = true
    }

    override fun onStop() {
        foreground = false
        stopCapture()
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        foreground = false
        captureGeneration.incrementAndGet()
        cancelMetricRefresh()
        releasePlayer(nextState = PlaybackState.UNAVAILABLE, render = false)
        activeRecorder.getAndSet(null)?.let(::finalizeRecorderAfterDestroy)
        if (::capture.isInitialized) {
            runCaptureCleanup(
                action = capture::release,
                onFailure = { error ->
                    val failure = captureCleanupFailure(error)
                    Log.e(LOG_TAG, "${failure.code}: ${failure.technicalMessage}", error)
                },
            )
        }
        snapshotExecutor.shutdown()
        recordingExecutor.shutdown()
        super.onDestroy()
    }

    private fun requestPermissionsAndStart() {
        val requestedVideo = binding.videoEnabled.isChecked
        val requestedAudio = binding.audioEnabled.isChecked
        if (!requestedVideo && !requestedAudio) {
            showInputError("请至少选择视频或音频中的一项")
            return
        }
        val accepted = try {
            permissionCoordinator.request(
                videoEnabled = requestedVideo,
                audioEnabled = requestedAudio,
                onGranted = { startCapture(requestedVideo, requestedAudio) },
                onDenied = {
                    postCaptureStatus(
                        generation = captureGeneration.get(),
                        status = CaptureStatus(
                            CaptureState.ERROR,
                            failure = MediaFailureCatalog.forCode(MediaErrorCode.PERMISSION_REQUIRED),
                        ),
                    )
                },
            )
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "请求媒体权限失败", error)
            currentStatus = CaptureStatus(
                CaptureState.ERROR,
                failure = MediaFailureCatalog.forCode(MediaErrorCode.PERMISSION_REQUIRED),
            )
            renderCurrentState()
            return
        }
        if (!accepted) showInputError("权限请求正在处理中，请完成授权后再操作")
    }

    private fun startCapture(requestedVideo: Boolean, requestedAudio: Boolean) {
        // 权限弹窗可能在页面退到后台后才返回；此时不能偷偷重新占用相机和麦克风。
        if (!foreground || destroyed) return
        val generation = captureGeneration.incrementAndGet()
        videoSelected = requestedVideo
        audioSelected = requestedAudio
        hasVideoFrame.set(false)
        latestDbfs.set(null)
        actualPcmFormat.set(null)
        snapshotState.set(SnapshotState.UNAVAILABLE)
        localMessage = null

        val videoListener = VideoFrameListener { frame ->
            if (destroyed || captureGeneration.get() != generation) return@VideoFrameListener
            hasVideoFrame.set(true)
            snapshotState.compareAndSet(SnapshotState.UNAVAILABLE, SnapshotState.READY)

            if (snapshotState.compareAndSet(SnapshotState.PENDING_FRAME, SnapshotState.SAVING)) {
                // copyData() 必须在 SDK 回调返回前调用，后台线程只接触独立副本。
                val ownedData = try {
                    frame.copyData()
                } catch (error: Throwable) {
                    Log.e(LOG_TAG, "复制 NV21 快照帧失败", error)
                    completeSnapshot(generation, null)
                    return@VideoFrameListener
                }
                submitSnapshot(generation, ownedData, frame.width, frame.height)
            }
        }.takeIf { requestedVideo }

        val audioListener = AudioFrameListener { frame ->
            if (destroyed || captureGeneration.get() != generation) return@AudioFrameListener
            latestDbfs.set(AudioLevelMeter.dbfs(frame.data))
            actualPcmFormat.compareAndSet(null, frame.toPcmFormatOrNull())

            val recorder = activeRecorder.get() ?: return@AudioFrameListener
            if (!recorder.append(frame) && activeRecorder.compareAndSet(recorder, null)) {
                recordingState.set(RecordingState.FINALIZING)
                finalizeRecorder(
                    recorder = recorder,
                    failureMessage = "PCM 数据格式发生变化或写入队列已满，本次录音未保存",
                )
            }
        }.takeIf { requestedAudio }

        try {
            capture.start(
                options = CaptureOptions(),
                videoListener = videoListener,
                audioListener = audioListener,
                statusListener = CaptureStatusListener { status ->
                    postCaptureStatus(generation, status)
                },
            )
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "启动原始媒体采集失败", error)
            currentStatus = runCatching { capture.currentStatus() }.getOrElse {
                CaptureStatus(CaptureState.ERROR)
            }
            localMessage = "启动媒体采集失败，请查看 Logcat 中的 $LOG_TAG 日志"
            renderCurrentState()
        }
    }

    private fun stopCapture() {
        cancelMetricRefresh()
        captureGeneration.getAndIncrement()
        snapshotState.updateAndGet { state ->
            if (state == SnapshotState.PENDING_FRAME || state == SnapshotState.SAVING) {
                SnapshotState.UNAVAILABLE
            } else {
                state
            }
        }
        stopPlayback()
        activeRecorder.getAndSet(null)?.let { recorder ->
            recordingState.set(RecordingState.FINALIZING)
            finalizeRecorder(recorder)
        }
        if (!::capture.isInitialized) return

        val cleanupSucceeded = runCaptureCleanup(
            action = capture::stop,
            onFailure = { error ->
                val failure = captureCleanupFailure(error)
                if (destroyed) {
                    Log.e(LOG_TAG, "${failure.code}: ${failure.technicalMessage}", error)
                } else {
                    currentStatus = CaptureStatus(CaptureState.ERROR, failure = failure)
                    renderCurrentState()
                }
            },
        )
        if (cleanupSucceeded && !destroyed) {
            currentStatus = runCatching { capture.currentStatus() }
                .getOrDefault(CaptureStatus(CaptureState.IDLE))
            renderCurrentState()
        }
    }

    private fun requestSnapshot() {
        val changed = snapshotState.compareAndSet(SnapshotState.READY, SnapshotState.PENDING_FRAME) ||
            snapshotState.compareAndSet(SnapshotState.SAVED, SnapshotState.PENDING_FRAME) ||
            snapshotState.compareAndSet(SnapshotState.ERROR, SnapshotState.PENDING_FRAME)
        if (!changed) return
        localMessage = null
        renderCurrentState()
    }

    private fun submitSnapshot(generation: Long, data: ByteArray, width: Int, height: Int) {
        try {
            snapshotExecutor.execute {
                val file = try {
                    snapshotWriter.write(data, width, height)
                } catch (error: Throwable) {
                    Log.e(LOG_TAG, "保存 NV21 快照失败", error)
                    null
                }
                completeSnapshot(generation, file)
            }
        } catch (error: RejectedExecutionException) {
            Log.e(LOG_TAG, "快照写入队列不可用", error)
            completeSnapshot(generation, null)
        }
    }

    private fun completeSnapshot(generation: Long, file: File?) {
        mainHandler.post {
            if (destroyed || captureGeneration.get() != generation) return@post
            if (file == null) {
                snapshotState.set(SnapshotState.ERROR)
                localMessage = "保存画面失败，请稍后重试"
            } else {
                jpegFile = file
                snapshotState.set(SnapshotState.SAVED)
                localMessage = "JPEG 已保存到应用专属目录"
                Log.i(LOG_TAG, "NV21 快照已保存：${file.absolutePath} (${file.length()} bytes)")
            }
            renderCurrentState()
        }
    }

    private fun startRecording() {
        val format = actualPcmFormat.get()
        if (format == null) {
            localMessage = "尚未收到有效 PCM 格式，请等待音频首帧"
            renderCurrentState()
            return
        }
        if (activeRecorder.get() != null) return

        val output = try {
            createWavOutputFile()
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "创建 WAV 文件失败", error)
            recordingState.set(RecordingState.ERROR)
            playbackState.set(PlaybackState.UNAVAILABLE)
            wavFile = null
            localMessage = "无法创建应用专属录音文件"
            renderCurrentState()
            return
        }
        val recorder = WavRecorder()
        try {
            recorder.start(output, format)
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "启动 PCM 录音失败", error)
            runCatching { recorder.close() }
            output.delete()
            recordingState.set(RecordingState.ERROR)
            playbackState.set(PlaybackState.UNAVAILABLE)
            wavFile = null
            localMessage = "启动 PCM 录音失败"
            renderCurrentState()
            return
        }

        if (!activeRecorder.compareAndSet(null, recorder)) {
            runCatching { recorder.close() }
            output.delete()
            return
        }
        wavFile = null
        recordingState.set(RecordingState.RECORDING)
        playbackState.set(PlaybackState.UNAVAILABLE)
        localMessage = null
        Log.i(
            LOG_TAG,
            "PCM 录音已开始：${format.sampleRateHz} Hz / ${format.channelCount} ch / " +
                "${format.bitsPerSample} bit",
        )
        renderCurrentState()
    }

    private fun stopRecording() {
        val recorder = activeRecorder.getAndSet(null) ?: return
        recordingState.set(RecordingState.FINALIZING)
        renderCurrentState()
        finalizeRecorder(recorder)
    }

    private fun finalizeRecorder(
        recorder: WavRecorder,
        failureMessage: String = "WAV 录音未能完整保存，请重试",
    ) {
        try {
            recordingExecutor.execute {
                val output = try {
                    recorder.stop()
                } catch (error: Throwable) {
                    Log.e(LOG_TAG, "完成 WAV 录音失败", error)
                    null
                } finally {
                    runCatching { recorder.close() }
                        .onFailure { Log.e(LOG_TAG, "关闭 WAV 录音器失败", it) }
                }
                mainHandler.post {
                    if (destroyed) return@post
                    if (output == null) {
                        wavFile = null
                        recordingState.set(RecordingState.ERROR)
                        playbackState.set(PlaybackState.UNAVAILABLE)
                        localMessage = failureMessage
                    } else {
                        wavFile = output
                        recordingState.set(RecordingState.READY)
                        playbackState.set(PlaybackState.READY)
                        localMessage = "WAV 已保存，停止采集后可以播放"
                        Log.i(LOG_TAG, "WAV 录音已完成：${output.absolutePath} (${output.length()} bytes)")
                    }
                    renderCurrentState()
                }
            }
        } catch (error: RejectedExecutionException) {
            Log.e(LOG_TAG, "录音完成队列不可用", error)
            runCatching { recorder.close() }
            recordingState.set(RecordingState.ERROR)
            playbackState.set(PlaybackState.UNAVAILABLE)
            wavFile = null
            localMessage = failureMessage
            renderCurrentState()
        }
    }

    private fun finalizeRecorderAfterDestroy(recorder: WavRecorder) {
        try {
            recordingExecutor.execute {
                runCatching { recorder.close() }
                    .onFailure { Log.e(LOG_TAG, "页面退出后关闭 WAV 录音器失败", it) }
            }
        } catch (_: RejectedExecutionException) {
            runCatching { recorder.close() }
        }
    }

    private fun playRecording() {
        val file = wavFile?.takeIf { it.isFile && it.length() >= WAV_HEADER_BYTES }
        if (file == null) {
            playbackState.set(PlaybackState.ERROR)
            localMessage = "没有可播放的完整 WAV 文件"
            renderCurrentState()
            return
        }
        releasePlayer(nextState = PlaybackState.READY, render = false)

        val player = MediaPlayer()
        mediaPlayer = player
        playbackState.set(PlaybackState.PLAYING)
        localMessage = null
        renderCurrentState()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener { prepared ->
                if (destroyed || mediaPlayer !== prepared) {
                    prepared.release()
                } else {
                    prepared.start()
                    Log.i(LOG_TAG, "WAV 播放已开始：${file.absolutePath}")
                }
            }
            player.setOnCompletionListener { completed ->
                if (mediaPlayer === completed) {
                    Log.i(LOG_TAG, "WAV 播放已完成：${file.absolutePath}")
                    releasePlayer(nextState = PlaybackState.READY)
                } else {
                    completed.release()
                }
            }
            player.setOnErrorListener { failed, what, extra ->
                Log.e(LOG_TAG, "WAV 播放失败：what=$what extra=$extra")
                if (mediaPlayer === failed) {
                    localMessage = "WAV 播放失败，请重新录制后再试"
                    releasePlayer(nextState = PlaybackState.ERROR)
                } else {
                    failed.release()
                }
                true
            }
            player.prepareAsync()
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "准备 WAV 播放失败", error)
            localMessage = "无法打开 WAV 录音文件"
            releasePlayer(nextState = PlaybackState.ERROR)
        }
    }

    private fun stopPlayback() {
        if (mediaPlayer != null || playbackState.get() == PlaybackState.PLAYING) {
            releasePlayer(
                nextState = if (wavFile?.isFile == true) PlaybackState.READY else PlaybackState.UNAVAILABLE,
            )
        }
    }

    private fun releasePlayer(nextState: PlaybackState, render: Boolean = true) {
        val player = mediaPlayer
        mediaPlayer = null
        if (player != null) {
            runCatching { player.setOnPreparedListener(null) }
            runCatching { player.setOnCompletionListener(null) }
            runCatching { player.setOnErrorListener(null) }
            runCatching { player.stop() }
            runCatching { player.release() }
        }
        playbackState.set(nextState)
        if (render && !destroyed && ::binding.isInitialized) renderCurrentState()
    }

    private fun postCaptureStatus(generation: Long, status: CaptureStatus) {
        runOnUiThread {
            if (destroyed || captureGeneration.get() != generation) return@runOnUiThread
            currentStatus = status
            renderCurrentState()
            if (status.state == CaptureState.CAPTURING) scheduleMetricRefresh()
        }
    }

    private fun renderCurrentState() {
        if (destroyed || !::binding.isInitialized) return
        currentStatus.failure
            ?.takeIf { it.technicalMessage.isNotBlank() || it.cause != null }
            ?.let { failure ->
                Log.e(LOG_TAG, "${failure.code}: ${failure.technicalMessage}", failure.cause)
            }
        val viewState = screenModel.render(
            MediaCaptureScreenInput(
                status = currentStatus,
                videoSelected = videoSelected,
                audioSelected = audioSelected,
                hasVideoFrame = hasVideoFrame.get(),
                latestDbfs = latestDbfs.get(),
                recordingState = recordingState.get(),
                snapshotState = snapshotState.get(),
                playbackState = playbackState.get(),
                wavPath = wavFile?.absolutePath,
                jpegPath = jpegFile?.absolutePath,
                localMessage = localMessage,
            ),
        )

        binding.statusTitle.text = viewState.title
        binding.actionHint.text = viewState.actionHint
        binding.videoMetrics.text = viewState.videoMetricsText
        binding.audioMetrics.text = viewState.audioMetricsText
        binding.recordingStatus.text = viewState.recordingStatusText
        binding.snapshotStatus.text = viewState.snapshotStatusText
        binding.playbackStatus.text = viewState.playbackStatusText
        binding.wavPath.setOptionalText(viewState.wavPathText?.let { "WAV：$it" })
        binding.jpegPath.setOptionalText(viewState.jpegPathText?.let { "JPEG：$it" })
        binding.localFileMessage.setOptionalText(viewState.localFileMessageText)
        binding.errorStatus.setOptionalText(viewState.errorText)
        binding.startButton.isEnabled = viewState.startEnabled
        binding.stopButton.isEnabled = viewState.stopEnabled
        binding.videoEnabled.isEnabled = viewState.mediaSelectionEnabled
        binding.audioEnabled.isEnabled = viewState.mediaSelectionEnabled
        binding.startRecordingButton.isEnabled = viewState.recordEnabled
        binding.stopRecordingButton.isEnabled = viewState.stopRecordingEnabled
        binding.saveFrameButton.isEnabled = viewState.saveFrameEnabled
        binding.playRecordingButton.isEnabled = viewState.playEnabled
        binding.stopPlaybackButton.isEnabled = viewState.stopPlaybackEnabled
        binding.root.keepScreenOn = viewState.keepScreenOn
        ensureUsableFocus()
    }

    /**
     * 眼镜主要依靠方向键操作。按钮执行后经常立刻变为不可用，如果仍保留在旧焦点上，用户会
     * 感觉后续按键“没有反应”。仅在当前焦点为空或已禁用时，才转移到本阶段最重要的可用操作。
     */
    private fun ensureUsableFocus() {
        val focusedView = currentFocus
        if (focusedView != null && focusedView.isEnabled) return
        sequenceOf(
            binding.stopRecordingButton,
            binding.stopPlaybackButton,
            binding.playRecordingButton,
            binding.stopButton,
            binding.saveFrameButton,
            binding.startRecordingButton,
            binding.startButton,
            binding.videoEnabled,
            binding.audioEnabled,
        ).firstOrNull { it.isEnabled && it.isFocusable }?.requestFocus()
    }

    private fun showInputError(message: String) {
        localMessage = message
        renderCurrentState()
    }

    private fun createWavOutputFile(): File {
        val musicDirectory = requireNotNull(getExternalFilesDir(Environment.DIRECTORY_MUSIC)) {
            "应用专属 Music 目录不可用"
        }
        check(musicDirectory.isDirectory || musicDirectory.mkdirs()) {
            "无法创建应用专属 Music 目录"
        }
        return File.createTempFile(
            "glass3-audio-${System.currentTimeMillis()}-",
            ".wav",
            musicDirectory,
        ).absoluteFile
    }

    private fun PcmFrame.toPcmFormatOrNull(): PcmFormat? = runCatching {
        PcmFormat(sampleRateHz, channelCount, bitsPerSample)
    }.onFailure {
        Log.e(LOG_TAG, "收到不支持的 PCM 格式", it)
    }.getOrNull()

    private fun android.widget.TextView.setOptionalText(value: String?) {
        text = value.orEmpty()
        visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun scheduleMetricRefresh() {
        if (metricRefreshScheduled || destroyed) return
        metricRefreshScheduled = true
        mainHandler.postDelayed(metricRefresh, METRIC_REFRESH_INTERVAL_MS)
    }

    private fun cancelMetricRefresh() {
        metricRefreshScheduled = false
        mainHandler.removeCallbacks(metricRefresh)
    }

    companion object {
        private const val LOG_TAG = "GlassMediaStream"
        private const val METRIC_REFRESH_INTERVAL_MS = 500L
        private const val WAV_HEADER_BYTES = 44L

        private fun boundedIoExecutor(name: String, capacity: Int): ThreadPoolExecutor =
            ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(capacity),
                { task -> Thread(task, name) },
                ThreadPoolExecutor.AbortPolicy(),
            )
    }
}
