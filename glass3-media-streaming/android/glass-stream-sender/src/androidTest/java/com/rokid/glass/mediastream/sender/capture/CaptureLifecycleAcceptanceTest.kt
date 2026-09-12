package com.rokid.glass.mediastream.sender.capture

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.rokid.glass.mediastream.capture.CaptureState
import com.rokid.glass.mediastream.capture.CaptureStatus
import com.rokid.glass.mediastream.capture.GlassMediaCapture
import com.rokid.glass.mediastream.sender.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bounded Glass3 lifecycle acceptance; uses the real page, SDK and media sources, not test doubles.
 * Keep the device awake and camera/microphone available throughout the run. This test neither
 * changes power settings nor writes JPEG/WAV files. LifecycleAcceptance logs retain state/counts.
 */
@RunWith(AndroidJUnit4::class)
class CaptureLifecycleAcceptanceTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    )

    @Before
    fun requireAwakeGlass() {
        assumeTrue(
            "This acceptance test requires Glass3 hardware",
            Build.MODEL.contains("glass", ignoreCase = true) ||
                Build.PRODUCT.contains("glass", ignoreCase = true),
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        assertTrue("Wake Glass3 before running; a stopped Activity cannot start capture", power.isInteractive)
    }

    @Test
    fun five_start_stop_rounds_honor_media_selection_on_the_same_page() = withPage { scenario, capture ->
        // Catches ignored media selection, stale metrics masquerading as fresh frames, and a stop
        // that never completes. Five rounds are deliberate: this is not a long-running stress test.
        val rounds = listOf(
            MediaSelection(true, false),
            MediaSelection(false, true),
            MediaSelection(true, true),
            MediaSelection(true, false),
            MediaSelection(false, true),
        )
        rounds.forEachIndexed { index, selection ->
            val label = "round=${index + 1}/5 $selection"
            start(scenario, selection)
            awaitLiveFrames(scenario, capture, label, selection)
            click(scenario, R.id.stopButton)
            val idle = awaitIdle(scenario, capture, "$label stopped")
            observeIdle(scenario, capture, "$label settled", idle, 300L)
        }
    }

    @Test
    fun stopping_a_start_during_recovery_does_not_restart_later() = withPage { scenario, capture ->
        // Removing cancellation of a queued/cooling start must fail after the recovery deadline.
        val selection = MediaSelection(true, true)
        start(scenario, selection)
        awaitLiveFrames(scenario, capture, "cancel setup", selection)
        click(scenario, R.id.stopButton)
        awaitIdle(scenario, capture, "cancel setup stopped")
        val idleObservedAt = SystemClock.elapsedRealtime()

        scenario.onActivity { activity ->
            assertResumed(activity)
            assertTrue("Test did not reach the 5-second recovery window in time",
                SystemClock.elapsedRealtime() - idleObservedAt < 1_000L)
            activity.clickEnabled(R.id.startButton)
            assertEquals("Restart should still be cooling down", CaptureState.PREPARING, capture.currentStatus().state)
            activity.clickEnabled(R.id.stopButton)
        }
        val cancelled = awaitIdle(scenario, capture, "cooling start cancelled")
        // Observe beyond the whole recovery interval, not just immediately after stop(). Metrics
        // may retain earlier history, so compare to the settled cancellation snapshot, not zero.
        observeIdle(scenario, capture, "cancelled start must stay cancelled", cancelled, 6_500L)
    }

    @Test
    fun leaving_the_page_stops_capture_and_returning_does_not_restart_it() = withPage { scenario, capture ->
        // Removing onStop cleanup or adding an unintended onStart restart must fail this test.
        val selection = MediaSelection(true, true)
        start(scenario, selection)
        awaitLiveFrames(scenario, capture, "leave setup", selection)

        scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(Lifecycle.State.CREATED, scenario.state)
        val backgroundIdle = awaitIdle(scenario, capture, "page stopped")
        observeIdle(scenario, capture, "background resources settled", backgroundIdle, 1_000L)

        scenario.moveToState(Lifecycle.State.RESUMED)
        observeIdle(scenario, capture, "return must remain idle", backgroundIdle, 1_000L)
        scenario.onActivity(::assertResumed)
    }

    private fun withPage(test: (ActivityScenario<MediaCaptureActivity>, GlassMediaCapture) -> Unit) {
        val scenario = ActivityScenario.launch(MediaCaptureActivity::class.java)
        lateinit var capture: GlassMediaCapture
        try {
            scenario.onActivity { activity ->
                assertResumed(activity)
                // Observation only: drive all operations through actual controls/lifecycle. Keep
                // test access here rather than adding a production-only-for-tests accessor.
                val field = MediaCaptureActivity::class.java.getDeclaredField("capture")
                field.isAccessible = true
                capture = field.get(activity) as GlassMediaCapture
            }
            test(scenario, capture)
            scenario.close()
            val deadline = SystemClock.elapsedRealtime() + STOP_TIMEOUT_MS
            var status = capture.currentStatus()
            while (status.state != CaptureState.RELEASED && SystemClock.elapsedRealtime() < deadline) {
                assertHealthy("page destroy", status)
                Thread.sleep(POLL_MS)
                status = capture.currentStatus()
            }
            Log.i(LOG_TAG, "page destroyed: $status")
            assertEquals("Destroy did not finish resource release: $status", CaptureState.RELEASED, status.state)
        } finally {
            // Also close on assertion failure; never swallow or replace the failing assertion with
            // a success path. No retry/automatic capture restart is performed by this test.
            scenario.close()
        }
    }

    private fun start(scenario: ActivityScenario<MediaCaptureActivity>, selection: MediaSelection) {
        scenario.onActivity { activity ->
            assertResumed(activity)
            activity.select(R.id.videoEnabled, selection.video)
            activity.select(R.id.audioEnabled, selection.audio)
            activity.clickEnabled(R.id.startButton)
        }
    }

    private fun awaitLiveFrames(
        scenario: ActivityScenario<MediaCaptureActivity>,
        capture: GlassMediaCapture,
        label: String,
        selection: MediaSelection,
    ) {
        val baseline = await(scenario, capture, "$label ready", START_TIMEOUT_MS) { sample ->
            val video = sample.status.videoMetrics
            val audio = sample.status.audioMetrics
            sample.status.state == CaptureState.CAPTURING &&
                (!selection.video || (video.width == 1280 && video.height == 720 && video.frameCount > 0L && sample.saveEnabled)) &&
                (!selection.audio || (audio.sampleRateHz == 16_000 && audio.channelCount == 1 && audio.bitsPerSample == 16 &&
                    audio.frameCount > 0L && sample.recordEnabled && !sample.audioText.contains("音量：--")))
        }
        var latest = baseline
        val deadline = SystemClock.elapsedRealtime() + 1_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(POLL_MS)
            latest = read(scenario, capture)
            assertHealthy(label, latest.status)
            assertEquals("$label lost foreground: $latest", Lifecycle.State.RESUMED, latest.lifecycle)
            assertEquals("$label stopped producing: $latest", CaptureState.CAPTURING, latest.status.state)
            if (!selection.video) {
                assertEquals("$label unexpectedly received video", baseline.status.videoMetrics, latest.status.videoMetrics)
                assertTrue("$label must not offer a snapshot", !latest.saveEnabled)
            }
            if (!selection.audio) {
                assertEquals("$label unexpectedly received audio", baseline.status.audioMetrics, latest.status.audioMetrics)
                assertTrue("$label must not offer audio recording", !latest.recordEnabled)
            }
        }
        // Counts are compared within the established session, so either reset-per-session or
        // retained historical counters cannot be mistaken for new media delivery.
        val videoDelta = latest.status.videoMetrics.frameCount - baseline.status.videoMetrics.frameCount
        val audioDelta = latest.status.audioMetrics.frameCount - baseline.status.audioMetrics.frameCount
        if (selection.video) {
            assertTrue("$label no fresh NV21 frames: $latest", videoDelta >= 2L)
            assertTrue(latest.status.videoMetrics.bytesReceived > baseline.status.videoMetrics.bytesReceived)
        }
        if (selection.audio) {
            assertTrue("$label no fresh PCM frames: $latest", audioDelta >= 2L)
            assertTrue(latest.status.audioMetrics.bytesReceived > baseline.status.audioMetrics.bytesReceived)
        }
        Log.i(LOG_TAG, "$label live deltaVideo=$videoDelta deltaAudio=$audioDelta $latest")
    }

    private fun awaitIdle(scenario: ActivityScenario<MediaCaptureActivity>, capture: GlassMediaCapture, label: String): Sample =
        await(scenario, capture, label, STOP_TIMEOUT_MS) {
            it.status.state == CaptureState.IDLE && it.startEnabled && !it.stopEnabled &&
                it.title == "准备原始媒体采集"
        }

    private fun observeIdle(
        scenario: ActivityScenario<MediaCaptureActivity>,
        capture: GlassMediaCapture,
        label: String,
        baseline: Sample,
        durationMs: Long,
    ) {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        var nextLogAt = 0L
        do {
            val sample = read(scenario, capture)
            assertHealthy(label, sample.status)
            assertEquals("$label resumed unexpectedly: $sample", CaptureState.IDLE, sample.status.state)
            assertEquals("$label video changed after IDLE", baseline.status.videoMetrics, sample.status.videoMetrics)
            assertEquals("$label audio changed after IDLE", baseline.status.audioMetrics, sample.status.audioMetrics)
            assertTrue("$label UI did not settle: $sample", sample.startEnabled && !sample.stopEnabled)
            if (SystemClock.elapsedRealtime() >= nextLogAt) {
                Log.i(LOG_TAG, "$label $sample")
                nextLogAt = SystemClock.elapsedRealtime() + 1_000L
            }
            Thread.sleep(POLL_MS)
        } while (SystemClock.elapsedRealtime() < deadline)
    }

    private fun await(
        scenario: ActivityScenario<MediaCaptureActivity>,
        capture: GlassMediaCapture,
        label: String,
        timeoutMs: Long,
        condition: (Sample) -> Boolean,
    ): Sample {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var nextLogAt = 0L
        var lastState: CaptureState? = null
        do {
            val sample = read(scenario, capture)
            assertHealthy(label, sample.status)
            if (sample.status.state != lastState || SystemClock.elapsedRealtime() >= nextLogAt) {
                Log.i(LOG_TAG, "$label $sample")
                lastState = sample.status.state
                nextLogAt = SystemClock.elapsedRealtime() + 1_000L
            }
            if (condition(sample)) return sample
            assertTrue("Timed out after ${timeoutMs}ms: $label $sample", SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(POLL_MS)
        } while (true)
    }

    private fun read(scenario: ActivityScenario<MediaCaptureActivity>, capture: GlassMediaCapture): Sample {
        lateinit var sample: Sample
        scenario.onActivity { activity ->
            sample = Sample(
                status = capture.currentStatus(),
                lifecycle = activity.lifecycle.currentState,
                title = activity.findViewById<TextView>(R.id.statusTitle).text.toString(),
                audioText = activity.findViewById<TextView>(R.id.audioMetrics).text.toString(),
                startEnabled = activity.findViewById<View>(R.id.startButton).isEnabled,
                stopEnabled = activity.findViewById<View>(R.id.stopButton).isEnabled,
                saveEnabled = activity.findViewById<View>(R.id.saveFrameButton).isEnabled,
                recordEnabled = activity.findViewById<View>(R.id.startRecordingButton).isEnabled,
            )
        }
        return sample
    }

    private fun click(scenario: ActivityScenario<MediaCaptureActivity>, id: Int) {
        scenario.onActivity { it.clickEnabled(id) }
    }

    private fun MediaCaptureActivity.clickEnabled(id: Int) {
        val control = findViewById<View>(id)
        assertTrue("Disabled control: ${resources.getResourceEntryName(id)}", control.isEnabled)
        assertTrue("Control did not handle click: ${resources.getResourceEntryName(id)}", control.performClick())
    }

    private fun MediaCaptureActivity.select(id: Int, selected: Boolean) {
        val control = findViewById<CheckBox>(id)
        assertTrue("Disabled media selector: ${resources.getResourceEntryName(id)}", control.isEnabled)
        // CompoundButton can toggle successfully while performClick() returns false when no
        // OnClickListener is installed. Verify the checked state, not that framework return value.
        if (control.isChecked != selected) control.performClick()
        assertEquals(selected, control.isChecked)
    }

    private fun assertResumed(activity: MediaCaptureActivity) {
        assertEquals("Keep Glass3 awake and the capture page in front", Lifecycle.State.RESUMED, activity.lifecycle.currentState)
    }

    private fun assertHealthy(label: String, status: CaptureStatus) {
        assertTrue("$label media failure: $status", status.state != CaptureState.ERROR && status.failure == null)
    }

    private data class MediaSelection(val video: Boolean, val audio: Boolean)

    private data class Sample(
        val status: CaptureStatus,
        val lifecycle: Lifecycle.State,
        val title: String,
        val audioText: String,
        val startEnabled: Boolean,
        val stopEnabled: Boolean,
        val saveEnabled: Boolean,
        val recordEnabled: Boolean,
    )

    private companion object {
        const val LOG_TAG = "LifecycleAcceptance"
        // One five-second recovery interval plus bounded SDK binding and first-media waits.
        const val START_TIMEOUT_MS = 35_000L
        const val STOP_TIMEOUT_MS = 15_000L
        const val POLL_MS = 100L
    }
}
