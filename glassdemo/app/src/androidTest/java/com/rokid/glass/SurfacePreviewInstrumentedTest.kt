package com.rokid.glass

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rokid.glass.base.GlassKeyEvent
import com.rokid.glass.camera.CameraControlsFragment
import com.rokid.glass.camera.SurfaceShareFragment
import com.rokid.glass.utils.GlassSdkUtils
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.camera.CameraZoomSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Surface regression tests. Both cases first consume real TextureView frames and require
 * awake, unlocked glasses with no competing camera session. It never records or captures a
 * photo, and does not launch Home (which changes persistent device settings). Every hardware
 * wait is bounded to 20 seconds; synthetic query text below tests UI isolation only.
 */
@RunWith(AndroidJUnit4::class)
class SurfacePreviewInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun followControlInEnglish() {
        val previous = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        instrumentation.runOnMainSync {
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags("en"))
        }
        try { followControlIsVisibleAndSurvivesApply() }
        finally {
            instrumentation.runOnMainSync {
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(previous)
            }
        }
    }

    @Test
    fun followControlIsVisibleAndSurvivesApply() {
        withConnectedSurface { scenario ->
            awaitRealFrames(scenario, 1f)
            scenario.onActivity {
                val f = fragment(it)
                click(f, "camera_preview_configure")
                assertTrue(tag(f, "surface_follow_zoom").isShown)
                for (expected in listOf(1.25f, 1.575f, 2f, 4f, 8f)) {
                    click(f, "surface_config_zoom")
                    assertEquals(expected, read(f, "requestedZoom") as Float, 0f)
                    assertFalse(read(f, "requestedFollowShared") as Boolean)
                }
                click(f, "surface_config_zoom")
                assertTrue(read(f, "requestedFollowShared") as Boolean)
                click(f, "surface_config_zoom")
                assertFalse(read(f, "requestedFollowShared") as Boolean)
                assertEquals(1f, read(f, "requestedZoom") as Float, 0f)
                repeat(6) { click(f, "surface_config_zoom") }
                assertTrue(read(f, "requestedFollowShared") as Boolean)
                click(f, "surface_more")
                click(f, "surface_await_zoom")
            }
            await("follow request accepted") {
                var following = false
                scenario.onActivity { following = read(fragment(it), "followingShared") as Boolean }
                following
            }
            scenario.onActivity { click(fragment(it), "surface_apply") }
            awaitRealFrames(scenario, 1f)
            scenario.onActivity {
                val f = fragment(it)
                assertTrue(read(f, "followingShared") as Boolean)
                click(f, "camera_preview_configure")
                assertTrue(tag(f, "surface_follow_zoom").isShown)
            }
            saveScreenshot("surface-follow-visible.png")
            scenario.onActivity { click(fragment(it), "camera_preview_return") }
            for (expected in listOf(1f, 1.25f, 1.575f, 2f, 4f, 8f)) {
                scenario.onActivity { click(fragment(it), "surface_live_zoom") }
                await("live zoom selection $expected") {
                    var selected = false
                    scenario.onActivity {
                        val f = fragment(it)
                        selected = !(read(f, "followingShared") as Boolean) &&
                            read(f, "liveRequestedZoom") as Float == expected
                    }
                    selected
                }
            }
            scenario.onActivity { click(fragment(it), "surface_live_zoom") }
            await("live zoom cycle enters shared mode") {
                var following = false
                scenario.onActivity { following = read(fragment(it), "followingShared") as Boolean }
                following
            }
        }
    }

    /** 只隔离界面状态源；真实预览仍由原会话持有。 / Isolate UI state; the original session owns the real preview. */
    @Test
    fun zoomDisplayTimesOutAndRecoversWithoutShowingConfirmationOnPreview() {
        withConnectedSurface { scenario ->
            awaitRealFrames(scenario, 1f)
            lateinit var original: CameraZoomSession
            val pending = CameraZoomSession()
            fun repaint(fragment: SurfaceShareFragment) {
                for (name in listOf("updateActualZoom", "showLiveStatus")) {
                    SurfaceShareFragment::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(fragment)
                }
            }
            try {
                scenario.onActivity {
                    val fragment = fragment(it)
                    original = session(fragment)!!
                    val text = (read(fragment, "status") as TextView).text.toString()
                    assertFalse(text.contains("已确认") || text.contains("confirmed"))
                    SurfaceShareFragment::class.java.getDeclaredField("zoomSession").apply { isAccessible = true }.set(fragment, pending)
                    repaint(fragment)
                    val loading = (read(fragment, "status") as TextView).text.toString()
                    assertTrue(loading.contains("获取中") || loading.contains("loading"))
                }
                await("UI timeout without a result") {
                    var unavailable = false
                    scenario.onActivity {
                        val text = (read(fragment(it), "status") as TextView).text.toString()
                        unavailable = text.contains("暂不可用") || text.contains("temporarily unavailable")
                    }
                    unavailable
                }
                saveScreenshot("surface-zoom-unavailable.png")
            } finally {
                scenario.onActivity {
                    val fragment = fragment(it)
                    SurfaceShareFragment::class.java.getDeclaredField("zoomSession").apply { isAccessible = true }.set(fragment, original)
                    repaint(fragment)
                }
                pending.close()
            }
            awaitRealFrames(scenario, 1f)
            scenario.onActivity {
                val fragment = fragment(it)
                val text = (read(fragment, "status") as TextView).text.toString()
                assertFalse(text.contains("暂不可用") || text.contains("unavailable") || text.contains("已确认") || text.contains("confirmed"))
                click(fragment, "camera_preview_configure")
                click(fragment, "surface_query_capabilities")
            }
            awaitQuery(scenario, "ready=true")
        }
    }

    /** 英文布局也应保留请求与当前尺寸。 / Verify the same header in English. */
    @Test
    fun previewHeaderInEnglishKeepsAppliedResolution() {
        val previous = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        instrumentation.runOnMainSync {
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags("en"))
        }
        try {
            previewHeaderKeepsAppliedResolutionWhileSettingsAreEdited()
        } finally {
            instrumentation.runOnMainSync {
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(previous)
            }
        }
    }

    /** 顶部显示已应用请求，而不是未提交的编辑。 / Show the applied request, not pending edits. */
    @Test
    fun previewHeaderKeepsAppliedResolutionWhileSettingsAreEdited() {
        withConnectedSurface { scenario ->
            awaitRealFrames(scenario, 1f)
            scenario.onActivity { activity ->
                val fragment = fragment(activity)
                val status = read(fragment, "status") as TextView
                val initial = status.text.toString()
                if (fragment.resources.configuration.locales[0].language == "en") {
                    assertTrue("Actual zoom must use the page locale: $initial", initial.contains("Actual zoom:"))
                }
                assertTrue("Header must identify the request: $initial",
                    initial.contains("请求：1280×720") || initial.contains("Requested: 1280×720"))
                assertTrue("Header must identify the current image: $initial",
                    initial.contains("当前画面：1280×720") || initial.contains("Current image: 1280×720"))
                click(fragment, "camera_preview_configure")
                (read(fragment, "resolutionButton") as Button).performClick()
                assertEquals(1920, read(fragment, "requestedWidth"))
                click(fragment, "camera_preview_return")
            }
            awaitRealFrames(scenario, 1f)
            scenario.onActivity { activity ->
                val text = (read(fragment(activity), "status") as TextView).text.toString()
                assertTrue("Unapplied selection must not replace the active request: $text",
                    text.contains("请求：1280×720") || text.contains("Requested: 1280×720"))
                assertFalse(text.contains("1920×1080"))
            }
            saveScreenshot("surface-requested-resolution-${context.resources.configuration.locales[0].language}.png")
        }
    }

    @Test
    fun queryRemainsReadableAndClosedOrSupersededResultsStayClosed() {
        withConnectedSurface { scenario ->
            // Let the real automatic startup settle, then explicitly stop and drain cleanup.
            // Synthetic query text must not race an SDK startup failure or camera callback.
            awaitRealFrames(scenario, 1f)
            scenario.onActivity { click(fragment(it), "surface_stop") }
            awaitStopped(scenario)
            drainWorker(scenario)
            lateinit var firstUpdate: (String) -> Unit
            scenario.onActivity { activity ->
                val fragment = fragment(activity)
                assertSettingsOnly(fragment)
                firstUpdate = beginQuery(fragment, "UI regression query")
                assertTrue(tag(fragment, "camera_query_overlay").isShown)
                firstUpdate((1..120).joinToString("\n") { "Result line $it" })
                val result = tag(fragment, "camera_query_result") as TextView
                val retainedText = result.text.toString()
                CameraControlsFragment::class.java
                    .getDeclaredMethod("showStatus", String::class.java, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                    .invoke(fragment, "Unrelated camera frame status", false)
                assertEquals("Frame/status callbacks must not replace a query result", retainedText,
                    result.text.toString())
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val fragment = fragment(activity)
                assertTrue(fragment.handleGlassKeyEvent(GlassKeyEvent.KEYCODE_FRONT))
            }
            await("long query must scroll using the temple gesture") {
                var scrolled = false
                scenario.onActivity {
                    scrolled = queryScroll(fragment(it)).scrollY > 0
                }
                scrolled
            }
            scenario.onActivity { activity ->
                val fragment = fragment(activity)
                assertTrue(fragment.handleGlassKeyEvent(GlassKeyEvent.KEYCODE_BEHIND))
                assertTrue(fragment.handleGlassKeyEvent(GlassKeyEvent.KEYCODE_CLICK))
                assertFalse(tag(fragment, "camera_query_overlay").isShown)
                firstUpdate("Late result from dismissed query")
                assertFalse("A dismissed query must not reopen when its worker finishes",
                    tag(fragment, "camera_query_overlay").isShown)

                val secondUpdate = beginQuery(fragment, "Second query")
                secondUpdate("Current query result")
                firstUpdate("Stale result from first query")
                assertTrue((tag(fragment, "camera_query_result") as TextView).text
                    .contains("Current query result"))
                assertFalse((tag(fragment, "camera_query_result") as TextView).text.contains("Stale"))
                assertTrue(fragment.handleGlassKeyEvent(GlassKeyEvent.KEYCODE_BACK))
                assertFalse(tag(fragment, "camera_query_overlay").isShown)
                assertSettingsOnly(fragment)
            }
            saveScreenshot("auto-query-closed-stopped-settings.png")
        }
    }

    @Test
    fun autoPreviewSupportsApplyLiveZoomResumeAndPersistentExplicitStop() {
        withConnectedSurface { scenario ->
            // There is deliberately no Apply call before the first real frames.
            awaitRealFrames(scenario, 1f)
            lateinit var defaultSession: CameraZoomSession
            lateinit var defaultCallback: Any
            var defaultGeneration = -1L
            var defaultTimestamp = 0L
            scenario.onActivity { activity ->
                val fragment = fragment(activity)
                assertPreviewOnly(fragment)
                assertEquals(1280, read(fragment, "requestedWidth"))
                assertEquals(720, read(fragment, "requestedHeight"))
                assertEquals(24, read(fragment, "requestedFps"))
                assertEquals(1f, read(fragment, "requestedZoom") as Float, 0f)
                assertEquals(false, read(fragment, "requestedEis"))
                assertEquals(false, read(fragment, "enableWatermark"))
                defaultSession = session(fragment)!!
                defaultCallback = read(fragment, "producerCallback")!!
                defaultGeneration = generation(fragment)
                defaultTimestamp = preview(fragment).surfaceTexture!!.timestamp
            }
            awaitRealFrames(scenario, 1f, defaultTimestamp)
            saveScreenshot("auto-preview-defaults.png")
            scenario.onActivity { activity ->
                val fragment = fragment(activity)
                assertSame("Automatic startup must settle on one session", defaultSession, session(fragment))
                assertSame("Automatic startup must not duplicate the Surface producer", defaultCallback,
                    read(fragment, "producerCallback"))
                assertEquals(defaultGeneration, generation(fragment))
                click(fragment, "camera_preview_configure")
                assertTrue(tag(fragment, "camera_settings_panel").isShown)
                assertTrue("Running preview can be returned to without reapplying",
                    tag(fragment, "camera_preview_return").isShown)
                click(fragment, "surface_query_capabilities")
                assertTrue("Query must open visible feedback immediately",
                    tag(fragment, "camera_query_overlay").isShown)
            }
            awaitQuery(scenario, if (context.resources.configuration.locales[0].language == "zh")
                "小数倍率共享：" else "Fractional zoom sharing: ")
            saveScreenshot("auto-preview-device-capabilities.png")
            scenario.onActivity { activity ->
                val fragment = fragment(activity)
                assertTrue(fragment.handleGlassKeyEvent(GlassKeyEvent.KEYCODE_CLICK))
                click(fragment, "surface_config_zoom")
                assertEquals(1.25f, read(fragment, "requestedZoom") as Float, 0f)
                assertSame("Selecting a parameter alone must not replace the running session",
                    defaultSession, session(fragment))
                click(fragment, "surface_apply")
            }
            awaitRealFrames(scenario, 1.25f)
            saveScreenshot("auto-preview-applied-1.25x.png")

            lateinit var appliedSession: CameraZoomSession
            lateinit var appliedCallback: Any
            var appliedGeneration = -1L
            var timestampBeforeZoom = 0L
            scenario.onActivity { activity ->
                val fragment = fragment(activity)
                assertPreviewOnly(fragment)
                appliedSession = session(fragment)!!
                appliedCallback = read(fragment, "producerCallback")!!
                appliedGeneration = generation(fragment)
                assertNotSame("Apply must start a session using the selected configuration",
                    defaultSession, appliedSession)
                assertNotSame(defaultCallback, appliedCallback)
                assertTrue(appliedGeneration > defaultGeneration)
                timestampBeforeZoom = preview(fragment).surfaceTexture!!.timestamp
                // Runtime controls remain reachable through temple navigation.
                assertTrue(fragment.handleGlassKeyEvent(GlassKeyEvent.KEYCODE_FRONT)) // Stop
                assertTrue(fragment.handleGlassKeyEvent(GlassKeyEvent.KEYCODE_FRONT)) // Live zoom
                assertTrue(fragment.handleGlassKeyEvent(GlassKeyEvent.KEYCODE_CLICK))
            }
            awaitRealFrames(scenario, 1.575f, timestampBeforeZoom)
            scenario.onActivity { activity ->
                val fragment = fragment(activity)
                assertSame("Live zoom must retain the same camera session", appliedSession, session(fragment))
                assertSame("Live zoom must retain the same Surface output", appliedCallback,
                    read(fragment, "producerCallback"))
                assertEquals("Live zoom must not restart the producer", appliedGeneration, generation(fragment))
            }
            saveScreenshot("auto-preview-live-1.575x.png")

            // A normal background/foreground round trip resumes the active preview with its
            // latest applied parameters. Leave an unapplied 2x selection in settings to ensure
            // resuming does not accidentally treat the pending selection as applied settings.
            scenario.onActivity { activity ->
                val fragment = fragment(activity)
                click(fragment, "camera_preview_configure")
                click(fragment, "surface_config_zoom")
                assertEquals(2f, read(fragment, "requestedZoom") as Float, 0f)
                assertSame(appliedSession, session(fragment))
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            drainWorker(scenario)
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitRealFrames(scenario, 1.575f)
            lateinit var resumedSession: CameraZoomSession
            scenario.onActivity { activity ->
                val fragment = fragment(activity)
                assertPreviewOnly(fragment)
                resumedSession = session(fragment)!!
                assertNotSame("Resume must replace the released session", appliedSession, resumedSession)
                click(fragment, "camera_preview_configure")
                click(fragment, "surface_more")
                click(fragment, "surface_follow_zoom")
            }
            await("followShared accepted for the existing session") {
                var following = false
                scenario.onActivity {
                    val fragment = fragment(it)
                    following = read(fragment, "followingShared") == true && session(fragment)?.state?.ready == true
                }
                following
            }
            scenario.onActivity { activity ->
                val fragment = fragment(activity)
                assertSame(resumedSession, session(fragment))
                click(fragment, "surface_query_capabilities")
                assertTrue(tag(fragment, "camera_query_overlay").isShown)
            }
            awaitQuery(scenario, "ready=true")
            saveScreenshot("auto-preview-resumed-state.png")
            scenario.onActivity { activity ->
                val fragment = fragment(activity)
                assertTrue(fragment.handleGlassKeyEvent(GlassKeyEvent.KEYCODE_BACK))
                click(fragment, "camera_preview_return")
                click(fragment, "surface_stop")
            }
            awaitStopped(scenario)
            drainWorker(scenario)
            scenario.onActivity { assertSettingsOnly(fragment(it)) }
            saveScreenshot("auto-preview-explicitly-stopped.png")

            scenario.moveToState(Lifecycle.State.CREATED)
            drainWorker(scenario)
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitStopped(scenario)
            drainWorker(scenario)
            // Observe a short stable interval as well as draining the worker: an unwanted
            // delayed auto-start would otherwise escape a single immediate null check.
            val stableUntil = SystemClock.uptimeMillis() + 1_000
            while (SystemClock.uptimeMillis() < stableUntil) {
                scenario.onActivity {
                    val fragment = fragment(it)
                    assertSettingsOnly(fragment)
                    assertNull("Explicit Stop must remain stopped after returning", session(fragment))
                    assertNull(read(fragment, "producerCallback"))
                }
                SystemClock.sleep(100)
            }
            saveScreenshot("auto-preview-stop-persists-after-resume.png")
            Log.i("SurfacePreviewTest", "REAL_SURFACE_AUTO_PREVIEW_PASS defaults=1280x720/24fps/1x/eisOff/watermarkOff singleSession=true apply=1.25 liveZoom=1.575 sameSession=true automaticResume=true unappliedSelectionIgnored=true follow=true explicitStopPersists=true")
        }
    }

    private fun withConnectedSurface(block: (ActivityScenario<CameraShareDemoActivity>) -> Unit) {
        var initializedSdkForTest = false
        try {
            instrumentation.runOnMainSync {
                if (!GlassSdk.isReady()) {
                    initializedSdkForTest = true
                    GlassSdkUtils.initSdk()
                }
            }
            await("SDK/media service connection") {
                GlassSdk.isReady() && GlassSdk.getGlassMediaService() != null
            }
            withSurface(block)
        } finally {
            if (initializedSdkForTest) instrumentation.runOnMainSync { GlassSdkUtils.destroySdk() }
        }
    }

    private fun withSurface(block: (ActivityScenario<CameraShareDemoActivity>) -> Unit) {
        var worker: ExecutorService? = null
        val intent = Intent(context, CameraShareDemoActivity::class.java)
            .putExtra("mode", "surface").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ActivityScenario.launch<CameraShareDemoActivity>(intent).use { scenario ->
                scenario.onActivity {
                    it.supportFragmentManager.executePendingTransactions()
                    worker = read(fragment(it), "worker") as ExecutorService
                }
                block(scenario)
            }
        } finally {
            worker?.let {
                if (it.isShutdown) assertTrue("Surface cleanup must finish before SDK teardown",
                    it.awaitTermination(5, TimeUnit.SECONDS))
                else it.submit {}.get(5, TimeUnit.SECONDS)
            }
        }
    }

    private fun drainWorker(scenario: ActivityScenario<CameraShareDemoActivity>) {
        lateinit var worker: ExecutorService
        scenario.onActivity { worker = read(fragment(it), "worker") as ExecutorService }
        worker.submit {}.get(5, TimeUnit.SECONDS)
        instrumentation.waitForIdleSync()
    }

    private fun awaitStopped(scenario: ActivityScenario<CameraShareDemoActivity>) {
        await("explicit stop releases the Surface and returns to settings") {
            var stopped = false
            scenario.onActivity {
                val fragment = fragment(it)
                stopped = session(fragment) == null && read(fragment, "producerCallback") == null &&
                    read(fragment, "startPending") == false &&
                    tag(fragment, "camera_settings_panel").isShown &&
                    !tag(fragment, "camera_query_overlay").isShown
            }
            stopped
        }
    }

    private fun awaitRealFrames(scenario: ActivityScenario<CameraShareDemoActivity>, zoom: Float,
        afterTimestamp: Long = 0L) {
        var previousTimestamp = afterTimestamp
        var progressingSamples = 0
        var detail = "No Surface snapshot"
        await("real Surface frames at ${zoom}x", { detail }) {
            assertEquals("Wake/unlock glasses and keep the Demo in the foreground", Lifecycle.State.RESUMED, scenario.state)
            var ready = false
            scenario.onActivity { activity ->
                val fragment = fragment(activity)
                val state = session(fragment)?.state
                val timestamp = preview(fragment).surfaceTexture?.timestamp ?: 0L
                if (timestamp > previousTimestamp) progressingSamples++
                previousTimestamp = maxOf(previousTimestamp, timestamp)
                detail = "timestamp=$timestamp samples=$progressingSamples state=$state"
                ready = progressingSamples >= 3 && state?.ready == true && state.appliedZoom.isFinite() &&
                    kotlin.math.abs(state.appliedZoom - zoom) < 0.03f &&
                    read(fragment, "startPending") == false &&
                    !tag(fragment, "camera_settings_panel").isShown &&
                    !tag(fragment, "camera_query_overlay").isShown
            }
            ready
        }
        Log.i("SurfacePreviewTest", "REAL_FRAMES zoom=$zoom $detail")
    }

    private fun awaitQuery(scenario: ActivityScenario<CameraShareDemoActivity>, contains: String) {
        var detail = ""
        await("query result contains $contains", { detail }) {
            var ready = false
            scenario.onActivity {
                val fragment = fragment(it)
                detail = (tag(fragment, "camera_query_result") as TextView).text.toString()
                ready = tag(fragment, "camera_query_overlay").isShown && detail.contains(contains)
            }
            ready
        }
    }

    private fun assertSettingsOnly(fragment: SurfaceShareFragment) {
        assertTrue(tag(fragment, "camera_settings_panel").isShown)
        assertFalse("Runtime controls must not appear while preview is stopped",
            tag(fragment, "camera_preview_actions").isShown)
        assertFalse("No hiding of an unstarted preview", tag(fragment, "camera_preview_return").isShown)
        val panel = tag(fragment, "camera_settings_panel")
        for (watermarkTag in listOf(
            "surface_watermark_text",
            "surface_watermark_sample",
            "surface_update_watermark",
        )) {
            assertFalse("Watermark edit controls must be hidden while watermark is off",
                tag(fragment, watermarkTag).isShown)
        }
        for (tag in listOf("surface_follow_zoom", "surface_await_zoom")) {
            assertFalse("Session tests must be disabled while stopped", tag(fragment, tag).isEnabled)
        }
        for (runtimeTag in listOf("surface_stop", "surface_live_zoom")) {
            assertFalse("Runtime action must not be in the settings panel: $runtimeTag",
                descendants(panel).any { it.tag == runtimeTag })
        }
    }

    private fun assertPreviewOnly(fragment: SurfaceShareFragment) {
        assertFalse("Settings are opened explicitly from Configure", tag(fragment, "camera_settings_panel").isShown)
        assertTrue(tag(fragment, "camera_preview_actions").isShown)
        assertTrue(tag(fragment, "camera_preview_configure").isShown)
        assertFalse("A startup failure/query must not cover a successful preview",
            tag(fragment, "camera_query_overlay").isShown)
    }

    private fun fragment(activity: CameraShareDemoActivity): SurfaceShareFragment =
        activity.supportFragmentManager.findFragmentByTag("camera_share_fragment") as SurfaceShareFragment

    private fun preview(fragment: SurfaceShareFragment) = read(fragment, "preview") as TextureView
    private fun session(fragment: SurfaceShareFragment) = read(fragment, "zoomSession") as? CameraZoomSession
    private fun generation(fragment: SurfaceShareFragment) = (read(fragment, "generation") as AtomicLong).get()
    private fun tag(fragment: SurfaceShareFragment, name: String): View =
        checkNotNull(fragment.requireView().findViewWithTag<View>(name)) { "Missing view tag $name" }
    private fun queryScroll(fragment: SurfaceShareFragment) =
        descendants(tag(fragment, "camera_query_overlay")).filterIsInstance<ScrollView>().single()

    private fun click(fragment: SurfaceShareFragment, name: String) {
        val button = tag(fragment, name)
        assertTrue("Button should be enabled: $name", button.isEnabled)
        assertTrue("Button should belong to visible UI: $name", button.isShown)
        assertTrue("Button must have an action: $name", button.performClick())
    }

    @Suppress("UNCHECKED_CAST")
    private fun beginQuery(fragment: SurfaceShareFragment, title: String): (String) -> Unit =
        invokeBase(fragment, "beginQueryResult", title) as (String) -> Unit

    private fun invokeBase(fragment: SurfaceShareFragment, name: String, text: String): Any? =
        CameraControlsFragment::class.java.getDeclaredMethod(name, String::class.java)
            .apply { isAccessible = true }.invoke(fragment, text)

    private fun read(instance: Any, name: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val field = type.declaredFields.firstOrNull { it.name == name }
            if (field != null) return field.apply { isAccessible = true }.get(instance)
            type = type.superclass
        }
        error("Missing field $name")
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }

    private fun await(label: String, detail: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out after 20 seconds: $label. ${detail()}")
    }

    private fun saveScreenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Screenshot unavailable" }
        try {
            val directory = File(checkNotNull(context.getExternalFilesDir(null)), "surface-preview-regression")
            check(directory.isDirectory || directory.mkdirs())
            File(directory, name).outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
