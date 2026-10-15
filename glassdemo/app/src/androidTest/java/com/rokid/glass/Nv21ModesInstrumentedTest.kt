package com.rokid.glass

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rokid.glass.base.GlassKeyEvent
import com.rokid.glass.camera.Nv21ExportFragment
import com.rokid.glass.utils.GlassSdkUtils
import com.rokid.glass.view.glsurface.view.BackgroundGLSurfaceView
import com.rokid.glesse.R
import com.rokid.security.glass3.open.sdk.GlassSdk
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Hardware smoke test: uses the real camera service and rendered NV21 frames, never synthetic
 * callbacks. Run only on awake, unlocked glasses with the Demo in the foreground and no other
 * camera test running. Each connection/preview wait is bounded to 20 seconds.
 *
 * This verifies delivery, preview and mode switching at 1280x720/15 fps/1x/EIS off. It does not
 * establish precise capture FPS, applied zoom metadata for V1/byte[], or allocation performance.
 */
@RunWith(AndroidJUnit4::class)
class Nv21ModesInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val foregroundMessage =
        "Hardware test precondition: glasses must stay awake/unlocked and Demo RESUMED; wake the device and retry"

    @Test
    fun realPreviewSurvivesAllThreePublicExportModesAndReturnsToOptionsBuffer() {
        val preferences = context.getSharedPreferences("nv21_preview", 0)
        val originalPreferences = preferences.all.mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else value
        }
        var initializedSdkForTest = false
        try {
            assertTrue("Could not write temporary camera configuration", preferences.edit().clear()
                .putInt("width", 1280).putInt("height", 720).putInt("fps", 15)
                .putFloat("zoom_ratio", 1f).putBoolean("eis", false)
                .putString("api_mode", "OPTIONS_BUFFER").commit())

            // Initialize only the SDK. Home also changes persistent device settings unrelated
            // to this camera test (for example the camera LED), so do not launch Home here.
            instrumentation.runOnMainSync {
                if (!GlassSdk.isReady()) {
                    initializedSdkForTest = true
                    GlassSdkUtils.initSdk()
                }
            }
            await("SDK/media service connection") {
                GlassSdk.isReady() && GlassSdk.getGlassMediaService() != null
            }
            val intent = Intent(context, CameraShareDemoActivity::class.java)
                .putExtra("mode", "nv21")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            var cameraExecutor: ExecutorService? = null
            try {
                ActivityScenario.launch<CameraShareDemoActivity>(intent).use { camera ->
                    camera.onActivity {
                        it.supportFragmentManager.executePendingTransactions()
                        cameraExecutor = field(fragment(it), "cameraExecutor") as ExecutorService
                    }
                    val modes = listOf("OPTIONS_BUFFER", "OPTIONS_ARRAY", "CONFIG_BUFFER", "OPTIONS_BUFFER")
                    for ((index, mode) in modes.withIndex()) {
                        if (index > 0) selectNextModeAndApply(camera, mode)
                        awaitRenderedFrames(camera, mode)
                        saveScreenshot("$index-$mode-preview.png")
                        verifyModeControls(camera, mode)
                        saveScreenshot("$index-$mode-config.png")
                    }
                }
            } finally {
                // onPause/onDestroyView queue release on this worker. Drain those tasks before
                // disconnecting an SDK initialized by this test. onDestroy shuts the worker down.
                cameraExecutor?.let { executor ->
                    if (executor.isShutdown) {
                        assertTrue("Camera release must finish before SDK teardown",
                            executor.awaitTermination(5, TimeUnit.SECONDS))
                    } else {
                        executor.submit {}.get(5, TimeUnit.SECONDS)
                    }
                }
            }
        } finally {
            try {
                if (initializedSdkForTest) instrumentation.runOnMainSync { GlassSdkUtils.destroySdk() }
            } finally {
                restorePreferences(preferences, originalPreferences)
            }
        }
    }

    private fun selectNextModeAndApply(scenario: ActivityScenario<CameraShareDemoActivity>, mode: String) {
        scenario.onActivity { activity ->
            assertEquals(foregroundMessage, Lifecycle.State.RESUMED, activity.lifecycle.currentState)
            click(activity, R.id.btn_show_config)
            val fragment = fragment(activity)
            val zoom = activity.findViewById<SeekBar>(R.id.seekbar_zoom)
            if (mode == "CONFIG_BUFFER") {
                // An un-applied fractional selection must reset explicitly when entering V1.
                zoom.progress = 2
                assertEquals(1.575f, field(fragment, "currentZoom") as Float, 0.0001f)
            }
            click(activity, R.id.btn_nv21_api)
            assertEquals(mode, field(fragment, "selectedMode").toString())
            assertEquals("A pending mode cannot control the previous session", listOf(false, false, false),
                sessionControls(fragment).map { it.isEnabled })
            if (mode == "CONFIG_BUFFER") {
                assertEquals("V1 must explicitly reset a fractional selection to 1x", 1f,
                    field(fragment, "currentZoom") as Float, 0f)
            }
            assertEquals("Every hardware preview in this test requests 1x", 0, zoom.progress)
            val oldGeneration = field(fragment, "callbackGeneration") as Int
            click(activity, R.id.btn_apply)
            assertTrue("Applying must invalidate callbacks from the previous export",
                (field(fragment, "callbackGeneration") as Int) > oldGeneration)
        }
    }

    private fun awaitRenderedFrames(scenario: ActivityScenario<CameraShareDemoActivity>, mode: String) {
        var lastSnapshot = "No preview state yet"
        await("$mode: more than five real rendered frames and completed configuration switch", { lastSnapshot }) {
            assertEquals(foregroundMessage, Lifecycle.State.RESUMED, scenario.state)
            var ready = false
            scenario.onActivity { activity ->
                assertEquals(foregroundMessage, Lifecycle.State.RESUMED, activity.lifecycle.currentState)
                val fragment = fragment(activity)
                val preview = activity.findViewById<BackgroundGLSurfaceView>(R.id.nv21_surface_view)
                val active = field(fragment, "activeMode").toString()
                val switching = (field(fragment, "configSwitchJob") as? Job)?.isActive == true
                val width = field(fragment, "currentWidth") as Int
                val height = field(fragment, "currentHeight") as Int
                val rendered = preview.renderedFrameCount
                val info = activity.findViewById<TextView>(R.id.tv_info).text.toString()
                lastSnapshot = "active=$active switching=$switching size=${width}x$height rendered=$rendered info=$info"
                ready = active == mode && !switching && width == 1280 && height == 720 && rendered > 5 &&
                    activity.findViewById<View>(R.id.btn_apply).isEnabled
            }
            ready
        }
        Log.i("Nv21ModesTest", "REAL_PREVIEW_PASS mode=$mode $lastSnapshot")
        val preferences = context.getSharedPreferences("nv21_preview", 0)
        assertEquals("Mode must be persisted only after its first matching frame", mode,
            preferences.getString("api_mode", null))
        assertEquals(1280, preferences.getInt("width", -1))
        assertEquals(720, preferences.getInt("height", -1))
        assertEquals(15, preferences.getInt("fps", -1))
        assertEquals(1f, preferences.getFloat("zoom_ratio", -1f), 0f)
        assertFalse(preferences.getBoolean("eis", true))
    }

    private fun verifyModeControls(scenario: ActivityScenario<CameraShareDemoActivity>, mode: String) {
        scenario.onActivity { activity ->
            click(activity, R.id.btn_show_config)
            val fragment = fragment(activity)
            assertEquals(mode, field(fragment, "selectedMode").toString())
            val zoom = activity.findViewById<SeekBar>(R.id.seekbar_zoom)
            val isV1 = mode == "CONFIG_BUFFER"
            val expectedRatios = if (isV1) listOf(1f, 2f, 3f, 4f, 6f, 8f)
                else listOf(1f, 1.25f, 1.575f, 2f, 3f, 4f, 6f, 8f)
            assertEquals(if (isV1) 5 else 7, zoom.max)
            expectedRatios.forEachIndexed { position, ratio ->
                zoom.progress = position
                assertEquals("$mode zoom choice $position", ratio, field(fragment, "currentZoom") as Float, 0f)
            }
            zoom.progress = 0 // Selection only; keep all actual camera sessions at 1x.
            val controls = sessionControls(fragment)
            assertEquals("Apply zoom, await actual zoom and follow shared must remain present", 3, controls.size)
            controls.forEach { control ->
                assertEquals("$mode session control: ${control.text}", !isV1, control.isEnabled)
            }
            val nav = configControls(fragment)
            assertEquals("Expandable controls and footer must share one temple navigation list", 12, nav.size)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.config_panel).visibility)
            repeat(5) { assertTrue(fragment.handleGlassKeyEvent(GlassKeyEvent.KEYCODE_FRONT)) }
            assertEquals("nv21_more", nav[selectedConfigIndex(fragment)].tag)
            if ((nav[6].parent as View).visibility != View.VISIBLE) {
                assertTrue(fragment.handleGlassKeyEvent(GlassKeyEvent.KEYCODE_CLICK)) // Expand zoom and query.
            }
            assertEquals("Expand control must reveal its child buttons", View.VISIBLE,
                (nav[6].parent as View).visibility)
            val queryIndex = nav.indexOfFirst { it.tag == "nv21_query_state" }
            assertTrue(queryIndex > 0)
            var steps = 0
            val visited = mutableListOf<String>()
            while (selectedConfigIndex(fragment) != queryIndex && steps++ < nav.size) {
                assertTrue(fragment.handleGlassKeyEvent(GlassKeyEvent.KEYCODE_FRONT))
                val index = selectedConfigIndex(fragment)
                visited += "$index:${nav[index].tag ?: nav[index].id}"
            }
            assertEquals("Navigation path=$visited shown=${nav.map { it.isShown }} enabled=${nav.map { it.isEnabled }}",
                "nv21_query_state", nav[selectedConfigIndex(fragment)].tag)
            assertTrue(fragment.handleGlassKeyEvent(GlassKeyEvent.KEYCODE_CLICK))
            assertEquals("nv21_query_overlay", activity.findViewById<View>(android.R.id.content)
                .findViewWithTag<View>("nv21_query_overlay")?.tag)
            assertTrue(fragment.handleGlassKeyEvent(GlassKeyEvent.KEYCODE_BACK))
            assertTrue(fragment.handleGlassKeyEvent(GlassKeyEvent.KEYCODE_FRONT))
            assertEquals(R.id.btn_apply, nav[selectedConfigIndex(fragment)].id)
        }
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            assertFullyVisible(activity.findViewById(R.id.btn_apply))
            val fragment = fragment(activity)
            val nav = configControls(fragment)
            assertTrue(fragment.handleGlassKeyEvent(GlassKeyEvent.KEYCODE_FRONT))
            assertEquals(R.id.btn_toggle_config, nav[selectedConfigIndex(fragment)].id)
        }
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            assertFullyVisible(activity.findViewById(R.id.btn_toggle_config))
            val fragment = fragment(activity)
            assertTrue(fragment.handleGlassKeyEvent(GlassKeyEvent.KEYCODE_CLICK))
            assertEquals(View.GONE, activity.findViewById<View>(R.id.config_panel).visibility)
        }
    }

    private fun assertFullyVisible(view: View) {
        val bounds = Rect()
        assertTrue("Selected footer must be on-screen: id=${view.id}", view.getGlobalVisibleRect(bounds))
        val location = IntArray(2).also { view.getLocationOnScreen(it) }
        val scroll = view.rootView.findViewById<android.widget.ScrollView>(R.id.config_panel)
        assertEquals("Selected footer must not be vertically clipped: id=${view.id} viewY=${location[1]} " +
            "visible=$bounds scrollY=${scroll.scrollY} scrollH=${scroll.height} scrollChildH=${scroll.getChildAt(0).height}",
            view.height, bounds.height())
    }

    @Suppress("UNCHECKED_CAST")
    private fun configControls(fragment: Nv21ExportFragment): List<View> =
        field(fragment, "configControls") as List<View>

    private fun selectedConfigIndex(fragment: Nv21ExportFragment): Int =
        field(fragment, "selectedConfigIndex") as Int

    private fun click(activity: CameraShareDemoActivity, id: Int) {
        val button = activity.findViewById<View>(id)
        assertTrue("Expected enabled button id=$id", button.isEnabled)
        assertEquals("Expected visible button id=$id", View.VISIBLE, button.visibility)
        assertTrue("Expected click listener id=$id", button.performClick())
    }

    private fun fragment(activity: CameraShareDemoActivity): Nv21ExportFragment =
        activity.supportFragmentManager.findFragmentByTag("camera_share_fragment") as Nv21ExportFragment

    private fun field(fragment: Nv21ExportFragment, name: String): Any? =
        Nv21ExportFragment::class.java.getDeclaredField(name).apply { isAccessible = true }.get(fragment)

    @Suppress("UNCHECKED_CAST")
    private fun sessionControls(fragment: Nv21ExportFragment): List<Button> =
        field(fragment, "sessionControls") as List<Button>

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
            val directory = File(checkNotNull(context.getExternalFilesDir(null)), "nv21-modes")
            check(directory.isDirectory || directory.mkdirs())
            File(directory, name).outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun restorePreferences(preferences: SharedPreferences, saved: Map<String, *>) {
        val editor = preferences.edit().clear()
        saved.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value as Set<String>)
                null -> editor.remove(key)
                else -> error("Unexpected SharedPreferences type for $key: ${value.javaClass}")
            }
        }
        assertTrue("Could not restore the user's NV21 preferences", editor.commit())
    }
}
