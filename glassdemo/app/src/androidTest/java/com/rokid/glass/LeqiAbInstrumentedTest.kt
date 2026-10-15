package com.rokid.glass

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.LocaleList
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rokid.glass.base.GlassKeyEvent
import com.rokid.glesse.R
import com.rokid.security.glass3.open.sdk.callback.LeqiInterceptor
import com.rokid.security.system.server.device.listener.DeviceEventListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Checks the Demo and the bundled SDK's actual ownership handling. Events marked SYNTHETIC
 * enter the SDK listener from a worker thread; they do not verify microphone recognition or
 * delivery from the glasses' device service.
 */
@RunWith(AndroidJUnit4::class)
class LeqiAbInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val buttonIds = listOf(
        R.id.btn_register_a, R.id.btn_register_b,
        R.id.btn_unregister_a, R.id.btn_unregister_b
    )
    private val managerClass by lazy {
        Class.forName("com.rokid.security.glass3.open.sdk.client.LeqiInterceptorManager")
    }
    private val manager by lazy { managerClass.getField("INSTANCE").get(null) }

    @Test
    fun replacingAWithBThenRemovingAStillDeliversOnlyToB() {
        launchForeground().use { scenario ->
            assertNull("Opening the page must not auto-register an interceptor", currentInterceptor())
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.btn_register_a).performClick()
                assertSame(ownedInterceptor(activity, "interceptorA"), currentInterceptor())
            }
            dispatchSynthetic(11000, "SYNTHETIC_LEQI_A")
            verifyCallbacks(scenario, 1, 0, "A" to "SYNTHETIC_LEQI_A")

            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.btn_register_b).performClick()
                assertSame(ownedInterceptor(activity, "interceptorB"), currentInterceptor())
            }
            dispatchSynthetic(11000, "SYNTHETIC_LEQI_B")
            verifyCallbacks(scenario, 1, 1, "B" to "SYNTHETIC_LEQI_B")

            scenario.onActivity { activity ->
                // This click must reach unregister(A) even though the current owner is B.
                activity.findViewById<View>(R.id.btn_unregister_a).performClick()
                assertSame("Removing A must preserve B", ownedInterceptor(activity, "interceptorB"), currentInterceptor())
            }
            dispatchSynthetic(11000, "SYNTHETIC_LEQI_B_AFTER_REMOVE_A")
            verifyCallbacks(scenario, 1, 2, "B" to "SYNTHETIC_LEQI_B_AFTER_REMOVE_A")

            dispatchSynthetic(-1234567, "SYNTHETIC_LEQI_UNRELATED_EVENT")
            verifyCallbacks(scenario, 1, 2)
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.btn_unregister_b).performClick()
                assertNull("Removing B must clear its registration", currentInterceptor())
            }
            dispatchSynthetic(11000, "SYNTHETIC_LEQI_AFTER_REMOVE_B")
            verifyCallbacks(scenario, 1, 2)
            scenario.onActivity { activity ->
                val logs = activity.findViewById<TextView>(R.id.tv_log).text.toString()
                assertFalse(logs.contains("SYNTHETIC_LEQI_UNRELATED_EVENT"))
                assertFalse(logs.contains("SYNTHETIC_LEQI_AFTER_REMOVE_B"))
            }
        }
        assertNull("Closing the test page must clean up its registration", currentInterceptor())
    }

    @Test
    fun pausingCleansUpBothOwnersAndReturningDoesNotRegisterAgain() {
        launchForeground().use { scenario ->
            for (id in listOf(R.id.btn_register_a, R.id.btn_register_b)) {
                scenario.onActivity { it.findViewById<View>(id).performClick() }
                assertTrue("Button must register an interceptor", currentInterceptor() != null)
                scenario.moveToState(Lifecycle.State.CREATED)
                assertNull("Leaving the page must clear its owned interceptor", currentInterceptor())
                scenario.moveToState(Lifecycle.State.RESUMED)
                assertNull("Returning must leave the manual test in its initial unregistered state", currentInterceptor())
            }
        }
    }

    @Test
    fun glassesNavigationVisitsAllFourOperationsAndWrapsInBothDirections() {
        launchForeground().use { scenario ->
            scenario.onActivity { activity ->
                fun click() = activity.onGlassKeyEvent(GlassKeyEvent.KEYCODE_CLICK)
                fun next() = activity.onGlassKeyEvent(GlassKeyEvent.KEYCODE_FRONT)
                fun previous() = activity.onGlassKeyEvent(GlassKeyEvent.KEYCODE_BEHIND)
                val a = ownedInterceptor(activity, "interceptorA")
                val b = ownedInterceptor(activity, "interceptorB")
                click()
                assertSame(a, currentInterceptor())
                next(); click()
                assertSame(b, currentInterceptor())
                next(); click()
                assertSame("Third operation removes A, retaining B", b, currentInterceptor())
                next(); click()
                assertNull(currentInterceptor())
                next(); click()
                assertSame("Forward wrap returns to register A", a, currentInterceptor())
                previous(); click()
                assertSame("Backward wrap removes B, retaining A", a, currentInterceptor())
                previous(); click()
                assertNull(currentInterceptor())
            }
        }
    }

    @Test
    fun enterActsOnTheSelectedButtonBeforeAnImmediateNavigationKey() {
        launchForeground().use { scenario ->
            scenario.onActivity { activity ->
                fun press(keyCode: Int) {
                    assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode)))
                    assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode)))
                }
                val a = ownedInterceptor(activity, "interceptorA")
                val b = ownedInterceptor(activity, "interceptorB")
                // Keep all events in the same main-thread turn: a delayed click must fail.
                press(KeyEvent.KEYCODE_ENTER)
                press(KeyEvent.KEYCODE_DPAD_RIGHT)
                assertSame("Enter must register A before navigation selects B", a, currentInterceptor())
                assertTrue("Navigation must highlight B", activity.findViewById<View>(R.id.btn_register_b).isSelected)
                assertFalse("Navigation must deselect A", activity.findViewById<View>(R.id.btn_register_a).isSelected)

                press(KeyEvent.KEYCODE_ENTER)
                assertSame("The next Enter must immediately register B", b, currentInterceptor())
                val operations = activity.findViewById<TextView>(R.id.tv_log).text.toString().lineSequence().toList()
                assertEquals("Enter down/up must register A once", 1, operations.count { it.contains(" register(A)]") })
                assertEquals("Enter down/up must register B once", 1, operations.count { it.contains(" register(B)]") })
            }
        }
    }

    @Test
    fun aSinglePointerTapRegistersAOnlyOnce() {
        launchForeground().use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val button = activity.findViewById<View>(R.id.btn_register_a)
                val location = IntArray(2).also(button::getLocationInWindow)
                val x = location[0] + button.width / 2f
                val y = location[1] + button.height / 2f
                val downAt = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0)
                val up = MotionEvent.obtain(downAt, downAt + 60, MotionEvent.ACTION_UP, x, y, 0)
                try {
                    activity.dispatchTouchEvent(down)
                    activity.dispatchTouchEvent(up)
                } finally {
                    down.recycle()
                    up.recycle()
                }
            }
            // BaseGlassActivity's ring click waits 500 ms. A pointer tap must not also
            // schedule that second route to the selected button.
            SystemClock.sleep(650)
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertSame(ownedInterceptor(activity, "interceptorA"), currentInterceptor())
                val operationCount = activity.findViewById<TextView>(R.id.tv_log).text.toString()
                    .lineSequence().count { it.contains(" register(A)]") }
                assertEquals("One pointer tap must produce exactly one register(A) operation", 1, operationCount)
            }
        }
    }

    @Test
    fun controlsAndLogsRemainReadableAndReachableAt480By640InBothLanguages() {
        instrumentation.runOnMainSync {
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "leqi-ab-layout-regression")
                .apply { mkdirs() }
            val configurations = listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH).flatMap { locale ->
                listOf(1f, 1.3f).map { scale -> locale to scale }
            }
            for ((locale, scale) in configurations) {
                val label = "${locale.toLanguageTag()}-$scale"
                val configuration = Configuration(instrumentation.targetContext.resources.configuration).apply {
                    setLocales(LocaleList(locale))
                    fontScale = scale
                    densityDpi = 240
                    orientation = Configuration.ORIENTATION_PORTRAIT
                    screenWidthDp = 320
                    screenHeightDp = 426
                    smallestScreenWidthDp = 320
                }
                val context = ContextThemeWrapper(
                    instrumentation.targetContext.createConfigurationContext(configuration),
                    R.style.Theme_Glessedemo
                )
                val root = LayoutInflater.from(context).inflate(R.layout.activity_test_leqi, null) as ViewGroup
                root.findViewById<TextView>(R.id.tv_interceptor_status).text = context.getString(R.string.leqi_ab_expected, "B")
                root.findViewById<TextView>(R.id.tv_callback_counts).text = context.getString(R.string.leqi_ab_counts, 123, 456)
                root.findViewById<TextView>(R.id.tv_foreground_status).setText(R.string.demo_foreground_yes)
                val log = root.findViewById<TextView>(R.id.tv_log)
                log.text = (1..50).joinToString("\n") { "$it. SYNTHETIC layout log A/B" }
                root.measure(
                    View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY)
                )
                root.layout(0, 0, 480, 640)
                saveScreenshot(root, File(directory, "$label-top.png"))
                buttonIds.forEach { id ->
                    val button = root.findViewById<TextView>(id)
                    assertTrue("$label: button must be visible and enabled", button.visibility == View.VISIBLE && button.isEnabled)
                    val textLayout = button.layout
                    assertTrue("$label: button text is vertically clipped: ${button.text}",
                        textLayout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                    assertEquals("$label: button label was truncated", button.text.length,
                        textLayout.getLineEnd(textLayout.lineCount - 1))
                    for (line in 0 until textLayout.lineCount) {
                        assertEquals("$label: button text must not be ellipsized", 0, textLayout.getEllipsisCount(line))
                        assertTrue("$label: button text is horizontally clipped",
                            textLayout.getLineLeft(line) >= -1 && textLayout.getLineRight(line) <= textLayout.width + 1)
                    }
                    button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true)
                    assertWithinViewport(button, root, label)
                }
                val scroll = ancestorScroll(log)
                assertTrue("$label: log must have a usable scrolling viewport", scroll != null && scroll.height > 0)
                scroll!!.scrollTo(0, scroll.getChildAt(0).height)
                assertTrue("$label: long callback logs must scroll", scroll.scrollY > 0)
                assertFalse("$label: last log line must be reachable", scroll.canScrollVertically(1))
                assertWithinViewport(scroll, root, label)
                saveScreenshot(root, File(directory, "$label-bottom.png"))
            }
        }
    }

    private fun launchForeground(): ActivityScenario<TestLeqiActivity> {
        val scenario = ActivityScenario.launch(TestLeqiActivity::class.java)
        try {
            val message = "Test precondition: glasses must be awake/unlocked and the page RESUMED; wake the device and retry"
            assertEquals(message, Lifecycle.State.RESUMED, scenario.state)
            scenario.onActivity { activity ->
                assertEquals(message, Lifecycle.State.RESUMED, activity.lifecycle.currentState)
            }
            return scenario
        } catch (error: Throwable) {
            runCatching { scenario.close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    private fun currentInterceptor(): Any? = managerClass.getMethod("getInterceptor").invoke(manager)

    private fun ownedInterceptor(activity: TestLeqiActivity, name: String): LeqiInterceptor =
        TestLeqiActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
            .get(activity) as LeqiInterceptor

    private fun dispatchSynthetic(code: Int, marker: String) {
        val listener = managerClass.getDeclaredField("sdkDeviceEventListener").apply { isAccessible = true }
            .get(manager) as DeviceEventListener
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit {
                assertTrue("Synthetic events must exercise non-main callback delivery", Looper.myLooper() != Looper.getMainLooper())
                listener.onDeviceEvent(code, marker)
            }.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
        instrumentation.waitForIdleSync()
    }

    private fun verifyCallbacks(scenario: ActivityScenario<TestLeqiActivity>, a: Int, b: Int, vararg expected: Pair<String, String>) {
        scenario.onActivity { activity ->
            val counts = activity.findViewById<TextView>(R.id.tv_callback_counts).text.toString()
            for ((name, count) in listOf("A" to a, "B" to b)) {
                val actual = Regex("$name\\s*[:=：]\\s*(\\d+)").find(counts)?.groupValues?.get(1)?.toInt()
                assertEquals("$name callback count in '$counts'", count, actual)
            }
            val logs = activity.findViewById<TextView>(R.id.tv_log).text.toString().lineSequence().toList()
            for ((name, marker) in expected) {
                val matching = logs.filter { it.contains("extra=$marker") }
                assertEquals("Each synthetic callback must appear once: $marker", 1, matching.size)
                assertTrue("Callback log must identify interceptor $name: ${matching.single()}",
                    Regex("(?:^|[^A-Za-z])$name(?:[^A-Za-z]|$)").containsMatchIn(matching.single().substringBefore("extra=")))
            }
        }
    }

    private fun ancestorScroll(view: View): ScrollView? {
        var parent = view.parent
        while (parent is View) {
            if (parent is ScrollView) return parent
            parent = parent.parent
        }
        return null
    }

    private fun bounds(view: View, root: View): RectF {
        var left = view.left.toFloat()
        var top = view.top.toFloat()
        var parent = view.parent
        while (parent is View && parent !== root) {
            left += parent.left - parent.scrollX
            top += parent.top - parent.scrollY
            parent = parent.parent
        }
        if (parent === root) {
            left -= root.scrollX
            top -= root.scrollY
        }
        return RectF(left, top, left + view.width, top + view.height)
    }

    private fun assertWithinViewport(view: View, root: View, label: String) {
        val actual = bounds(view, root)
        assertTrue("$label: control extends off-screen: $actual", contains(RectF(0f, 0f, 480f, 640f), actual))
        var parent = view.parent
        while (parent is View && parent !== root) {
            if (parent is ScrollView) {
                val scrollParent = parent
                val viewport = bounds(scrollParent, root).apply {
                    left += scrollParent.paddingLeft
                    top += scrollParent.paddingTop
                    right -= scrollParent.paddingRight
                    bottom -= scrollParent.paddingBottom
                }
                assertTrue("$label: control is clipped by its scrolling viewport: $actual / $viewport", contains(viewport, actual))
            }
            parent = parent.parent
        }
    }

    private fun contains(outer: RectF, inner: RectF): Boolean =
        inner.left >= outer.left - 1 && inner.top >= outer.top - 1 &&
            inner.right <= outer.right + 1 && inner.bottom <= outer.bottom + 1

    private fun saveScreenshot(view: View, file: File) {
        val bitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
