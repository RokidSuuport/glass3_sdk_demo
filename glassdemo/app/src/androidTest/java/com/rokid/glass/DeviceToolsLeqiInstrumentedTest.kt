package com.rokid.glass

import android.os.Looper
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rokid.glass.base.GlassKeyEvent
import com.rokid.glesse.R
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.callback.LeqiInterceptor
import com.rokid.security.system.server.device.listener.DeviceEventListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 使用合成事件验证 SDK 的拦截器管理，不代表真实麦克风或服务投递。
 * Verify SDK interceptor management with synthetic events, not microphone/service delivery.
 */
@RunWith(AndroidJUnit4::class)
class DeviceToolsLeqiInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val managerClass by lazy {
        Class.forName("com.rokid.security.glass3.open.sdk.client.LeqiInterceptorManager")
    }
    private val manager by lazy { managerClass.getField("INSTANCE").get(null) }

    @Test
    fun glassesActionRegistersReceivesAndNavigationReachesAudioControls() {
        launchForeground().use { scenario ->
            assertNull("Opening the Device tools page must not register automatically", currentInterceptor())
            scenario.onActivity { activity ->
                select(activity, R.id.btnRegisterLeqi)
                click(activity)
                assertSame(ownedInterceptor(activity), currentInterceptor())
                // 注册后镜腿继续导航到音频采集，注销由页面生命周期负责。
                // Glasses navigation reaches audio capture after registration; cleanup belongs to the page lifecycle.
                activity.onGlassKeyEvent(GlassKeyEvent.KEYCODE_FRONT)
                assertEquals(R.id.btnRecordAudio, selectedId(activity))
                activity.onGlassKeyEvent(GlassKeyEvent.KEYCODE_FRONT)
                assertEquals(R.id.btnStopRecordAudio, selectedId(activity))
                activity.onGlassKeyEvent(GlassKeyEvent.KEYCODE_FRONT)
                assertEquals(R.id.btQRCodeImage, selectedId(activity))
                activity.onGlassKeyEvent(GlassKeyEvent.KEYCODE_BEHIND)
                assertEquals(R.id.btnStopRecordAudio, selectedId(activity))
                activity.onGlassKeyEvent(GlassKeyEvent.KEYCODE_BEHIND)
                activity.onGlassKeyEvent(GlassKeyEvent.KEYCODE_BEHIND)
                assertEquals(R.id.btnRegisterLeqi, selectedId(activity))
            }
            dispatchSynthetic(11000, "SYNTHETIC_AI_LEQI")
            scenario.onActivity { activity ->
                assertEquals(1, callbackCount(activity))
                val log = activity.findViewById<TextView>(R.id.tvLog).text.toString()
                assertEquals("The worker callback must appear once in the page log", 1,
                    log.lineSequence().count { it.contains("SYNTHETIC_AI_LEQI") })
                // 重复注册复用同一对象，不创建新所有者。
                // Re-registration reuses the original object rather than creating a new owner.
                click(activity)
                assertSame(ownedInterceptor(activity), currentInterceptor())
            }
            dispatchSynthetic(-1234567, "SYNTHETIC_AI_UNRELATED")
            scenario.onActivity { assertEquals(1, callbackCount(it)) }
        }
        assertNull("Closing the Device tools page must clear its owned registration", currentInterceptor())
    }

    @Test
    fun leavingClearsOwnedInterceptorAndReturningDoesNotRegisterAgain() {
        launchForeground().use { scenario ->
            lateinit var pausedActivity: DeviceToolsActivity
            scenario.onActivity { activity ->
                pausedActivity = activity
                select(activity, R.id.btnRegisterLeqi)
                click(activity)
                assertSame(ownedInterceptor(activity), currentInterceptor())
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            assertNull("onPause must clear the page's interceptor", currentInterceptor())
            // BaseActivity 的延迟点击可能在 onPause 之后到达。
            // BaseActivity can deliver its delayed touch click after onPause.
            instrumentation.runOnMainSync {
                assertEquals(R.id.btnRegisterLeqi, selectedId(pausedActivity))
                click(pausedActivity)
                assertNull("A delayed click must not register an inactive page again", currentInterceptor())
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertNull("Returning must require an explicit register action", currentInterceptor())
        }
    }

    @Test
    fun pausePreservesANewerOwner() {
        val otherCount = AtomicInteger()
        val other = object : LeqiInterceptor {
            override fun onLeqiInstruction(extra: String?): Boolean {
                otherCount.incrementAndGet()
                return true
            }
        }
        try {
            launchForeground().use { scenario ->
                scenario.onActivity { activity ->
                    select(activity, R.id.btnRegisterLeqi)
                    click(activity)
                    GlassSdk.registerLeqiInterceptor(activity, other)
                    assertSame(other, currentInterceptor())
                }
                dispatchSynthetic(11000, "SYNTHETIC_REPLACEMENT_BEFORE_PAUSE")
                assertEquals(1, otherCount.get())
                scenario.onActivity { activity ->
                    assertEquals(0, callbackCount(activity))
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                assertSame("onPause must not globally clear another owner's registration", other, currentInterceptor())
                scenario.moveToState(Lifecycle.State.RESUMED)
                assertSame("Returning must not replace the newer owner", other, currentInterceptor())
                dispatchSynthetic(11000, "SYNTHETIC_REPLACEMENT_AFTER_PAUSE")
                assertEquals(2, otherCount.get())
            }
            assertSame("Destroying the old page must preserve its replacement", other, currentInterceptor())
        } finally {
            instrumentation.runOnMainSync { GlassSdk.unregisterLeqiInterceptor(other) }
        }
    }

    private fun select(activity: DeviceToolsActivity, id: Int) {
        repeat(40) {
            if (selectedId(activity) == id) return
            activity.onGlassKeyEvent(GlassKeyEvent.KEYCODE_FRONT)
        }
        throw AssertionError("Button $id is not reachable through the glasses menu")
    }

    private fun click(activity: DeviceToolsActivity) {
        activity.onGlassKeyEvent(GlassKeyEvent.KEYCODE_CLICK)
    }

    private fun field(activity: DeviceToolsActivity, name: String): Any =
        DeviceToolsActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)!!

    private fun selectedId(activity: DeviceToolsActivity): Int {
        val buttons = field(activity, "buttons") as List<*>
        return (buttons[field(activity, "selected") as Int] as TextView).id
    }
    private fun ownedInterceptor(activity: DeviceToolsActivity): LeqiInterceptor =
        field(activity, "leqiInterceptor") as LeqiInterceptor
    private fun callbackCount(activity: DeviceToolsActivity): Int =
        (field(activity, "leqiCallbackCount") as AtomicInteger).get()
    private fun currentInterceptor(): Any? = managerClass.getMethod("getInterceptor").invoke(manager)

    private fun dispatchSynthetic(code: Int, marker: String) {
        val listener = managerClass.getDeclaredField("sdkDeviceEventListener").apply { isAccessible = true }
            .get(manager) as DeviceEventListener
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit {
                assertTrue("Exercise the non-main callback path", Looper.myLooper() != Looper.getMainLooper())
                listener.onDeviceEvent(code, marker)
            }.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
        instrumentation.waitForIdleSync()
    }

    private fun launchForeground(): ActivityScenario<DeviceToolsActivity> {
        val scenario = ActivityScenario.launch(DeviceToolsActivity::class.java)
        try {
            val message = "Test precondition: wake/unlock the glasses and keep the Device tools page RESUMED"
            assertEquals(message, Lifecycle.State.RESUMED, scenario.state)
            scenario.onActivity { assertEquals(message, Lifecycle.State.RESUMED, it.lifecycle.currentState) }
            return scenario
        } catch (error: Throwable) {
            runCatching { scenario.close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }
}
