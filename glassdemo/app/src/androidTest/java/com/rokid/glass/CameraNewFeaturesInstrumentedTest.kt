package com.rokid.glass

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.Surface
import android.widget.FrameLayout
import android.util.Log
import java.io.File
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rokid.glass.camera.CameraTextureView
import com.rokid.glass.camera.LeaseConsumer
import com.rokid.glass.view.glsurface.view.I420SurfaceView
import com.rokid.glass.utils.GlassSdkUtils
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.camera.CameraShareHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.nio.ByteBuffer

/** Requires awake RG-glasses running the current service. No Home, recording or global zoom calls. */
@RunWith(AndroidJUnit4::class)
class CameraNewFeaturesInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun i420ManualBaselineReceivesRealFrames() = connected("i420") { scenario ->
        lateinit var preview: I420SurfaceView
        scenario.onActivity {
            val f = fragment(it)
            preview = read(f, "preview") as I420SurfaceView
            click(f, "i420_start")
        }
        await("Manual baseline I420 must render real frames") { preview.renderedFrameCount >= 10 }
        assertFalse(isPreviewBlack(preview))
    }

    @Test fun i420AutomaticPreviewReceivesRealFramesAndFps() = connected("i420") { scenario ->
        lateinit var preview: I420SurfaceView
        scenario.onActivity { preview = read(fragment(it), "preview") as I420SurfaceView }
        await("I420 must receive and render real frames with measured FPS") {
            var measured = 0L
            scenario.onActivity { measured = read(fragment(it), "measuredFps") as? Long ?: 0L }
            preview.renderedFrameCount >= 10 && measured > 0
        }
        assertFalse("Exported camera image must not be black", isPreviewBlack(preview))
    }

    @Test fun i420PagesAndFpsOptions() = connected("i420") { scenario ->
        await("automatic I420 start") {
            var started = false
            scenario.onActivity { started = read(fragment(it), "exporting") as Boolean && tag(fragment(it), "i420_start").isEnabled }
            started
        }
        scenario.onActivity {
            val f = fragment(it)
            assertEquals(15, read(f, "fps"))
            assertFalse(tag(f, "camera_settings_panel").isShown)
            click(f, "camera_preview_configure")
            assertTrue(tag(f, "camera_settings_panel").isShown)
            for (expected in listOf(20, 24, 30, 15)) {
                click(f, "i420_fps")
                assertEquals(expected, read(f, "fps"))
            }
        }
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(300)
        val lang = context.resources.configuration.locales[0].language
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), "i420-settings-$lang.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        scenario.onActivity { click(fragment(it), "camera_preview_return") }
        scenario.onActivity { assertFalse(tag(fragment(it), "camera_settings_panel").isShown) }
        scenario.onActivity { click(fragment(it), "i420_stop") }
        await("explicit stop") {
            var stopped = false
            scenario.onActivity { stopped = !(read(fragment(it), "exporting") as Boolean) }
            stopped
        }
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        scenario.onActivity { assertFalse(read(fragment(it), "previewWanted") as Boolean) }
    }

    @Test fun i420PagesInEnglish() {
        val previous = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        instrumentation.runOnMainSync {
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags("en"))
        }
        try { i420PagesAndFpsOptions() }
        finally { instrumentation.runOnMainSync {
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(previous)
        } }
    }

    @Test fun cameraBufferSizeSurvivesPreviewLayoutChanges() = connected("i420") { scenario ->
        lateinit var preview: CameraTextureView
        scenario.onActivity {
            val host = read(fragment(it), "previewHost") as FrameLayout
            host.removeAllViews()
            preview = CameraTextureView(it)
            host.addView(preview, FrameLayout.LayoutParams(360, 240))
        }
        await("TextureView surface") { preview.isAvailable }
        scenario.onActivity { preview.setCameraBufferSize(1280, 720) }
        // A Canvas producer lets us inspect the actual BufferQueue allocation, without reflection
        // into Android internals. Resizing the UI must not change the camera's negotiated buffer.
        fun assertBufferSize() {
            val surface = Surface(preview.surfaceTexture)
            try {
                val canvas = surface.lockCanvas(null)
                try { assertEquals(1280, canvas.width); assertEquals(720, canvas.height) }
                finally { surface.unlockCanvasAndPost(canvas) }
            } finally { surface.release() }
        }
        assertBufferSize()
        scenario.onActivity { preview.layoutParams = FrameLayout.LayoutParams(320, 180) }
        await("Preview layout changed") { preview.width == 320 && preview.height == 180 }
        instrumentation.waitForIdleSync()
        assertBufferSize()
    }

    /** Save actual rendered output and transforms so aspect/direction can be compared on glasses. */
    @Test fun previewGeometryEvidence() {
        connected("surface") { scenario ->
            lateinit var preview: TextureView
            scenario.onActivity { preview = read(fragment(it), "preview") as TextureView }
            await("Surface frames") {
                var ready = false
                scenario.onActivity { ready = (read(fragment(it), "zoomSession") as? com.rokid.security.glass3.open.sdk.camera.CameraZoomSession)?.state?.ready == true }
                ready
            }
            scenario.onActivity {
                val f = fragment(it)
                val texture = FloatArray(16)
                preview.surfaceTexture!!.getTransformMatrix(texture)
                Log.i("PreviewGeometry", "direct view=${preview.width}x${preview.height} image=${read(f, "displayWidth")}x${read(f, "displayHeight")} texture=${texture.toList()} viewTransform=${preview.getTransform(null)}")
                saveGeometry("surface-direct", preview.bitmap!!)
            }
            saveGeometry("surface-direct-screen", instrumentation.uiAutomation.takeScreenshot()!!)
        }
        connected("surface_legacy") { scenario ->
            lateinit var f: Fragment
            lateinit var preview: android.opengl.GLSurfaceView
            scenario.onActivity {
                f = fragment(it)
                preview = f.requireView().findViewById(com.rokid.glesse.R.id.gl_surface_view)
            }
            await("Original page frames") { (read(f, "frameCount") as Long) >= 4 }
            saveGeometry("surface-remote-original", copyPreview(preview))
        }
    }

    @Test fun surfaceUsesActualCallbackSizeWithoutStretchingItsPreviewBuffer() = connected("surface") { scenario ->
        lateinit var f: Fragment
        lateinit var preview: CameraTextureView
        scenario.onActivity { f = fragment(it); preview = read(f, "preview") as CameraTextureView }
        await("Surface ready") {
            (read(f, "zoomSession") as? com.rokid.security.glass3.open.sdk.camera.CameraZoomSession)?.state?.ready == true
        }
        val callback = read(f, "producerCallback") as com.rokid.security.system.server.media.callback.ICameraSurfaceCallback
        // Shared camera arbitration may keep a 4:3 source despite this output requesting 16:9.
        // Public legacy callbacks give camera-source dimensions (480 x 640), as on the old page.
        callback.onSurfaceShareConfigChanged(480, 640, 24, false)
        await("Actual source must update the displayed output to 640 x 480") {
            read(f, "displayWidth") == 640 && read(f, "displayHeight") == 480
        }
        instrumentation.waitForIdleSync()
        scenario.onActivity {
            assertEquals("Preview itself must retain the actual content aspect", 4f / 3f,
                preview.width.toFloat() / preview.height, 0.01f)
            assertTrue("Do not rely on a second non-uniform TextureView scale to undo stretching",
                preview.getTransform(null).isIdentity)
        }
    }

    @Test fun surfaceKeepsAspectAfterStatusPanelChangesSize() = connected("surface") { scenario ->
        lateinit var preview: TextureView
        scenario.onActivity { preview = read(fragment(it), "preview") as TextureView }
        await("Surface ready") {
            var ready = false
            scenario.onActivity { ready = (read(fragment(it), "zoomSession") as? com.rokid.security.glass3.open.sdk.camera.CameraZoomSession)?.state?.ready == true }
            ready
        }
        for (lines in listOf(1, 7, 3)) {
            var timestamp = 0L
            scenario.onActivity {
                (read(fragment(it), "status") as TextView).text = List(lines) { "Aspect check" }.joinToString("\n")
                timestamp = preview.surfaceTexture!!.timestamp
            }
            instrumentation.waitForIdleSync()
            await("Frame after layout") { preview.surfaceTexture!!.timestamp > timestamp }
            scenario.onActivity {
                val f = fragment(it)
                val matrix = FloatArray(9)
                preview.getTransform(null).getValues(matrix)
                val aspect = preview.width * matrix[Matrix.MSCALE_X] / (preview.height * matrix[Matrix.MSCALE_Y])
                val expected = (read(f, "displayWidth") as Int).toFloat() / (read(f, "displayHeight") as Int)
                assertEquals("Image must retain aspect after $lines status lines: view=${preview.width}x${preview.height}, transform=${matrix.toList()}", expected, aspect, 0.005f)
            }
        }
    }

    private fun saveGeometry(name: String, bitmap: Bitmap) {
        val directory = File(context.getExternalFilesDir(null), "preview-geometry").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun legacySurfaceStartsOriginalPreviewAndReleasesOnPause() = connected("surface_legacy") { scenario ->
        lateinit var original: Fragment
        scenario.onActivity { original = fragment(it) }
        fun activeHelper() = read(original, "surfaceHelper") as? CameraShareHelper
        await("Legacy entry must start the remote original 1280x720 preview without another click") {
            activeHelper()?.let { it.isSurfaceActive() && it.getCameraWidth() > 0 } == true
        }
        val helper = activeHelper()!!
        scenario.onActivity {
            for (name in listOf("surface_await_zoom", "surface_live_zoom", "surface_watermark", "surface_query_capabilities")) {
                assertNull("Remote original page must not mix in additional API modes: $name", original.requireView().findViewWithTag<View>(name))
            }
        }
        assertEquals(720, helper.getCameraWidth())
        assertEquals(1280, helper.getCameraHeight())
        scenario.moveToState(Lifecycle.State.CREATED)
        await("Legacy pause releases its producer") { !helper.isSurfaceActive() }
        scenario.moveToState(Lifecycle.State.RESUMED)
        await("Legacy preview restarts on resume") { helper.isSurfaceActive() && helper.getCameraWidth() > 0 }
        scenario.recreate()
        scenario.onActivity { original = fragment(it) }
        await("Legacy preview restarts after recreation") {
            activeHelper()?.let { it.isSurfaceActive() && it.getCameraWidth() > 0 } == true
        }
    }

    @Test fun i420AsyncV2AndV1ReturnEveryOutstandingLeaseAfterStop() = connected("i420") { scenario ->
        lateinit var preview: I420SurfaceView
        scenario.onActivity { preview = read(fragment(it), "preview") as I420SurfaceView }
        scenario.onActivity { click(fragment(it), "i420_async") }
        repeat(2) { index ->
            val before = preview.renderedFrameCount
            scenario.onActivity { click(fragment(it), "i420_start") }
            lateinit var consumer: LeaseConsumer<*>
            // Queue-pressure behavior is covered by LeaseConsumerTest using a blocked consumer;
            // a real preview must not add artificial latency just to force dropped frames.
            await("I420 $index async consumer must process and return real frames") {
                var ready = false
                scenario.onActivity {
                    val value = read(fragment(it), "consumer") as? LeaseConsumer<*>
                    if (value != null) { consumer = value; ready = value.returned.get() >= 5 && value.returnFailures.get() == 0 }
                }
                ready
            }
            await("I420 $index async frames must also be drawn") {
                preview.receivedFrameCount >= 2 && preview.renderedFrameCount > before
            }
            scenario.onActivity { click(fragment(it), "i420_stop") }
            await("I420 must return outstanding leases even after releaseI420Export") {
                consumer.received.get() > 0 && consumer.received.get() == consumer.returned.get()
            }
            if (index == 0) scenario.onActivity { click(fragment(it), "i420_api") }
        }
    }

    @Test fun i420PreviewDrawsPlanesAndSupportsDisableStopResizeAndResume() = connected("i420") { scenario ->
        lateinit var preview: I420SurfaceView
        scenario.onActivity {
            val f = fragment(it)
            preview = read(f, "preview") as I420SurfaceView
            click(f, "camera_preview_return")
        }
        // Six-pixel Y / three-pixel chroma rows exercise unpack alignment; distinct U/V detects swaps.
        preview.submit(ByteBuffer.wrap(ByteArray(24) { 128.toByte() }),
            ByteBuffer.wrap(ByteArray(6) { 64 }), ByteBuffer.wrap(ByteArray(6) { 192.toByte() }),
            6, 4, 6, 3, 3)
        await("I420 shader must draw") { preview.renderedFrameCount > 0 }
        copyPreview(preview).let { bitmap ->
            val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            assertEquals(229.0, Color.red(pixel).toDouble(), 5.0)
            assertEquals(110.0, Color.green(pixel).toDouble(), 5.0)
            assertEquals(10.0, Color.blue(pixel).toDouble(), 5.0)
            bitmap.recycle()
        }
        var before = preview.renderedFrameCount
        scenario.onActivity { click(fragment(it), "i420_start") }
        await("Synchronous I420 must display real camera frames") {
            preview.receivedFrameCount >= 5 && preview.renderedFrameCount > before
        }
        copyPreview(preview).let { bitmap ->
            assertTrue("Exported camera preview must contain visible pixels", hasVisiblePixels(bitmap))
            File(context.getExternalFilesDir(null), "camera-i420-preview.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        scenario.onActivity { click(fragment(it), "i420_preview_toggle") }
        await("Disabling preview must clear its image") { isPreviewBlack(preview) }
        assertEquals(0L, preview.receivedFrameCount)
        scenario.onActivity {
            val helper = read(fragment(it), "helper") as CameraShareHelper
            assertTrue("Preview toggle must not stop export", helper.isI420Active())
            click(fragment(it), "i420_preview_toggle")
        }
        await("Preview can be reenabled without restarting export") { preview.receivedFrameCount >= 3 }
        scenario.onActivity { click(fragment(it), "i420_stop") }
        await("Stop must clear the last exported image") { preview.receivedFrameCount == 0L && isPreviewBlack(preview) }
        before = preview.renderedFrameCount
        scenario.onActivity {
            click(fragment(it), "i420_size")
            click(fragment(it), "i420_start")
        }
        await("New resolution must render after stop") { preview.receivedFrameCount >= 3 && preview.renderedFrameCount > before }
        scenario.moveToState(Lifecycle.State.CREATED)
        await("Background must release pending copies") { preview.receivedFrameCount == 0L }
        scenario.moveToState(Lifecycle.State.RESUMED)
        before = preview.renderedFrameCount
        scenario.onActivity { click(fragment(it), "i420_start") }
        await("Resume must recreate GL and draw new I420 frames") {
            preview.receivedFrameCount >= 3 && preview.renderedFrameCount > before
        }
    }

    private fun copyPreview(preview: SurfaceView): Bitmap {
        val deadline = SystemClock.elapsedRealtime() + 5000
        do {
            val bitmap = Bitmap.createBitmap(preview.width, preview.height, Bitmap.Config.ARGB_8888)
            val latch = CountDownLatch(1)
            var result = -1
            PixelCopy.request(preview, bitmap, { result = it; latch.countDown() }, Handler(Looper.getMainLooper()))
            assertTrue("Read rendered I420 Surface", latch.await(5, TimeUnit.SECONDS))
            if (result == PixelCopy.SUCCESS) return bitmap
            bitmap.recycle()
            // Status text can resize the preview Surface. Wait for the first swap of its new buffer;
            // NO_DATA is not a black frame and must not count as proof that clear() succeeded.
            assertEquals(PixelCopy.ERROR_SOURCE_NO_DATA, result)
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("I420 Surface did not submit a buffer for PixelCopy")
    }
    private fun hasVisiblePixels(bitmap: Bitmap): Boolean = (1..9).any { y ->
        (1..9).any { x ->
            val pixel = bitmap.getPixel(bitmap.width * x / 10, bitmap.height * y / 10)
            maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 10
        }
    }
    private fun isPreviewBlack(preview: I420SurfaceView) = copyPreview(preview).let { bitmap ->
        (!hasVisiblePixels(bitmap)).also { bitmap.recycle() }
    }

    @Test fun directSurfaceWatermarkSwitchAppliesOnPreview() = connected("surface") { scenario ->
        await("Initial Surface preview") {
            var ready = false
            scenario.onActivity { ready = read(fragment(it), "previewStarted") == true && read(fragment(it), "startPending") == false }
            ready
        }
        for (enabled in listOf(true, false)) {
            scenario.onActivity {
                click(fragment(it), "camera_preview_configure")
                click(fragment(it), "surface_watermark")
                click(fragment(it), "surface_apply")
            }
            await("Surface watermark enabled=$enabled must apply") {
                var ready = false
                scenario.onActivity {
                    val f = fragment(it)
                    val session = read(f, "zoomSession") as? com.rokid.security.glass3.open.sdk.camera.CameraZoomSession
                    ready = session?.state?.ready == true && read(f, "startPending") == false &&
                        (read(f, "producerWatermark") != null) == enabled
                }
                ready
            }
        }
    }

    private fun connected(mode: String, body: (ActivityScenario<CameraShareDemoActivity>) -> Unit) {
        var initialized = false
        var worker: ExecutorService? = null
        try {
            instrumentation.runOnMainSync {
                if (!GlassSdk.isReady()) { initialized = true; GlassSdkUtils.initSdk() }
            }
            await("SDK connection") { GlassSdk.isReady() && GlassSdk.getGlassMediaService() != null }
            ActivityScenario.launch<CameraShareDemoActivity>(Intent(context, CameraShareDemoActivity::class.java)
                .putExtra("mode", mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
                scenario.onActivity {
                    it.supportFragmentManager.executePendingTransactions()
                    worker = if (fragment(it) is com.rokid.glass.camera.CameraControlsFragment) read(fragment(it), "worker") as ExecutorService else null
                }
                body(scenario)
            }
        } finally {
            worker?.let { if (it.isShutdown) assertTrue(it.awaitTermination(5, TimeUnit.SECONDS)) }
            if (initialized) instrumentation.runOnMainSync { GlassSdkUtils.destroySdk() }
        }
    }

    private fun fragment(activity: CameraShareDemoActivity): Fragment =
        activity.supportFragmentManager.findFragmentByTag("camera_share_fragment")!!
    private fun tag(fragment: Fragment, tag: String): View = fragment.requireView().findViewWithTag(tag)
    private fun click(fragment: Fragment, tag: String) { assertTrue(tag(fragment, tag).performClick()) }
    private fun read(target: Any, name: String): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            try { return type.getDeclaredField(name).apply { isAccessible = true }.get(target) }
            catch (_: NoSuchFieldException) { type = type.superclass }
        }
        error("Missing field $name")
    }
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue(message, condition())
    }
}
