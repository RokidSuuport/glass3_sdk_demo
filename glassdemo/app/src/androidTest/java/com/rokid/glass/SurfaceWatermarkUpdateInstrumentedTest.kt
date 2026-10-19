package com.rokid.glass

import android.content.Intent
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.content.res.Configuration
import android.os.LocaleList
import android.widget.TextView
import com.rokid.glass.base.GlassKeyEvent
import java.util.Locale
import android.graphics.Bitmap
import android.view.TextureView
import android.view.View
import android.widget.EditText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rokid.glass.camera.SurfaceShareFragment
import com.rokid.glass.utils.GlassSdkUtils
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.camera.CameraZoomSession
import com.rokid.security.glass3.sdk.base.data.media.TextWatermarkConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** 在真实相机输出上改字并验证不重启及退出清理。
 * Update real camera frames and verify continuity and exit cleanup.
 */
@RunWith(AndroidJUnit4::class)
class SurfaceWatermarkUpdateInstrumentedTest {
    @Test fun textUpdatesKeepTheSamePreviewAndReleaseResourcesOnExit() = verifyUpdate("zh-CN", 1f)

    @Test fun englishLargeTextUpdatesKeepTheSamePreviewAndReleaseResourcesOnExit() = verifyUpdate("en", 1.3f)

