package com.rokid.glass.mediastream.sender.capture

import com.rokid.glass.mediastream.capture.CaptureState
import com.rokid.glass.mediastream.capture.CaptureStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureCallbackGateTest {
    @Test
    fun stopping_rejects_old_frames_but_delivers_async_idle_to_reenable_start_and_wav_playback() {
        val callbacks = CaptureCallbackGate()
        val model = MediaCaptureScreenModel()
        val session = callbacks.begin()
        var status = CaptureStatus(CaptureState.CAPTURING)
        fun receive(update: CaptureState) {
            if (callbacks.acceptsStatus(session, update)) status = CaptureStatus(update)
        }
        fun screen() = model.render(MediaCaptureScreenInput(
            status = status,
            recordingState = RecordingState.READY,
            playbackState = PlaybackState.READY,
            wavPath = "/app/files/recording.wav",
        ))

        callbacks.stop()
        // stop() has returned, but the device has not completed its cleanup.
        status = CaptureStatus(CaptureState.STOPPING)
        assertFalse(callbacks.acceptsFrame(session))
        assertFalse(screen().startEnabled)
        assertFalse(screen().playEnabled)
        // A CAPTURING callback already queued on the UI thread must not revive it.
        receive(CaptureState.CAPTURING)
        assertFalse(screen().stopEnabled)
        // This is the worker's later terminal notification, not stop's return.
        receive(CaptureState.IDLE)
        assertTrue(screen().startEnabled)
        assertTrue(screen().playEnabled)
        assertFalse(callbacks.acceptsFrame(session))
    }

    @Test
    fun asynchronous_cleanup_failure_remains_visible_after_stop() {
        val callbacks = CaptureCallbackGate()
        val session = callbacks.begin()
        callbacks.stop()
        assertTrue(callbacks.acceptsStatus(session, CaptureState.ERROR))
    }

    @Test
    fun restarting_rejects_the_previous_terminal_status_and_accepts_new_frames() {
        val callbacks = CaptureCallbackGate()
        val previous = callbacks.begin()
        callbacks.stop()
        val current = callbacks.begin()
        assertFalse(callbacks.acceptsStatus(previous, CaptureState.IDLE))
        assertFalse(callbacks.acceptsStatus(previous, CaptureState.ERROR))
        assertFalse(callbacks.acceptsFrame(previous))
        assertTrue(callbacks.acceptsStatus(current, CaptureState.PREPARING))
        assertTrue(callbacks.acceptsFrame(current))
    }

    @Test
    fun destroying_the_page_rejects_every_late_callback() {
        val callbacks = CaptureCallbackGate()
        val session = callbacks.begin()
        callbacks.invalidate()
        assertFalse(callbacks.acceptsFrame(session))
        assertFalse(callbacks.acceptsStatus(session, CaptureState.IDLE))
        assertFalse(callbacks.acceptsStatus(session, CaptureState.ERROR))
        assertFalse(callbacks.acceptsStatus(session, CaptureState.RELEASED))
    }

    @Test
    fun error_during_cleanup_allows_queued_retry_but_never_playback() {
        val screen = MediaCaptureScreenModel().render(MediaCaptureScreenInput(
            status = CaptureStatus(CaptureState.ERROR),
            recordingState = RecordingState.READY,
            playbackState = PlaybackState.READY,
            wavPath = "/app/files/recording.wav",
        ))
        assertTrue(screen.startEnabled)
        assertFalse(screen.playEnabled)
        assertFalse(screen.actionHint.contains("资源已清理"))
    }
}
