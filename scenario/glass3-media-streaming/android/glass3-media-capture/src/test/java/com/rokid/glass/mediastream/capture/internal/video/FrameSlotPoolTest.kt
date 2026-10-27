package com.rokid.glass.mediastream.capture.internal.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSlotPoolTest {
    @Test
    fun `frame contains an independent copy with the supplied nanosecond timestamp`() {
        val pool = FrameSlotPool(capacity = 2)
        val source = ByteArray(4 * 2 * 3 / 2) { it.toByte() }

        val frame = requireNotNull(pool.acquireFrame(source, source.size, 4, 2, 10L))
        source.fill(99)

        assertEquals(4, frame.width)
        assertEquals(2, frame.height)
        assertEquals(10L, frame.timestampNs)
        assertArrayEquals(ByteArray(12) { it.toByte() }, frame.data)
        frame.close()
    }

    @Test
    fun `invalid dimensions and short input are rejected`() {
        val pool = FrameSlotPool()
        val source = ByteArray(12)

        for ((width, height) in listOf(0 to 2, 4 to 0, 3 to 2, 4 to 3)) {
            assertThrows(IllegalArgumentException::class.java) {
                pool.acquireFrame(source, source.size, width, height, 1L)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            pool.acquireFrame(source, 11, 4, 2, 1L)
        }
    }

    @Test
    fun `capacity rejects overload and a fully released slot is reused safely`() {
        val pool = FrameSlotPool(capacity = 2)
        val frameBytes = ByteArray(12)

        val first = requireNotNull(pool.acquireFrame(frameBytes, frameBytes.size, 4, 2, 1L))
        val retained = first.retain()
        val second = requireNotNull(pool.acquireFrame(frameBytes, frameBytes.size, 4, 2, 1L))
        assertNull(pool.acquireFrame(frameBytes, frameBytes.size, 4, 2, 1L))
        assertEquals(2L, pool.acceptedFrames)
        assertEquals(1L, pool.droppedFrames)
        assertEquals(2, pool.inUseSlots)
        assertEquals(2, pool.allocatedSlotCount)

        first.close()
        assertEquals(2, pool.inUseSlots)
        retained.close()
        retained.close()
        assertEquals(1, pool.inUseSlots)

        val fourth = pool.acquireFrame(frameBytes, frameBytes.size, 4, 2, 1L)
        assertNotNull(fourth)
        assertEquals(2, pool.inUseSlots)
        assertTrue(requireNotNull(fourth).timestampNs > second.timestampNs)

        second.close()
        fourth.close()
        assertEquals(0, pool.inUseSlots)
    }

    @Test
    fun `slot storage resizes only after resolution changes and excludes source padding`() {
        val pool = FrameSlotPool(capacity = 1)
        val first = requireNotNull(pool.acquireFrame(ByteArray(12), 12, 4, 2, 1L))
        val firstStorage = first.data
        first.close()

        val padded = ByteArray(20) { it.toByte() }
        val sameSize = requireNotNull(pool.acquireFrame(padded, 12, 4, 2, 2L))
        assertTrue(firstStorage === sameSize.data)
        assertEquals(12, sameSize.data.size)
        assertArrayEquals(ByteArray(12) { it.toByte() }, sameSize.data)
        sameSize.close()

        val resized = requireNotNull(pool.acquireFrame(ByteArray(24), 24, 4, 4, 3L))
        assertEquals(24, resized.data.size)
        assertTrue(firstStorage !== resized.data)
        resized.close()
    }
}
