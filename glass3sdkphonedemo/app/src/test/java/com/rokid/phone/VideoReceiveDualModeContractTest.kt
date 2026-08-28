package com.rokid.phone

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoReceiveDualModeContractTest {
    @Test
    fun `video stream page exposes both preview modes and one render fps metric`() {
        val activity = File("src/main/java/com/rokid/phone/VideoReceiveActivity.kt").readText()
        val layout = File("src/main/res/layout/activity_video_receive.xml").readText()

        assertTrue(layout.contains("spinner_preview_mode"))
        assertTrue(layout.contains("h264_surface_view"))
        assertTrue(layout.contains("tv_fps"))
        assertTrue(!layout.contains("tv_request_fps"))
        assertTrue(!layout.contains("tv_receive_fps"))
        assertTrue(!layout.contains("tv_render_fps"))
        assertTrue(activity.contains("PreviewMode.NV21"))
        assertTrue(activity.contains("PreviewMode.H264"))
        assertTrue(activity.contains("NV21(\"NV21\")"))
        assertTrue(activity.contains("H264(\"H.264\")"))
        assertTrue(activity.contains("网络中传输的都是眼镜端编码后的 H.264 压缩码流"))
        assertTrue(activity.contains("MediaCodec 硬解并直接输出到 SurfaceView"))
        assertTrue(!layout.contains("tv_preview_mode_description"))
        assertTrue(activity.contains("onVideoH264Stream"))
        assertTrue(activity.contains("H264SurfaceDecoder"))
    }

    @Test
    fun `preview mode controls sdk h264 to nv21 conversion and restores the default`() {
        val activity = File("src/main/java/com/rokid/phone/VideoReceiveActivity.kt").readText()

        assertTrue(activity.contains("setAutoDecodeH264ToNv21(previewMode == PreviewMode.NV21)"))
        assertTrue(activity.contains("setAutoDecodeH264ToNv21(true)"))
    }

    @Test
    fun `video timeout diagnoses channel state before a controlled recovery`() {
        val activity = File("src/main/java/com/rokid/phone/VideoReceiveActivity.kt").readText()

        assertTrue(activity.contains("handleVideoNoFrames"))
        assertTrue(activity.contains("VideoStreamRecoveryPolicy"))
        assertTrue(activity.contains("视频流无数据，Wi-Fi P2P 已断开"))
        assertTrue(activity.contains("stopRemoteVideoStreamAndAwait"))
        assertTrue(!activity.contains("private fun retryVideoStream"))
    }

    @Test
    fun `a new preview waits for the previous remote stream to stop before accepting frames`() {
        val activity = File("src/main/java/com/rokid/phone/VideoReceiveActivity.kt").readText()

        assertTrue(activity.contains("pendingStreamStopJob"))
        assertTrue(activity.contains("awaitPreviousStreamStopAndDrain()"))
        assertTrue(activity.contains("scheduleCurrentStreamStop()"))
        val startRequest = activity.substringAfter("private suspend fun startStreamRequest")
            .substringBefore("private fun startDurationTimer")
        assertTrue(
            startRequest.indexOf("awaitPreviousStreamStopAndDrain()") <
                startRequest.indexOf("resumeVideoAfterDrain(config)")
        )
    }

    @Test
    fun `preview page displays bluetooth p2p and video states separately`() {
        val activity = File("src/main/java/com/rokid/phone/VideoReceiveActivity.kt").readText()
        val layout = File("src/main/res/layout/activity_video_receive.xml").readText()

        assertTrue(layout.contains("tv_stream_state"))
        assertTrue(activity.contains("蓝牙："))
        assertTrue(activity.contains("P2P："))
        assertTrue(activity.contains("视频："))
    }

    @Test
    fun `resolution configuration uses editable dimensions and preset autofill`() {
        val activity = File("src/main/java/com/rokid/phone/VideoReceiveActivity.kt").readText()
        val layout = File("src/main/res/layout/activity_video_receive.xml").readText()

        assertTrue(activity.contains("binding.spinnerResolutionPreset"))
        assertTrue(activity.contains("fillResolutionInputs"))
        assertTrue(activity.contains("width !in 16..4096 || width % 2 != 0"))
        assertTrue(activity.contains("height !in 16..4096 || height % 2 != 0"))
        assertTrue(layout.contains("spinner_resolution_preset"))
        assertTrue(layout.contains("et_resolution_width"))
        assertTrue(layout.contains("et_resolution_height"))
        assertTrue(!layout.contains("custom_resolution_container"))
    }

    @Test
    fun `both preview modes preserve the actual frame aspect ratio`() {
        val activity = File("src/main/java/com/rokid/phone/VideoReceiveActivity.kt").readText()
        val nv21Renderer = File(
            "src/main/java/com/rokid/phone/glsurface/view/BackgroundGLSurfaceView.java"
        ).readText()
        val h264Decoder = File(
            "src/main/java/com/rokid/phone/video/H264SurfaceDecoder.kt"
        ).readText()
        val layout = File("src/main/res/layout/activity_video_receive.xml").readText()

        assertTrue(nv21Renderer.contains("updateFitCenterVertexBufferIfNeeded"))
        assertTrue(h264Decoder.contains("MediaCodec.INFO_OUTPUT_FORMAT_CHANGED"))
        assertTrue(h264Decoder.contains("crop-left"))
        assertTrue(activity.contains("onH264OutputFormatChanged"))
        assertTrue(activity.contains("applyH264SurfaceFitCenter"))
        assertTrue(activity.contains("FitCenterScaleCalculator.calculateSize"))
        assertTrue(activity.contains("params.gravity = Gravity.CENTER"))
        assertTrue(layout.contains("h264_surface_container"))
        assertTrue(!activity.contains("params.dimensionRatio = ratio"))
    }

    @Test
    fun `resume reuses the validated resolution instead of passing a nullable value`() {
        val activity = File("src/main/java/com/rokid/phone/VideoReceiveActivity.kt").readText()

        assertTrue(
            activity.contains(
                "currentConfig?.resolution ?: parseResolution() ?: DEFAULT_RESOLUTION"
            )
        )
    }
}
