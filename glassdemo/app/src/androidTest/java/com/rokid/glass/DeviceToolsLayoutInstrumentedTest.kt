package com.rokid.glass

import android.content.res.Configuration
import android.graphics.Rect
import android.os.LocaleList
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rokid.glesse.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * 测量实际设备工具布局的中英文按钮、行距及滚动可达性。
 * Measure actual Device tools controls in both languages for spacing and scroll reachability.
 */
@RunWith(AndroidJUnit4::class)
class DeviceToolsLayoutInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val rowIds = listOf(
        listOf(R.id.btQRCodeImage, R.id.btQRCode, R.id.btQRCodeXml),
        listOf(R.id.btConnectWifi, R.id.btDisconnectWifi, R.id.btConfigureAppVisibility),
        listOf(R.id.btnAiChat, R.id.btnStopAiChat, R.id.btnRegisterLeqi, R.id.btnSendCusEvent),
        listOf(R.id.btnRecordAudio, R.id.btnStopRecordAudio)
    )

    @Test
    fun templeSwipesVisitEveryDeviceActionWithoutSkipping() {
        val ids = rowIds.flatten()
        ActivityScenario.launch(DeviceToolsActivity::class.java).use { scenario ->
            fun expectSelected(index: Int) = scenario.onActivity { activity ->
                ids.forEachIndexed { position, id ->
                    val button = activity.findViewById<View>(id)
                    assertEquals("Selected action at $position", position == index, button.isSelected)
                    assertEquals("Focused action at $position", position == index, button.hasFocus())
                }
            }
            fun swipe(keyCode: Int) {
                SystemClock.sleep(120)
                scenario.onActivity { activity ->
                    activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                    activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                }
            }
            expectSelected(0)
            swipe(KeyEvent.KEYCODE_DPAD_RIGHT)
            expectSelected(1)
            swipe(KeyEvent.KEYCODE_DPAD_DOWN)
            expectSelected(2)
            for (index in 3 until ids.size) {
                swipe(KeyEvent.KEYCODE_DPAD_RIGHT)
                expectSelected(index)
            }
            swipe(KeyEvent.KEYCODE_DPAD_LEFT)
            expectSelected(ids.lastIndex - 1)
        }
    }

    @Test
    fun touchSwipesMoveOneActionAndTapDoesNotNavigate() {
        ActivityScenario.launch(DeviceToolsActivity::class.java).use { scenario ->
            fun gesture(startY: Float, endY: Float) = scenario.onActivity { activity ->
                val downTime = SystemClock.uptimeMillis()
                for ((action, y) in listOf(
                    MotionEvent.ACTION_DOWN to startY,
                    MotionEvent.ACTION_MOVE to (startY + endY) / 2f,
                    MotionEvent.ACTION_UP to endY
                )) {
                    val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 250f, y, 0)
                    activity.dispatchTouchEvent(event)
                    event.recycle()
                }
            }
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.btQRCodeImage).isSelected)
            }
            gesture(100f, 205f)
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.btQRCode).isSelected)
            }
            gesture(205f, 100f)
            scenario.onActivity { activity ->
                val selectedIds = rowIds.flatten().filter { activity.findViewById<View>(it).isSelected }
                assertEquals("Selection after reverse touch swipe", listOf(R.id.btQRCodeImage), selectedIds)
            }
            gesture(15f, 15f)
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.btQRCodeImage).isSelected)
            }
        }
    }

    @Test
    fun actualPageScrollsToStopCapture() {
        ActivityScenario.launch(DeviceToolsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val controls = activity.findViewById<ScrollView>(R.id.controlsScrollView)
                val start = activity.findViewById<TextView>(R.id.btnRecordAudio)
                val stop = activity.findViewById<TextView>(R.id.btnStopRecordAudio)
                assertTrue("Capture and stop must share a row", start.parent === stop.parent)
                stop.requestRectangleOnScreen(Rect(0, 0, stop.width, stop.height), true)
                assertTrue("Stop capture must be visible in the actual page",
                    (stop.parent as View).bottom <= controls.scrollY + controls.height)
            }
        }
    }

    @Test
    fun controlsHaveEvenSpacingAndReachableAudioButtons() {
        val failures = mutableListOf<String>()
        instrumentation.runOnMainSync {
            for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH)) {
                for (scale in listOf(1f, 1.3f)) {
                    val name = "${locale.toLanguageTag()}-$scale"
                    val configuration = Configuration(instrumentation.targetContext.resources.configuration).apply {
                        setLocales(LocaleList(locale))
                        fontScale = scale
                        densityDpi = 240
                        screenWidthDp = 320
                        screenHeightDp = 426
                    }
                    val context = ContextThemeWrapper(
                        instrumentation.targetContext.createConfigurationContext(configuration),
                        R.style.Theme_Glessedemo
                    )
                    val root = LayoutInflater.from(context).inflate(R.layout.activity_device_tools, null) as ViewGroup
                    root.measure(
                        View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY)
                    )
                    root.layout(0, 0, 480, 640)
                    val controls = root.findViewById<ScrollView>(R.id.controlsScrollView)
                    val log = root.findViewById<ScrollView>(R.id.scrollView)
                    check(failures, "$name: log viewport shorter than half the screen") { log.height >= 320 }
                    check(failures, "$name: controls overlap log") { controls.bottom <= log.top }
                    val rows = rowIds.map { ids -> ids.map { root.findViewById<TextView>(it) } }
                    val rowViews = rows.map { it.first().parent as View }
                    val gaps = rowViews.zipWithNext().map { (upper, lower) -> lower.top - upper.bottom }
                    check(failures, "$name: uneven row gaps $gaps") {
                        gaps.all { it > 0 } && gaps.max() - gaps.min() <= 1
                    }
                    rows.forEachIndexed { index, buttons ->
                        val row = rowViews[index]
                        buttons.forEach { button ->
                            check(failures, "$name: ${button.text} outside row or too short") {
                                button.top >= 0 && button.bottom <= row.height &&
                                    button.height >= button.lineCount * button.lineHeight
                            }
                        }
                        buttons.zipWithNext().forEach { (left, right) ->
                            check(failures, "$name: adjacent buttons overlap") { left.right < right.left }
                        }
                    }
                    val last = root.findViewById<TextView>(R.id.btnStopRecordAudio)
                    last.requestRectangleOnScreen(Rect(0, 0, last.width, last.height), true)
                    val visibleBottom = controls.scrollY + controls.height - controls.paddingBottom
                    val lastBottom = rowViews.last().bottom
                    check(failures, "$name: stop capture is unreachable") { lastBottom <= visibleBottom }
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun check(failures: MutableList<String>, message: String, condition: () -> Boolean) {
        if (!condition()) failures += message
    }
}
