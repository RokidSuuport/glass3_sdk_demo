package com.rokid.phone.video

import java.nio.ByteBuffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class H264NalUnitInspectorTest {
    @Test
    fun `detects frame slices but not codec configuration`() {
        assertFalse(H264NalUnitInspector.containsFrame(ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 0x67))))
        assertTrue(H264NalUnitInspector.containsFrame(ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 0x65))))
        assertTrue(H264NalUnitInspector.containsFrame(ByteBuffer.wrap(byteArrayOf(0, 0, 1, 0x41))))
    }

    @Test
    fun `detects idr slices separately from predicted slices`() {
        assertTrue(H264NalUnitInspector.containsIdr(ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 0x65))))
        assertFalse(H264NalUnitInspector.containsIdr(ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 0x41))))
        assertFalse(H264NalUnitInspector.containsIdr(ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 0x67))))
    }

    @Test
    fun `detects frames in four byte length prefixed access units`() {
        assertTrue(
            H264NalUnitInspector.containsIdr(
                ByteBuffer.wrap(byteArrayOf(0, 0, 0, 2, 0x65, 0x00))
            )
        )
        assertTrue(
            H264NalUnitInspector.containsFrame(
                ByteBuffer.wrap(byteArrayOf(0, 0, 0, 2, 0x41, 0x00))
            )
        )
    }
}
