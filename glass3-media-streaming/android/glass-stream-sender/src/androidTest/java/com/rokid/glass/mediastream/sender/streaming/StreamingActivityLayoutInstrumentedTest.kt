package com.rokid.glass.mediastream.sender.streaming

import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rokid.glass.mediastream.sender.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamingActivityLayoutInstrumentedTest {
    @Test
    fun start_action_is_fully_visible_on_one_line() {
        ActivityScenario.launch(StreamingActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            scenario.onActivity { activity ->
                val startButton = activity.findViewById<Button>(R.id.startButton)

                assertEquals(1, startButton.lineCount)
                assertFalse(startButton.layout?.getEllipsisCount(0)?.let { it > 0 } ?: true)
            }
        }
    }
}
