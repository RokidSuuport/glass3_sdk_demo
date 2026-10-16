package com.rokid.glass.camera

import com.rokid.glass.utils.demoString

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.rokid.glass.base.GlassKeyEvent
import com.rokid.glass.utils.FileSizeUtil
import com.rokid.glass.utils.Nv21VideoRecorder
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.camera.CameraShareHelper
import com.rokid.security.glass3.sdk.base.data.media.CameraShareConfig
import com.rokid.glesse.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mix 录制模式 Fragment
 * Fragment for Mix recording mode.
 * 相机+屏幕叠加录制 MP4
 * Record camera footage overlaid with screen content as MP4.
 */
class MixRecordFragment : Fragment(), CameraShareGestureHandler {

    companion object {
        private const val TAG = "MixRecordFragment"

        /** 连续多少帧尺寸与当前编码器不符就切换编码器。 */
        private const val MISMATCH_FRAMES_BEFORE_SWITCH = 3

        /** 两次分段切换的最小间隔，避免尺寸抖动导致反复分段。 */
        private const val MIN_SWITCH_INTERVAL_MS = 1_000L

        /**
         * AR Mix 导出常用预览参数（与 [Nv21ExportFragment] 默认 1080P 竖屏区分，Mix 走 GPU 叠加管线）。
         * Common AR Mix export preview parameters; unlike the default portrait 1080P in [Nv21ExportFragment], Mix uses the GPU overlay pipeline.
         */
        private val DEFAULT_MIX_CONFIG = CameraShareConfig(
            previewWidth = 720,
            previewHeight = 1280,
            previewTargetFps = 15,
            enableVideoStabilization = false,
            zoomLevel = 1,
        )

        fun newInstance(): MixRecordFragment {
            return MixRecordFragment()
        }
    }

    private lateinit var recordPanel: LinearLayout
    private lateinit var tvRecordTime: TextView
    private lateinit var tvRecordInfo: TextView
    private lateinit var tvOverlayTime: TextView
    private lateinit var btnStopRecord: Button

    private var nv21Helper: CameraShareHelper? = null
    private var nv21Recorder: Nv21VideoRecorder? = null
    private var stopping = false
    private var segmentIndex = 0
    private var mismatchWarned = false
    private var mismatchFrames = 0
    private var lastSwitchAtMs = 0L

    /** 一段录制的信息，结束后用于把所有分片都列出来。 */
    private data class SegmentInfo(
        val index: Int,
        val path: String,
        val width: Int,
        val height: Int,
        val frames: Long,
        val sizeBytes: Long,
        val success: Boolean,
        val reason: String?,
    )

    private val segments = mutableListOf<SegmentInfo>()
    private var recordStartTime = 0L
    private var frameCount = 0L
    private var lastFpsTime = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val overlayTimeUpdater = object : Runnable {
        override fun run() {
            tvOverlayTime.text = timeFormat.format(Date())
            mainHandler.postDelayed(this, 1000)
        }
    }

