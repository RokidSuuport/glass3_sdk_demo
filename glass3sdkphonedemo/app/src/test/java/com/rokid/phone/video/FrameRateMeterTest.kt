package com.rokid.phone.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRateMeterTest {
    @Test
    fun `calculates frames per actual elapsed second`() {
        val meter = FrameRateMeter()

        assertNull(meter.recordFrame(0L))
        repeat(9) { index ->
            assertNull(meter.recordFrame((index + 1) * 100L))
        }

        assertEquals(10.0f, meter.recordFrame(1_000L)!!, 0.01f)
    }

    @Test
    fun `reset discards the previous sampling window`() {
        val meter = FrameRateMeter(samplePeriodMs = 200L)

        meter.recordFrame(0L)
        meter.recordFrame(500L)
        meter.reset()

        assertNull(meter.recordFrame(2_000L))
        assertEquals(5.0f, meter.recordFrame(2_200L)!!, 0.01f)
    }
}