    private fun verifyUpdate(language: String, scale: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        val initialized = !GlassSdk.isReady()
        if (initialized) instrumentation.runOnMainSync { GlassSdkUtils.initSdk() }
        await("SDK ready") { GlassSdk.isReady() && GlassSdk.getGlassMediaService() != null }
        val application = instrumentation.targetContext.applicationContext as Application
        val originalConfiguration = Configuration(application.resources.configuration)
        val configuration = Configuration(originalConfiguration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(language)))
            fontScale = scale
        }
        val overrides = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, state: Bundle?) {
                if (activity is CameraShareDemoActivity) {
                    @Suppress("DEPRECATION")
                    activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
                }
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        application.registerActivityLifecycleCallbacks(overrides)
        var worker: ExecutorService? = null
        try {
            ActivityScenario.launch<CameraShareDemoActivity>(Intent(instrumentation.targetContext,
                CameraShareDemoActivity::class.java).putExtra("mode", "surface")).use { scenario ->
                lateinit var f: SurfaceShareFragment
                scenario.onActivity {
                    it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    f = it.supportFragmentManager.findFragmentByTag("camera_share_fragment") as SurfaceShareFragment
                    worker = read(f, "worker") as ExecutorService
                }
                await("initial frames") { ready(f) }
                scenario.onActivity { click(f, "camera_preview_configure") }
                scenario.onActivity {
                    for (tagName in listOf(
                        "surface_watermark_text",
                        "surface_watermark_sample",
                        "surface_update_watermark",
                    )) {
                        assertFalse("$tagName must be hidden while watermark is off", tag(f, tagName).isShown)
                    }
                    click(f, "surface_watermark")
                }
                await("watermark controls measured") {
                    var measured = false
                    scenario.onActivity {
                        val button = tag(f, "surface_update_watermark") as TextView
                        val sample = tag(f, "surface_watermark_sample")
                        val input = tag(f, "surface_watermark_text")
                        measured = button.layout != null && button.width > 0 &&
                            button.isShown && sample.isShown && input.isShown
                    }
                    measured
                }
                scenario.onActivity {
                    val button = tag(f, "surface_update_watermark") as TextView
                    assertEquals(scale, f.resources.configuration.fontScale, 0.01f)
                    assertTrue(button.text.toString().contains(
                        if (language == "en") "Modify watermark dynamically" else "动态修改水印"))
                    button.requestRectangleOnScreen(android.graphics.Rect(0, 0, button.width, button.height), true)
                    assertTrue(button.isShown)
                    for (line in 0 until button.layout.lineCount) {
                        assertEquals(0, button.layout.getEllipsisCount(line))
                        assertTrue(button.layout.getLineWidth(line) <= button.width - button.compoundPaddingLeft - button.compoundPaddingRight + 1)
                    }
                    click(f, "surface_apply")
                }
                await("watermarked frames") { ready(f) && read(f, "producerWatermark") != null }
                val callback = read(f, "producerCallback")
                val session = read(f, "zoomSession")
                val surface = read(f, "producerSurface")
                val generation = (read(f, "generation") as AtomicLong).get()
                var timestamp = 0L
                for ((name, text) in listOf("before" to "VERIFY A", "after" to "VERIFY UPDATED")) {
                    scenario.onActivity {
                        click(f, "camera_preview_configure")
                        (tag(f, "surface_watermark_text") as EditText).setText(text)
                        click(f, "surface_update_watermark")
                    }
                    await("watermark request $text") {
                        (read(f, "producerWatermark") as? TextWatermarkConfig)?.items?.any { it.id == "demo" && it.text == text } == true
                    }
                    scenario.onActivity { click(f, "camera_preview_return") }
                    var freshFrames = 0
                    var lastFrame = timestamp
                    await("frames continue") {
                        var advanced = false
                        scenario.onActivity {
                            val current = (read(f, "preview") as TextureView).surfaceTexture!!.timestamp
                            if (current > lastFrame) { freshFrames++; lastFrame = current }
                            advanced = freshFrames >= 3
                        }
                        advanced
                    }
                    scenario.onActivity {
                        val preview = read(f, "preview") as TextureView
                        timestamp = preview.surfaceTexture!!.timestamp
                        assertSame(callback, read(f, "producerCallback"))
                        assertSame(session, read(f, "zoomSession"))
                        assertSame(surface, read(f, "producerSurface"))
                        assertEquals(generation, (read(f, "generation") as AtomicLong).get())
                        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "watermark-update/$language-$scale").apply { mkdirs() }
                        File(dir, "$name.png").outputStream().use { output ->
                            assertTrue(preview.getBitmap(1280, 720)!!.compress(Bitmap.CompressFormat.PNG, 100, output))
                        }
                    }
                }
                var sampleText = ""
                scenario.onActivity {
                    click(f, "camera_preview_configure")
                    val buttons = read(f, "buttons") as List<*>
                    val sample = tag(f, "surface_watermark_sample")
                    repeat(buttons.size) {
                        if (buttons[(read(f, "selected") as Int)] !== sample) f.handleGlassKeyEvent(GlassKeyEvent.KEYCODE_FRONT)
                    }
                    assertSame(sample, buttons[read(f, "selected") as Int])
                    assertTrue(f.handleGlassKeyEvent(GlassKeyEvent.KEYCODE_CLICK))
                    sampleText = (tag(f, "surface_watermark_text") as EditText).text.toString()
                    assertTrue(sampleText.isNotEmpty())
                    assertTrue(f.handleGlassKeyEvent(GlassKeyEvent.KEYCODE_FRONT))
                    assertSame(tag(f, "surface_update_watermark"), buttons[read(f, "selected") as Int])
                    assertTrue(f.handleGlassKeyEvent(GlassKeyEvent.KEYCODE_CLICK))
                }
                await("temple gesture updates sample text") {
                    (read(f, "producerWatermark") as? TextWatermarkConfig)?.items?.firstOrNull()?.text == sampleText
                }
                scenario.onActivity {
                    (tag(f, "surface_watermark_text") as EditText).setText("x".repeat(161))
                    click(f, "surface_update_watermark")
                }
                assertEquals(sampleText, (read(f, "producerWatermark") as TextWatermarkConfig).items.first().text)
                // 通过真实页面 finish 退出，避免测试辅助空 Activity 抢前台影响设备策略。
                // Finish the actual page instead of launching a test helper Activity to stop it.
                scenario.onActivity { it.finish() }
                await("actual page destroyed") { scenario.state == Lifecycle.State.DESTROYED }
                await("producer resources released") {
                    read(f, "producerCallback") == null && read(f, "producerSurface") == null &&
                        read(f, "producerService") == null && read(f, "zoomSession") == null &&
                        read(f, "producerWatermark") == null && read(f, "previewStarted") == false
                }
                await("native Surface handle released") { !(surface as android.view.Surface).isValid }

            }
            assertTrue(worker!!.awaitTermination(5, TimeUnit.SECONDS))
        } finally {
            application.unregisterActivityLifecycleCallbacks(overrides)
            instrumentation.runOnMainSync {
                @Suppress("DEPRECATION")
                application.resources.updateConfiguration(originalConfiguration, application.resources.displayMetrics)
            }
            if (initialized) instrumentation.runOnMainSync { GlassSdkUtils.destroySdk() }
        }
    }

    private fun ready(f: SurfaceShareFragment) =
        (read(f, "zoomSession") as? CameraZoomSession)?.state?.ready == true && read(f, "startPending") == false
    private fun tag(f: SurfaceShareFragment, value: String): View = checkNotNull(f.requireView().findViewWithTag(value))
    private fun click(f: SurfaceShareFragment, value: String) { assertTrue(tag(f, value).performClick()) }
    private fun read(target: Any, name: String): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            try { return type.getDeclaredField(name).apply { isAccessible = true }.get(target) }
            catch (_: NoSuchFieldException) { type = type.superclass }
        }
        error("Missing field $name")
    }
    private fun await(message: String, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < until) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue(message, condition())
    }
}
