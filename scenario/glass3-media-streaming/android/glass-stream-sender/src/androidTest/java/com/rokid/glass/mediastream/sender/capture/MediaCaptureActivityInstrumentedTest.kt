package com.rokid.glass.mediastream.sender.capture

import android.Manifest
import android.os.Build
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.rokid.glass.mediastream.sender.R
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * 只在真实 Glass3 上执行的原始媒体端到端检查。
 *
 * 普通 Android 手机没有 Rokid 媒体服务，因此会跳过本用例。测试仅删除本轮新生成的应用专属
 * JPEG/WAV，不清空客户已有文件。运行时请确保相机和麦克风没有被其他应用占用。
 */
@RunWith(AndroidJUnit4::class)
class MediaCaptureActivityInstrumentedTest {
    private val permissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    )
    private val activityRule = ActivityScenarioRule(MediaCaptureActivity::class.java)

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(permissionRule).around(activityRule)

    private val filesBefore = linkedSetOf<String>()

    @Before
    fun requireGlassAndRememberExistingFiles() {
        assumeTrue(
            "This device test requires Glass3 hardware",
            Build.MODEL.contains("glass", ignoreCase = true) ||
                Build.PRODUCT.contains("glass", ignoreCase = true),
        )
        filesBefore += currentMediaFiles()
    }

    @After
    fun removeOnlyFilesCreatedByThisRun() {
        (currentMediaFiles() - filesBefore).forEach { File(it).delete() }
    }

    @Test
    fun capture_snapshot_record_play_and_release_complete_on_glass() {
        val scenario = activityRule.scenario

        scenario.onActivity { activity ->
            activity.findViewById<android.view.View>(R.id.startButton).performClick()
        }
        awaitActivity(scenario) { activity ->
            activity.textOf(R.id.videoMetrics).contains("1280 × 720") &&
                activity.textOf(R.id.audioMetrics).contains("16000 Hz") &&
                !activity.textOf(R.id.audioMetrics).contains("音量：--") &&
                activity.findViewById<android.view.View>(R.id.saveFrameButton).isEnabled &&
                activity.findViewById<android.view.View>(R.id.startRecordingButton).isEnabled
        }

        scenario.onActivity { activity ->
            activity.findViewById<android.view.View>(R.id.saveFrameButton).performClick()
        }
        awaitActivity(scenario) { it.textOf(R.id.snapshotStatus).contains("已保存") }
        scenario.onActivity { activity ->
            val path = activity.textOf(R.id.jpegPath).removePrefix("JPEG：")
            assertTrue(File(path).isFile)
            assertTrue(File(path).length() > 0L)
        }

        scenario.onActivity { activity ->
            activity.findViewById<android.view.View>(R.id.startRecordingButton).performClick()
        }
        awaitActivity(scenario) { it.textOf(R.id.recordingStatus).contains("录音中") }
        Thread.sleep(RECORDING_DURATION_MS)
        scenario.onActivity { activity ->
            activity.findViewById<android.view.View>(R.id.stopRecordingButton).performClick()
        }
        awaitActivity(scenario) { it.textOf(R.id.recordingStatus).contains("已保存") }
        scenario.onActivity { activity ->
            val path = activity.textOf(R.id.wavPath).removePrefix("WAV：")
            assertTrue(File(path).isFile)
            assertTrue(File(path).length() > WAV_HEADER_BYTES)
            activity.findViewById<android.view.View>(R.id.stopButton).performClick()
        }
        awaitActivity(scenario) { it.textOf(R.id.statusTitle) == "准备原始媒体采集" }

        scenario.onActivity { activity ->
            val play = activity.findViewById<android.view.View>(R.id.playRecordingButton)
            assertTrue(play.isEnabled)
            play.performClick()
            assertTrue(activity.textOf(R.id.playbackStatus).contains("播放中"))
            assertTrue(activity.findViewById<android.view.View>(R.id.stopPlaybackButton).isEnabled)
        }
        Thread.sleep(PLAYBACK_OBSERVATION_MS)
        scenario.onActivity { activity ->
            activity.findViewById<android.view.View>(R.id.stopPlaybackButton).performClick()
        }
        awaitActivity(scenario) { it.textOf(R.id.playbackStatus).contains("可以播放") }

        scenario.close()
    }

    private fun awaitActivity(
        scenario: ActivityScenario<MediaCaptureActivity>,
        condition: (MediaCaptureActivity) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS
        var matched = false
        while (!matched && System.currentTimeMillis() < deadline) {
            scenario.onActivity { matched = condition(it) }
            if (!matched) Thread.sleep(POLL_INTERVAL_MS)
        }
        assertTrue("Timed out waiting for Glass3 media state", matched)
    }

    private fun MediaCaptureActivity.textOf(id: Int): String =
        findViewById<TextView>(id).text.toString()

    private fun currentMediaFiles(): Set<String> {
        val context = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation()
            .targetContext
        return listOfNotNull(
            context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES),
            context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC),
        ).flatMap { directory ->
            directory.listFiles()?.filter(File::isFile).orEmpty()
        }.mapTo(linkedSetOf()) { it.absolutePath }
    }

    private companion object {
        const val WAIT_TIMEOUT_MS = 15_000L
        const val POLL_INTERVAL_MS = 100L
        const val RECORDING_DURATION_MS = 1_500L
        const val PLAYBACK_OBSERVATION_MS = 300L
        const val WAV_HEADER_BYTES = 44L
    }
}
