package com.rokid.phone.video

import org.junit.Assert.assertEquals
import org.junit.Test

class FitCenterScaleCalculatorTest {
    @Test
    fun `4 by 3 frame is fully shown with side bars on 16 by 9 surface`() {
        val scale = FitCenterScaleCalculator.calculate(2400, 1800, 1920, 1080)

        assertEquals(0.75f, scale.x, 0.0001f)
        assertEquals(1f, scale.y, 0.0001f)
    }

    @Test
    fun `16 by 9 frame fills 16 by 9 surface`() {
        val scale = FitCenterScaleCalculator.calculate(2560, 1440, 1920, 1080)

        assertEquals(1f, scale.x, 0.0001f)
        assertEquals(1f, scale.y, 0.0001f)
    }

    @Test
    fun `wide custom frame is fully shown with top and bottom bars`() {
        val scale = FitCenterScaleCalculator.calculate(2400, 1000, 1920, 1080)

        assertEquals(1f, scale.x, 0.0001f)
        assertEquals(0.7407407f, scale.y, 0.0001f)
    }

    @Test
    fun `invalid dimensions fall back to full surface`() {
        val scale = FitCenterScaleCalculator.calculate(0, 0, 1920, 1080)

        assertEquals(FitCenterScaleCalculator.Scale(1f, 1f), scale)
    }

    @Test
    fun `portrait h264 frame is centered inside a landscape container without cropping`() {
        val size = FitCenterScaleCalculator.calculateSize(
            frameWidth = 1920,
            frameHeight = 2440,
            containerWidth = 1920,
            containerHeight = 1080,
        )

        assertEquals(850, size.width)
        assertEquals(1080, size.height)
    }

    @Test
    fun `wide h264 frame is centered inside a tall container without cropping`() {
        val size = FitCenterScaleCalculator.calculateSize(
            frameWidth = 1920,
            frameHeight = 1080,
            containerWidth = 1080,
            containerHeight = 1920,
        )

        assertEquals(1080, size.width)
        assertEquals(608, size.height)
    }
}