    private val recordTimeUpdater = object : Runnable {
        override fun run() {
            val elapsed = (System.currentTimeMillis() - recordStartTime) / 1000
            val min = elapsed / 60
            val sec = elapsed % 60
            tvRecordTime.text = "%02d:%02d".format(min, sec)
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_mix_record, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recordPanel = view.findViewById(R.id.record_panel)
        tvRecordTime = view.findViewById(R.id.tv_record_time)
        tvRecordInfo = view.findViewById(R.id.tv_record_info)
        tvOverlayTime = view.findViewById(R.id.tv_overlay_time)
        btnStopRecord = view.findViewById(R.id.btn_stop_record)
        btnStopRecord.setOnClickListener {
            stopMixMode()
            btnStopRecord.isEnabled = false
            btnStopRecord.text = demoString(R.string.demo_stopped)
        }

        tvOverlayTime.text = timeFormat.format(Date())
        mainHandler.post(overlayTimeUpdater)

        startMixMode()
    }

    override fun onPause() {
        releaseMixResources("onPause")
        super.onPause()
    }

    override fun onDestroyView() {
        mainHandler.removeCallbacks(overlayTimeUpdater)
        mainHandler.removeCallbacks(recordTimeUpdater)
        releaseMixResources("onDestroyView")
        super.onDestroyView()
    }

    /**
     * 与 [Nv21ExportFragment.releaseNv21Resources] 对齐：Activity 退后台或 Fragment 销毁时释放 NV21/Mix 会话，
     * Match [Nv21ExportFragment.releaseNv21Resources]: release the NV21/Mix session when the Activity goes to the background or the Fragment is destroyed,
     * 避免相机与编码器在后台仍占用。
     * so the camera and encoder are not kept occupied in the background.
     */
    private fun releaseMixResources(reason: String) {
        mainHandler.removeCallbacks(recordTimeUpdater)
        val helper = nv21Helper
        if (helper == null && nv21Recorder == null) return
        Log.d(TAG, "$reason: stopping mix record and releasing NV21 export")
        stopMixMode(demoString(R.string.demo_mix_record_reason_page_left))
    }

    private fun startMixMode() {
        if (!GlassSdk.isReady()) {
            Log.e(TAG, "GlassSdk not ready for mix mode")
            return
        }
        if (nv21Helper?.isNv21Active() == true) {
            Log.w(TAG, "startMixMode: already active, skip")
            return
        }

        val helper = CameraShareHelper()
        nv21Helper = helper
        helper.initNv21ExportWithConfig(
            enableMix = true,
            config = DEFAULT_MIX_CONFIG,
            callback = object : CameraShareHelper.Nv21Callback {
                override fun onCameraOpened(width: Int, height: Int) {
                    Log.d(TAG, "Mix camera opened: ${width}x${height}")
                    stopping = false
                    segmentIndex = 0
                    segments.clear()
                    val path = startSegment(width, height)
                    recordStartTime = System.currentTimeMillis()
                    activity?.runOnUiThread {
                        tvRecordInfo.text = demoString(R.string.demo_recording_x_nfile, width, height, path ?: "")
                        btnStopRecord.isEnabled = true
                        btnStopRecord.requestFocus()
                        mainHandler.post(recordTimeUpdater)
                    }
                }

                override fun onNv21Frame(nv21: ByteArray, width: Int, height: Int, timestamp: Long) {
                    val recorder = nv21Recorder ?: return
                    if (recorder.width != width || recorder.height != height) {
                        // 尺寸不符的帧不能喂给当前编码器（那正是画面被拉伸的原因）；
                        // Frames of another size must never reach the current encoder, which is what stretches the picture.
                        // 如果连续多帧都是新尺寸，就按帧本身切换编码器，不只依赖 SDK 的分辨率回调。
                        // If several consecutive frames arrive at the new size, switch encoders based on the frames themselves.
                        if (!mismatchWarned) {
                            mismatchWarned = true
                            Log.w(TAG, "Frame ${width}x$height != encoder ${recorder.width}x${recorder.height}, waiting to switch")
                        }
                        mismatchFrames++
                        if (mismatchFrames >= MISMATCH_FRAMES_BEFORE_SWITCH) {
                            mismatchFrames = 0
                            val now = System.currentTimeMillis()
                            if (now - lastSwitchAtMs >= MIN_SWITCH_INTERVAL_MS) {
                                lastSwitchAtMs = now
                                Log.w(TAG, "Frame size stayed ${width}x$height, switching encoder")
                                mainHandler.post { switchSegment(width, height, -1) }
                            }
                        }
                        return
                    }
                    mismatchFrames = 0
                    recorder.encodeFrame(nv21)
                    updateFps(width, height)
                }

                /**
                 * 导出分辨率变化：一个编码器只能编码一种分辨率，必须换新编码器并按新分辨率另起分段，
                 * Export resolution changed: one encoder only encodes one resolution, so a new encoder and a new
                 * 否则继续用旧编码器编新尺寸帧会把画面拉伸/错行。
                 * segment are required; otherwise the picture is stretched or skewed.
                 */
                override fun onNv21ExportResolutionChanged(width: Int, height: Int, appliedPreviewFps: Int) {
                    Log.d(TAG, "Mix export resolution changed: ${width}x$height, fps=$appliedPreviewFps")
                    mainHandler.post { switchSegment(width, height, appliedPreviewFps) }
                }

                override fun onCameraClosed() {
                    Log.d(TAG, "Mix camera closed")
                    // 录制过程中相机被关闭（例如被系统录像或其它页面抢占）时主动收尾，避免留下半截或空文件。
                    // Stop the session when the camera disappears mid-recording (taken over by the system recorder or another page).
                    if (nv21Recorder != null && !stopping) {
                        mainHandler.post {
                            stopMixMode(demoString(R.string.demo_mix_record_reason_camera_closed))
                        }
                    }
                }

                override fun onError(code: Int, msg: String) {
                    Log.e(TAG, "Mix error: code=$code, msg=$msg")
                    if (nv21Recorder != null && !stopping) {
                        mainHandler.post { stopMixMode(msg) }
                    } else {
                        activity?.runOnUiThread {
                            tvRecordInfo.text = demoString(R.string.demo_error, code, msg)
                            btnStopRecord.isEnabled = false
                        }
                    }
                }
            },
        )
    }

    /**
     * 停止混合录制。
     * Stop mix recording.
     *
     * 顺序：先让录制器把编码器收尾（写 EOS、drain、muxer.stop），拿到结果后再释放 NV21 导出；
     * Order: finalise the encoder first (EOS, drain, muxer.stop), then release the NV21 export.
     * 结果里没有有效帧时，录制器已删除空文件，这里按失败提示，不再显示「已保存」。
     * When the result has no valid frames the recorder has deleted the empty file and a failure is shown.
     */
    private fun stopMixMode(failureReason: String? = null, interruptedSeconds: Long? = null) {
        if (stopping) return
        stopping = true
        mainHandler.removeCallbacks(recordTimeUpdater)
        val recorder = nv21Recorder
        val helper = nv21Helper
        nv21Recorder = null
        nv21Helper = null
        btnStopRecord.isEnabled = false
        if (recorder == null) {
            releaseNv21Helper(helper)
            return
        }
        val finishedIndex = segmentIndex
        val finishedWidth = recorder.width
        val finishedHeight = recorder.height
        recorder.stop { result ->
            mainHandler.post {
                finishMixRecord(
                    finishedIndex,
                    finishedWidth,
                    finishedHeight,
                    result,
                    failureReason,
                    interruptedSeconds,
                    helper,
                )
            }
        }
    }

    /**
     * 用指定分辨率新建一段录制（分辨率变化时必须换新编码器）。
     * Start a new recording segment at the given resolution; a resolution change always needs a new encoder.
     */
    private fun startSegment(width: Int, height: Int): String? {
        val recorder = Nv21VideoRecorder(requireContext(), width, height, segmentIndex = ++segmentIndex)
        // 首帧超时：启动后一直取不到画面就终止本次录制，避免留下 0 帧空文件。
        // First-frame timeout: abort when no frame arrives so no zero-frame file is kept.
        recorder.onFirstFrameTimeout = { path ->
            Log.w(TAG, "Mix record first-frame timeout: $path")
            mainHandler.post {
                stopMixMode(demoString(R.string.demo_mix_record_reason_first_frame_timeout))
            }
        }
        // 录制中途断流（例如相机被系统录像占用，帧不再送达）时终止本次录制并如实提示。
        // Stop and report honestly when frames stop mid-recording, e.g. the camera was taken by the system recorder.
        recorder.onFrameStall = { idleMs ->
            val seconds = (idleMs / 1000L).coerceAtLeast(1L)
            Log.w(TAG, "Mix record frame stall: ${idleMs}ms")
            mainHandler.post { stopMixMode(interruptedSeconds = seconds) }
        }
        nv21Recorder = recorder
        mismatchWarned = false
        mismatchFrames = 0
        lastSwitchAtMs = 0L
        val path = recorder.start()
        Log.d(TAG, "Segment $segmentIndex started: $path (${width}x$height)")
        return path
    }

    /**
     * 分辨率切换：收尾旧分段（旧编码器）并按新分辨率开始新分段。
     * Resolution switch: finalise the previous segment (old encoder) and start a new one at the new resolution.
     */
    private fun switchSegment(width: Int, height: Int, appliedPreviewFps: Int) {
        if (stopping) return
        val current = nv21Recorder
        if (current != null && current.width == width && current.height == height) {
            Log.d(TAG, "Resolution change ignored, encoder already ${width}x$height")
            return
        }
        Log.w(
            TAG,
            "Switch encoder ${current?.width}x${current?.height} -> ${width}x$height (fps=$appliedPreviewFps)"
        )
        val finishedIndex = segmentIndex
        val finishedWidth = current?.width ?: 0
        val finishedHeight = current?.height ?: 0
        nv21Recorder = null
        current?.stop { result ->
            mainHandler.post { reportSegment(finishedIndex, finishedWidth, finishedHeight, result, "resolution switch") }
        }
        startSegment(width, height)
        activity?.runOnUiThread {
            if (isAdded) {
                tvRecordInfo.text = demoString(
                    R.string.demo_mix_record_segment_switched,
                    current?.width ?: 0,
                    current?.height ?: 0,
                    width,
                    height,
                    segmentIndex,
                )
            }
        }
    }

    /**
     * 分段收尾结果（非最终停止时只记录日志）。
     * Result of a non-final segment; only logged because recording continues.
     */
    private fun reportSegment(
        index: Int,
        width: Int,
        height: Int,
        result: Nv21VideoRecorder.Result,
        reason: String,
    ) {
        segments.add(
            SegmentInfo(
                index = index,
                path = result.path,
                width = width,
                height = height,
                frames = result.framesWritten,
                sizeBytes = result.sizeBytes,
                success = result.success,
                reason = result.reason,
            )
        )
        Log.d(
            TAG,
            "Segment #$index finished ($reason): path=${result.path} ${width}x$height success=${result.success}" +
                " frames=${result.framesWritten}/${result.framesSubmitted} skipped=${result.framesSkipped}" +
                " size=${result.sizeBytes} durationMs=${result.durationMs} reason=${result.reason}"
        )
    }

    /**
     * 录制收尾：释放 NV21 导出并按结果更新界面。
     * Finish a recording: release the NV21 export and report the outcome on screen.
     */
    private fun finishMixRecord(
        index: Int,
        width: Int,
        height: Int,
        result: Nv21VideoRecorder.Result,
        failureReason: String?,
        interruptedSeconds: Long?,
        helper: CameraShareHelper?,
    ) {
        releaseNv21Helper(helper)
        segments.add(
            SegmentInfo(
                index = index,
                path = result.path,
                width = width,
                height = height,
                frames = result.framesWritten,
                sizeBytes = result.sizeBytes,
                success = result.success,
                reason = result.reason,
            )
        )
        val reason = failureReason ?: demoString(R.string.demo_mix_record_reason_no_frames)
        val sizeText = FileSizeUtil.formatFileSize(result.sizeBytes.toDouble())
        val text = when {
            !result.success -> demoString(R.string.demo_mix_record_failed, reason)
            // 多段时把所有分片都列出来：总览一行 + 每段一行（序号、文件名、分辨率、帧数、大小）。
            // With several segments every part is listed: one summary line plus one line per segment.
            segments.size > 1 -> buildString {
                if (interruptedSeconds != null) {
                    append(demoString(R.string.demo_mix_record_interrupted, interruptedSeconds)).append('\n')
                }
                val totalBytes = segments.sumOf { it.sizeBytes }
                val totalFrames = segments.sumOf { it.frames }
                append(
                    demoString(
                        R.string.demo_mix_record_saved_multi,
                        segments.size,
                        totalFrames,
                        FileSizeUtil.formatFileSize(totalBytes.toDouble()),
                    )
                )
                segments.forEach { info ->
                    append('\n').append(
                        demoString(
                            R.string.demo_mix_record_segment_line,
                            info.index,
                            File(info.path).name,
                            info.width,
                            info.height,
                            info.frames,
                            FileSizeUtil.formatFileSize(info.sizeBytes.toDouble()),
                        )
                    )
                    if (!info.success) append(' ').append(demoString(R.string.demo_mix_record_segment_invalid))
                }
            }
            interruptedSeconds != null ->
                demoString(R.string.demo_mix_record_stopped_by_stall, interruptedSeconds, result.path)
            else -> demoString(R.string.demo_mix_record_saved, result.path, result.framesWritten, sizeText)
        }
        Log.d(
            TAG,
            "Mix record finished: success=${result.success} frames=${result.framesWritten}/${result.framesSubmitted}" +
                " skipped=${result.framesSkipped} size=${result.sizeBytes} durationMs=${result.durationMs}" +
                " segments=${segments.size} reason=${result.reason}"
        )
        Log.d(
            TAG,
            "Mix record segments: " + segments.joinToString(" | ") {
                "#${it.index} ${it.path} ${it.width}x${it.height} frames=${it.frames} size=${it.sizeBytes} success=${it.success}"
            }
        )
        activity?.runOnUiThread {
            if (isAdded) tvRecordInfo.text = text
        }
    }

    private fun releaseNv21Helper(helper: CameraShareHelper?) {
        if (helper == null || !helper.isNv21Active()) return
        try {
            helper.releaseNv21Export()
            Log.d(TAG, "stopMixMode: NV21 export released after encoder stop")
        } catch (e: Exception) {
            Log.e(TAG, "stopMixMode: releaseNv21Export failed: ${e.message}", e)
        }
    }

    override fun handleGlassKeyEvent(keyEvent: Int): Boolean {
        if (keyEvent != GlassKeyEvent.KEYCODE_CLICK || !btnStopRecord.isEnabled) return false
        btnStopRecord.performClick()
        return true
    }

    private fun updateFps(width: Int, height: Int) {
        frameCount++
        val now = System.currentTimeMillis()
        if (lastFpsTime == 0L) lastFpsTime = now
        val elapsed = now - lastFpsTime
        if (elapsed >= 1000) {
            val fps = frameCount * 1000f / elapsed
            activity?.runOnUiThread {
                tvRecordInfo.text = demoString(R.string.demo_recording_x_nframe_rate_fps, width, height, fps)
            }
            frameCount = 0
            lastFpsTime = now
        }
    }
}
