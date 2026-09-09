package com.rokid.glass.mediastream.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SharedFrameLeaseTest {
    @Test
    fun `two retained handles release the owned buffer only after every handle closes`() {
        val pooledData = ByteArray(12)
        var finalReleaseCount = 0
        var releasedData: ByteArray? = null
        val original = Nv21Frame.create(
            data = pooledData,
            width = 4,
            height = 2,
            timestampNs = 10L,
            finalRelease = { data ->
                finalReleaseCount += 1
                releasedData = data
            },
        )
        val firstRetained = original.retain()
        val secondRetained = original.retain()

        original.close()
        firstRetained.close()
        assertEquals(0, finalReleaseCount)

        secondRetained.close()
        assertEquals(1, finalReleaseCount)
        assertSame(pooledData, releasedData)
    }

    @Test
    fun `closing the same handle twice releases its reference once`() {
        val pooledData = ByteArray(12)
        var finalReleaseCount = 0
        val frame = Nv21Frame.create(
            data = pooledData,
            width = 4,
            height = 2,
            timestampNs = 10L,
            finalRelease = { finalReleaseCount += 1 },
        )

        frame.close()
        frame.close()

        assertEquals(1, finalReleaseCount)
    }

    @Test
    fun `closed handles cannot be retained after their buffer is released`() {
        val pooledData = ByteArray(12)
        val frame = Nv21Frame.create(
            data = pooledData,
            width = 4,
            height = 2,
            timestampNs = 10L,
            finalRelease = {},
        )
        frame.close()

        assertThrows(IllegalStateException::class.java) {
            frame.retain()
        }
    }
}
