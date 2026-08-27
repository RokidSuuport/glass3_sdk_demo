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
}
