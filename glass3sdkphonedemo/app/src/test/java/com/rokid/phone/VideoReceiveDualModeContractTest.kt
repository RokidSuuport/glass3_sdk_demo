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
        assertTrue(activity.contains("PreviewMode.NV21"))
        assertTrue(activity.contains("PreviewMode.H264"))
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
        assertTrue(activity.contains("R.string.demo_no_video_data_wi_fi_p2p_disconnected"))
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
        assertTrue(activity.contains("R.string.demo_bluetooth_p2p_video"))
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
        assertTrue(!layout.contains("layout_constraintDimensionRatio=\"16:9\""))
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

    @Test
    fun `video preview shows one status line and exposes custom zoom options`() {
        val activity = File("src/main/java/com/rokid/phone/VideoReceiveActivity.kt").readText()
        val layout = File("src/main/res/layout/activity_video_receive.xml").readText()
        val strings = File("src/main/res/values/video_stream_strings.xml").readText()
        val chineseStrings = File("src/main/res/values-zh-rCN/video_stream_strings.xml").readText()

        assertTrue(activity.contains("listOf<Float?>(1f, null, 1.575f, 2f, 3f, 4f, 8f)"))
        assertTrue(activity.contains("listOf<Float?>(1f, null, 2f, 3f, 4f, 8f)"))
        assertTrue(activity.contains("customZoomOptionIndex = 0"))
        assertTrue(activity.contains("add(getString(R.string.video_zoom_custom))"))
        assertTrue(activity.contains("zoomPresets.getOrNull(position - 1)"))
        assertTrue(activity.contains("integerMode && ratio % 1f != 0f"))
        assertTrue(activity.contains("video_preview_status"))
        assertTrue(activity.contains("config.zoomRatio?.let { zoomRatio = it }"))
        assertTrue(activity.contains("config.zoomLevel?.let { zoomLevel = it }"))
        assertTrue(activity.contains("supportsFloatZoom="))
        assertTrue(layout.contains("@+id/et_custom_video_zoom"))
        assertTrue(layout.contains("@+id/sw_zoom_integer_compat"))
        assertTrue(
            layout.indexOf("@+id/sw_zoom_integer_compat") <
                layout.indexOf("@+id/spinner_video_zoom")
        )
        assertTrue(layout.contains("android:singleLine=\"true\""))
        assertTrue(
            layout.indexOf("@+id/spinner_resolution_preset") <
                layout.indexOf("@+id/spinner_video_zoom")
        )
        assertTrue(!layout.contains("@+id/tv_video_actual_resolution"))
        assertTrue(!layout.contains("@+id/tv_video_zoom_capability"))
        assertTrue(!strings.contains("video_zoom_unknown"))
        assertTrue(!strings.contains("video_resolution_aspect_mismatch"))
        assertTrue(strings.contains(">Integer zoom mode<"))
        assertTrue(chineseStrings.contains(">整数倍率模式<"))
    }
}
