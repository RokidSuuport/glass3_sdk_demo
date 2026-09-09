package com.rokid.glass.mediastream.sender.capture

import com.rokid.glass.mediastream.capture.AudioCaptureMetrics
import com.rokid.glass.mediastream.capture.CaptureState
import com.rokid.glass.mediastream.capture.CaptureStatus
import com.rokid.glass.mediastream.capture.MediaErrorCode
import com.rokid.glass.mediastream.capture.MediaFailure
import com.rokid.glass.mediastream.capture.VideoCaptureMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCaptureScreenModelTest {
    private val model = MediaCaptureScreenModel()

    @Test
    fun every_capture_state_has_a_complete_customer_control_policy() {
        data class ExpectedPolicy(
            val title: String,
            val start: Boolean,
            val stop: Boolean,
            val mediaSelection: Boolean,
            val keepScreenOn: Boolean,
        )

        val expected = mapOf(
            CaptureState.IDLE to ExpectedPolicy("准备原始媒体采集", true, false, true, false),
            CaptureState.PREPARING to ExpectedPolicy("正在准备媒体服务", false, true, false, false),
            CaptureState.CAPTURING to ExpectedPolicy("原始媒体采集中", false, true, false, true),
            CaptureState.STOPPING to ExpectedPolicy("正在停止采集", false, false, false, false),
            CaptureState.ERROR to ExpectedPolicy("媒体采集失败", true, false, true, false),
            CaptureState.RELEASED to ExpectedPolicy("页面资源已释放", false, false, false, false),
        )

        expected.forEach { (state, policy) ->
            val viewState = model.render(MediaCaptureScreenInput(CaptureStatus(state)))

            assertEquals("$state title", policy.title, viewState.title)
            assertTrue("$state must have an action hint", viewState.actionHint.isNotBlank())
            assertEquals("$state start", policy.start, viewState.startEnabled)
            assertEquals("$state stop", policy.stop, viewState.stopEnabled)
            assertEquals(
                "$state media selection",
                policy.mediaSelection,
                viewState.mediaSelectionEnabled,
            )
            assertEquals("$state keep screen on", policy.keepScreenOn, viewState.keepScreenOn)
            assertFalse("$state record", viewState.recordEnabled)
            assertFalse("$state stop recording", viewState.stopRecordingEnabled)
            assertFalse("$state save frame", viewState.saveFrameEnabled)
            assertFalse("$state play", viewState.playEnabled)
            assertFalse("$state stop playback", viewState.stopPlaybackEnabled)
        }
    }

    @Test
    fun actual_nv21_and_pcm_metrics_are_shown_while_unknown_metrics_stay_unavailable() {
        val unavailable = model.render(
            MediaCaptureScreenInput(
                status = CaptureStatus(CaptureState.CAPTURING),
                videoSelected = true,
                audioSelected = true,
            ),
        )
        val actual = model.render(
            MediaCaptureScreenInput(
                status = CaptureStatus(
                    state = CaptureState.CAPTURING,
                    videoMetrics = VideoCaptureMetrics(
                        width = 1280,
                        height = 720,
                        frameCount = 31,
                        droppedFrames = 2,
                        bytesReceived = 1_382_400,
                        fps = 14.5,
                    ),
                    audioMetrics = AudioCaptureMetrics(
                        sampleRateHz = 16_000,
                        channelCount = 1,
                        bitsPerSample = 16,
                        frameCount = 80,
                        bytesReceived = 25_600,
                    ),
                ),
                videoSelected = true,
                audioSelected = true,
                hasVideoFrame = true,
                latestDbfs = -6.02,
                snapshotState = SnapshotState.READY,
            ),
        )

        assertTrue(unavailable.videoMetricsText.contains("NV21 视频：--"))
        assertFalse(unavailable.videoMetricsText.contains("0 × 0"))
        assertTrue(unavailable.audioMetricsText.contains("PCM 音频：--"))
        assertFalse(unavailable.audioMetricsText.contains("0 Hz"))

        assertTrue(actual.videoMetricsText.contains("1280 × 720 @ 14.5 fps"))
        assertTrue(actual.videoMetricsText.contains("帧数：31"))
        assertTrue(actual.videoMetricsText.contains("丢帧：2"))
        assertTrue(actual.videoMetricsText.contains("字节：1382400"))
        assertTrue(actual.audioMetricsText.contains("16000 Hz / 1 声道 / 16 bit"))
        assertTrue(actual.audioMetricsText.contains("帧数：80"))
        assertTrue(actual.audioMetricsText.contains("字节：25600"))
    }

    @Test
    fun dbfs_is_formatted_for_live_audio_silence_and_unavailable_audio() {
        val live = model.render(
            MediaCaptureScreenInput(
                status = CaptureStatus(CaptureState.CAPTURING),
                audioSelected = true,
                latestDbfs = -6.0206,
            ),
        )
        val silence = model.render(
            MediaCaptureScreenInput(
                status = CaptureStatus(CaptureState.CAPTURING),
                audioSelected = true,
                latestDbfs = -96.0,
            ),
        )
        val unavailable = model.render(
            MediaCaptureScreenInput(
                status = CaptureStatus(CaptureState.CAPTURING),
                audioSelected = true,
            ),
        )

        assertTrue(live.audioMetricsText.contains("音量：-6.02 dBFS"))
        assertTrue(silence.audioMetricsText.contains("≤ -96.0 dBFS"))
        assertTrue(silence.audioMetricsText.contains("静音"))
        assertTrue(unavailable.audioMetricsText.contains("音量：--"))
        assertFalse(unavailable.audioMetricsText.contains("NaN"))
        assertFalse(unavailable.audioMetricsText.contains("Infinity"))
    }

    @Test
    fun save_frame_requires_a_frame_and_reenables_after_success_or_failure() {
        val beforeFirstFrame = model.render(
            MediaCaptureScreenInput(
                status = CaptureStatus(CaptureState.CAPTURING),
                videoSelected = true,
                snapshotState = SnapshotState.UNAVAILABLE,
            ),
        )
        val ready = model.render(
            MediaCaptureScreenInput(
                status = CaptureStatus(CaptureState.CAPTURING),
                videoSelected = true,
                hasVideoFrame = true,
                snapshotState = SnapshotState.READY,
            ),
        )
        val pending = model.render(
            MediaCaptureScreenInput(
                status = CaptureStatus(CaptureState.CAPTURING),
                videoSelected = true,
                hasVideoFrame = true,
                snapshotState = SnapshotState.PENDING_FRAME,
            ),
        )
        val saving = model.render(
            MediaCaptureScreenInput(
                status = CaptureStatus(CaptureState.CAPTURING),
                videoSelected = true,
                hasVideoFrame = true,
                snapshotState = SnapshotState.SAVING,
            ),
        )
        val saved = model.render(
            MediaCaptureScreenInput(
                status = CaptureStatus(CaptureState.CAPTURING),
                videoSelected = true,
                hasVideoFrame = true,
                snapshotState = SnapshotState.SAVED,
                jpegPath = "/storage/emulated/0/Android/data/app/files/Pictures/frame.jpg",
            ),
        )
        val failed = model.render(
            MediaCaptureScreenInput(
                status = CaptureStatus(CaptureState.CAPTURING),
                videoSelected = true,
                hasVideoFrame = true,
                snapshotState = SnapshotState.ERROR,
                localMessage = "保存画面失败，请检查应用文件目录",
            ),
        )

        assertFalse(beforeFirstFrame.saveFrameEnabled)
        assertTrue(ready.saveFrameEnabled)
        assertFalse(pending.saveFrameEnabled)
        assertFalse(saving.saveFrameEnabled)
        assertTrue(saved.saveFrameEnabled)
        assertTrue(failed.saveFrameEnabled)
        assertTrue(pending.snapshotStatusText.contains("等待下一帧"))
        assertTrue(saving.snapshotStatusText.contains("保存"))
        assertEquals(
            "保存画面失败，请检查应用文件目录",
            failed.localFileMessageText,
        )
        assertNull(failed.errorText)
    }

    @Test
    fun recording_requires_live_pcm_and_prevents_a_second_recording() {
        val metrics = AudioCaptureMetrics(
            sampleRateHz = 16_000,
            channelCount = 1,
            bitsPerSample = 16,
            frameCount = 1,
            bytesReceived = 320,
        )
        val withoutSelection = model.render(
            MediaCaptureScreenInput(
                status = CaptureStatus(CaptureState.CAPTURING, audioMetrics = metrics),
            ),
        )
        val withoutFormat = model.render(
            MediaCaptureScreenInput(
                status = CaptureStatus(CaptureState.CAPTURING),
                audioSelected = true,
            ),
        )
        val ready = model.render(
            MediaCaptureScreenInput(
                status = CaptureStatus(CaptureState.CAPTURING, audioMetrics = metrics),
                audioSelected = true,
                recordingState = RecordingState.IDLE,
            ),
        )
        val recording = model.render(
            MediaCaptureScreenInput(
                status = CaptureStatus(CaptureState.CAPTURING, audioMetrics = metrics),
                audioSelected = true,
                recordingState = RecordingState.RECORDING,
            ),
        )
        val finalizing = model.render(
            MediaCaptureScreenInput(
                status = CaptureStatus(CaptureState.CAPTURING, audioMetrics = metrics),
                audioSelected = true,
                recordingState = RecordingState.FINALIZING,
            ),
        )

        assertFalse(withoutSelection.recordEnabled)
        assertFalse(withoutFormat.recordEnabled)
        assertTrue(ready.recordEnabled)
        assertFalse(ready.stopRecordingEnabled)
        assertFalse(recording.recordEnabled)
        assertTrue(recording.stopRecordingEnabled)
        assertFalse(finalizing.recordEnabled)
        assertFalse(finalizing.stopRecordingEnabled)
        assertTrue(recording.recordingStatusText.contains("录音中"))
        assertTrue(finalizing.recordingStatusText.contains("完成录音"))
    }

    @Test
    fun playback_requires_a_finalized_wav_and_never_overlaps_media_work() {
        val wavPath = "/storage/emulated/0/Android/data/app/files/Music/audio.wav"
        val playable = model.render(
            MediaCaptureScreenInput(
                status = CaptureStatus(CaptureState.IDLE),
                recordingState = RecordingState.READY,
                playbackState = PlaybackState.READY,
                wavPath = wavPath,
            ),
        )
        val capturing = model.render(
            MediaCaptureScreenInput(
                status = CaptureStatus(CaptureState.CAPTURING),
                recordingState = RecordingState.READY,
                playbackState = PlaybackState.READY,
                wavPath = wavPath,
            ),
        )
        val recording = model.render(
            MediaCaptureScreenInput(
                status = CaptureStatus(CaptureState.IDLE),
                recordingState = RecordingState.RECORDING,
                playbackState = PlaybackState.READY,
                wavPath = wavPath,
            ),
        )
        val finalizing = model.render(
            MediaCaptureScreenInput(
                status = CaptureStatus(CaptureState.IDLE),
                recordingState = RecordingState.FINALIZING,
                playbackState = PlaybackState.READY,
                wavPath = wavPath,
            ),
        )
        val savingSnapshot = model.render(
            MediaCaptureScreenInput(
                status = CaptureStatus(CaptureState.IDLE),
                recordingState = RecordingState.READY,
                snapshotState = SnapshotState.SAVING,
                playbackState = PlaybackState.READY,
                wavPath = wavPath,
            ),
        )
        val withoutFile = model.render(
            MediaCaptureScreenInput(
                status = CaptureStatus(CaptureState.IDLE),
                recordingState = RecordingState.READY,
                playbackState = PlaybackState.READY,
            ),
        )
        val playing = model.render(
            MediaCaptureScreenInput(
                status = CaptureStatus(CaptureState.IDLE),
                recordingState = RecordingState.READY,
                playbackState = PlaybackState.PLAYING,
                wavPath = wavPath,
            ),
        )

        assertTrue(playable.playEnabled)
        assertFalse(capturing.playEnabled)
        assertFalse(recording.playEnabled)
        assertFalse(finalizing.playEnabled)
        assertFalse(savingSnapshot.playEnabled)
        assertFalse(withoutFile.playEnabled)
        assertFalse(playing.playEnabled)
        assertTrue(playing.stopPlaybackEnabled)
        assertFalse(playing.startEnabled)
        assertFalse(finalizing.startEnabled)
    }

    @Test
    fun saved_jpeg_and_wav_absolute_paths_are_visible_after_success() {
        val jpegPath = "/storage/emulated/0/Android/data/app/files/Pictures/glass3-frame-1.jpg"
        val wavPath = "/storage/emulated/0/Android/data/app/files/Music/glass3-audio-1.wav"

        val viewState = model.render(
            MediaCaptureScreenInput(
                status = CaptureStatus(CaptureState.IDLE),
                snapshotState = SnapshotState.SAVED,
                recordingState = RecordingState.READY,
                playbackState = PlaybackState.READY,
                jpegPath = jpegPath,
                wavPath = wavPath,
            ),
        )

        assertTrue(viewState.snapshotStatusText.contains("已保存"))
        assertTrue(viewState.recordingStatusText.contains("已保存"))
        assertEquals(jpegPath, viewState.jpegPathText)
        assertEquals(wavPath, viewState.wavPathText)
    }

    @Test
    fun capture_failure_is_redacted_and_kept_separate_from_local_file_failure() {
        val failure = MediaFailure(
            code = MediaErrorCode.CAMERA_IN_USE,
            userMessage = "相机正在被其他应用占用",
            suggestedAction = "关闭其他相机应用后重试",
            technicalMessage = "CameraAccessException: MAX_CAMERAS_IN_USE at vendor.camera.Provider",
            cause = IllegalStateException("customer-secret-camera-detail"),
        )

        val viewState = model.render(
            MediaCaptureScreenInput(
                status = CaptureStatus(CaptureState.ERROR, failure = failure),
                snapshotState = SnapshotState.ERROR,
                localMessage = "保存画面失败，请稍后重试",
            ),
        )
        val errorText = viewState.errorText.orEmpty()

        assertTrue(viewState.startEnabled)
        assertTrue(viewState.mediaSelectionEnabled)
        assertTrue(errorText.contains("CAMERA_IN_USE"))
        assertTrue(errorText.contains("相机正在被其他应用占用"))
        assertTrue(errorText.contains("关闭其他相机应用后重试"))
        assertFalse(errorText.contains("CameraAccessException"))
        assertFalse(errorText.contains("vendor.camera.Provider"))
        assertFalse(errorText.contains("IllegalStateException"))
        assertFalse(errorText.contains("customer-secret-camera-detail"))
        assertFalse(errorText.contains("保存画面失败"))
        assertEquals("保存画面失败，请稍后重试", viewState.localFileMessageText)
    }

    @Test
    fun released_disables_every_operation_and_later_states_do_not_keep_stale_errors() {
        val failed = model.render(
            MediaCaptureScreenInput(
                status = CaptureStatus(
                    state = CaptureState.ERROR,
                    failure = MediaFailure(
                        MediaErrorCode.AUDIO_DATA_TIMEOUT,
                        "没有收到音频数据",
                        "重新开始采集",
                    ),
                ),
            ),
        )
        val released = model.render(
            MediaCaptureScreenInput(
                status = CaptureStatus(CaptureState.RELEASED),
                videoSelected = true,
                audioSelected = true,
                hasVideoFrame = true,
                recordingState = RecordingState.READY,
                snapshotState = SnapshotState.SAVED,
                playbackState = PlaybackState.READY,
                wavPath = "/tmp/old.wav",
                jpegPath = "/tmp/old.jpg",
            ),
        )
        val laterIdle = model.render(MediaCaptureScreenInput(CaptureStatus(CaptureState.IDLE)))

        assertTrue(failed.errorText.orEmpty().contains("AUDIO_DATA_TIMEOUT"))
        assertFalse(released.startEnabled)
        assertFalse(released.stopEnabled)
        assertFalse(released.recordEnabled)
        assertFalse(released.stopRecordingEnabled)
        assertFalse(released.saveFrameEnabled)
        assertFalse(released.playEnabled)
        assertFalse(released.stopPlaybackEnabled)
        assertFalse(released.mediaSelectionEnabled)
        assertFalse(released.keepScreenOn)
        assertNull(released.errorText)
        assertNull(released.localFileMessageText)
        assertNull(laterIdle.errorText)
        assertNull(laterIdle.localFileMessageText)
        assertTrue(laterIdle.startEnabled)
    }
}
