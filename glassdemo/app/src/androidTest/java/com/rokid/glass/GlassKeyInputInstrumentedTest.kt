package com.rokid.glass

import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import com.rokid.glass.speech.privateservice.PrivateSpeechActivity
import com.rokid.glesse.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlassKeyInputInstrumentedTest {
    @Test
    fun glassesPagesShareSingleStepNavigationAndDebounce() {
        fun press(activity: android.app.Activity, keyCode: Int) {
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }

        ActivityScenario.launch(SendMessageActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val first = activity.findViewById<android.view.View>(R.id.btnTtsQueueMode)
                val second = activity.findViewById<android.view.View>(R.id.btnOnlineAsr)
                assertTrue(first.isSelected)
                press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
                assertTrue(second.isSelected)
                press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
                assertTrue(second.isSelected)
                assertFalse(first.isSelected)
            }
        }

        SystemClock.sleep(120)
        ActivityScenario.launch(SdkMediaActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val first = activity.findViewById<android.view.View>(R.id.btPhoto)
                val second = activity.findViewById<android.view.View>(R.id.btnPhotoSettings)
                assertTrue(first.isSelected)
                press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
                assertTrue(second.isSelected)
                press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
                assertTrue(second.isSelected)
            }
        }

        SystemClock.sleep(120)
        ActivityScenario.launch(DeviceToolsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val first = activity.findViewById<android.view.View>(R.id.btQRCodeImage)
                val second = activity.findViewById<android.view.View>(R.id.btQRCode)
                assertTrue(first.isSelected)
                press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
                assertTrue(second.isSelected)
                press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
                assertTrue(second.isSelected)
            }
        }
    }

    @Test
    fun baseActivityMovesPrivateSpeechSelectionOneItemPerSwipe() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(PrivateSpeechActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(0, activity.findViewById<ViewPager2>(R.id.pager_private_speech).currentItem)
            }

            fun swipe(keyCode: Int) {
                SystemClock.sleep(120)
                scenario.onActivity { activity ->
                    activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                    activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                }
                instrumentation.waitForIdleSync()
            }

            swipe(KeyEvent.KEYCODE_DPAD_RIGHT)
            scenario.onActivity { activity ->
                assertEquals(0, activity.findViewById<ViewPager2>(R.id.pager_private_speech).currentItem)
            }
            swipe(KeyEvent.KEYCODE_DPAD_RIGHT)
            scenario.onActivity { activity ->
                assertEquals(1, activity.findViewById<ViewPager2>(R.id.pager_private_speech).currentItem)
            }
            swipe(KeyEvent.KEYCODE_DPAD_LEFT)
            scenario.onActivity { activity ->
                assertEquals(0, activity.findViewById<ViewPager2>(R.id.pager_private_speech).currentItem)
            }
        }
    }
}
