package com.rokid.phone.video

import org.junit.Assert.assertEquals
import org.junit.Test

class DecodedVideoGeometryTest {
    @Test
    fun `uses coded dimensions when decoder reports no crop`() {
        val geometry = DecodedVideoGeometry.create(2400, 1800)

        assertEquals(2400, geometry.displayWidth)
        assertEquals(1800, geometry.displayHeight)
    }

    @Test
    fun `uses inclusive crop rectangle as visible dimensions`() {
        val geometry = DecodedVideoGeometry.create(
            codedWidth = 2048,
            codedHeight = 1536,
            cropLeft = 64,
            cropTop = 48,
            cropRight = 1983,
            cropBottom = 1487,
        )

        assertEquals(1920, geometry.displayWidth)
        assertEquals(1440, geometry.displayHeight)
    }

    @Test
    fun `swaps visible dimensions for quarter turn rotation`() {
        val geometry = DecodedVideoGeometry.create(1920, 1080, rotationDegrees = 90)

        assertEquals(1080, geometry.displayWidth)
        assertEquals(1920, geometry.displayHeight)
    }
}
