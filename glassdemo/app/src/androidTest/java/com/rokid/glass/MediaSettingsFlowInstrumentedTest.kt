package com.rokid.glass

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.View
import android.view.KeyEvent
import android.widget.TextView
import android.widget.CheckedTextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rokid.glass.base.GlassKeyEvent
import com.rokid.glass.camera.CameraMediaController
import com.rokid.glass.camera.CaptureSize
import com.rokid.glass.camera.MediaSettingsFragment
import com.rokid.glass.camera.MediaSettingsTarget
import com.rokid.glass.utils.GlassSdkUtils
import com.rokid.glesse.R
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.camera.CameraShareHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 验证页面与设备分辨率选择，不启动拍照、录像、传输、采音或 AI。
 * Verify settings and camera-reported sizes without starting capture, transmission or AI.
 */
@RunWith(AndroidJUnit4::class)
class MediaSettingsFlowInstrumentedTest {
    @Test fun capturePageHasOnePhotoActionAndSeparateSettings() {
        ActivityScenario.launch(SdkMediaActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                fun control(name: String): View? = activity.findViewById(
                    activity.resources.getIdentifier(name, "id", activity.packageName))
                assertNotNull("One photo action must replace the resolution buttons", control("btPhoto"))
                assertNotNull(control("btStartRecord"))
                assertNotNull(control("btCameraShare"))
                assertNotNull(control("btnPhotoSettings"))
                assertNotNull(control("btnVideoSettings"))
            }
        }
    }

    @Test fun templeSwipesVisitEveryMediaActionWithoutSkipping() {
        val buttons = listOf(R.id.btPhoto, R.id.btnPhotoSettings, R.id.btStartRecord,
            R.id.btStopRecord, R.id.btnVideoSettings, R.id.btCameraShare)
        ActivityScenario.launch(SdkMediaActivity::class.java).use { scenario ->
            fun expectSelected(index: Int) = scenario.onActivity { activity ->
                buttons.forEachIndexed { position, id ->
                    val button = activity.findViewById<View>(id)
                    assertEquals("Selected action at $position", position == index, button.isSelected)
                    assertEquals("Focused action at $position", position == index, button.hasFocus())
                }
            }
            fun swipe(keyCode: Int) = scenario.onActivity { activity ->
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            }
            expectSelected(0)
            swipe(KeyEvent.KEYCODE_DPAD_RIGHT)
            swipe(KeyEvent.KEYCODE_DPAD_DOWN)
            expectSelected(1)
            for (index in 2 until buttons.size) {
                SystemClock.sleep(120)
                swipe(KeyEvent.KEYCODE_DPAD_RIGHT)
                expectSelected(index)
            }
            SystemClock.sleep(120)
            swipe(KeyEvent.KEYCODE_DPAD_LEFT)
            expectSelected(4)
        }
    }

    @Test fun cameraSizesPersistAndOpeningSettingsKeepsTheMediaPageActive() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("media_capture_settings", Context.MODE_PRIVATE)
        val saved = prefs.all
        prefs.edit().clear().commit()
        var initializedSdkForTest = false
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                if (!GlassSdk.isReady()) {
                    initializedSdkForTest = true
                    GlassSdkUtils.initSdk()
                }
            }
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (GlassSdk.getGlassMediaService() == null && SystemClock.uptimeMillis() < deadline)
                SystemClock.sleep(100)
            assertNotNull("SDK media service must connect before querying camera sizes", GlassSdk.getGlassMediaService())
            ActivityScenario.launch(SdkMediaActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(CameraMediaController.Api.OPTIONS_V2, activity.captureSettings.photoApi)
                    activity.findViewById<View>(R.id.btnPhotoSettings).performClick()
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.mediaMainPage).visibility)
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.mediaSettingsHost).visibility)
                    val controller = SdkMediaActivity::class.java.getDeclaredField("optionsController").apply { isAccessible = true }.get(activity)
                    assertEquals(true, controller.javaClass.getDeclaredField("active").apply { isAccessible = true }.get(controller))
                }
                val photoSize = chooseCameraSize(scenario, last = false)
                scenario.onActivity { activity ->
                    assertFalse(activity.findViewById<View>(R.id.btnSetZoom).isShown)
                    assertFalse(activity.findViewById<View>(R.id.btnMediaRecordWatermark).isShown)
                    activity.onGlassKeyEvent(GlassKeyEvent.KEYCODE_BACK)
                    activity.findViewById<View>(R.id.btnVideoSettings).performClick()
                    assertFalse(activity.findViewById<View>(R.id.btnMediaPhotoQuality).isShown)
                    assertFalse(activity.findViewById<View>(R.id.btnMediaReuseResolution).isShown)
                }
                val videoSize = chooseCameraSize(scenario, last = true)
                scenario.onActivity { activity ->
                    activity.onGlassKeyEvent(GlassKeyEvent.KEYCODE_BACK)
                    activity.findViewById<View>(R.id.btnPhotoSettings).performClick()
                }
                scenario.onActivity { activity ->
                    activity.findViewById<View>(R.id.btnMediaOptionsZoom).performClick()
                    val dialog = checkNotNull(choiceDialog(activity))
                    repeat(2) { dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)) }
                    dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    assertEquals(1.575f, activity.captureSettings.photoZoom)
                    assertEquals(1f, activity.captureSettings.videoZoom)
                    activity.onGlassKeyEvent(GlassKeyEvent.KEYCODE_BACK)
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.mediaMainPage).visibility)
                    assertTrue(activity.findViewById<TextView>(R.id.tvPhotoSummary).text.contains(photoSize.toString()))
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals(photoSize, activity.captureSettings.photo)
                    assertEquals(videoSize, activity.captureSettings.video)
                    assertEquals(1.575f, activity.captureSettings.photoZoom)
                    assertEquals(1f, activity.captureSettings.videoZoom)
                    activity.findViewById<View>(R.id.btnPhotoSettings).performClick()
                }
                // A recreated settings page must remain a separate page and return correctly.
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.mediaSettingsHost).visibility)
                    assertEquals(MediaSettingsTarget.PHOTO,
                        (activity.supportFragmentManager.findFragmentByTag("media-settings") as MediaSettingsFragment).target)
                    activity.onGlassKeyEvent(GlassKeyEvent.KEYCODE_BACK)
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.mediaMainPage).visibility)
                }
            }
        } finally {
            if (initializedSdkForTest) InstrumentationRegistry.getInstrumentation().runOnMainSync {
                GlassSdkUtils.destroySdk()
            }
            prefs.edit().clear().apply {
                saved.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Float -> putFloat(key, value)
                    is Boolean -> putBoolean(key, value)
                } }
            }.commit()
        }
    }

    private fun choiceDialog(activity: SdkMediaActivity): AlertDialog? {
        val page = activity.supportFragmentManager.findFragmentByTag("media-settings") as MediaSettingsFragment
        return page.javaClass.getDeclaredField("choiceDialog").apply { isAccessible = true }.get(page) as? AlertDialog
    }

    private fun chooseCameraSize(scenario: ActivityScenario<SdkMediaActivity>, last: Boolean): CaptureSize {
        scenario.onActivity { activity ->
            activity.findViewById<View>(R.id.btnMediaSize).performClick()
        }
        var ready = false
        for (attempt in 0 until 50) {
            scenario.onActivity { activity -> ready = choiceDialog(activity)?.isShowing == true }
            if (ready) break
            Thread.sleep(100)
        }
        assertTrue("Camera size query should open the chooser", ready)
        val expectedLabels = CameraShareHelper().getSupportedPreviewSizes()
            .map { "${it.first}×${it.second}" }.distinct()
        lateinit var chosen: CaptureSize
        scenario.onActivity { activity ->
            val dialog = checkNotNull(choiceDialog(activity))
            val list = dialog.listView
            val labels = (0 until list.adapter.count).map { list.adapter.getItem(it).toString() }
            assertTrue(labels.all { it.matches(Regex("[0-9]+×[0-9]+")) })
            assertTrue("Camera must report at least one usable size", labels.isNotEmpty())
            assertEquals("Every camera-reported size must be selectable", expectedLabels, labels)
            assertEquals(Color.BLACK, (checkNotNull(dialog.window?.decorView?.background) as ColorDrawable).color)
            assertEquals(Color.WHITE, (dialog.listView.adapter.getView(0, null, list) as CheckedTextView).currentTextColor)
            assertEquals(Color.WHITE, dialog.getButton(AlertDialog.BUTTON_NEGATIVE).currentTextColor)
            val index = if (last) labels.lastIndex else 0
            chosen = CaptureSize(labels[index].substringBefore('×').toInt(), labels[index].substringAfter('×').toInt())
            if (last) {
                val start = list.checkedItemPosition.coerceAtLeast(0)
                repeat((index - start + labels.size + 1) % (labels.size + 1)) {
                    dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
                }
                assertEquals(index, list.checkedItemPosition)
                dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            } else list.performItemClick(list.adapter.getView(index, null, list), index, index.toLong())
            assertFalse(dialog.isShowing)
            assertEquals(chosen, if (last) activity.captureSettings.video else activity.captureSettings.photo)
        }
        return chosen
    }
}
