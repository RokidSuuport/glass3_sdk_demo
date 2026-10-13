package com.rokid.glass.view.glsurface.view

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class I420FrameMailboxTest {
    @Test fun copiesVisibleRowsWithoutPaddingOrChangingBorrowedPositions() {
        val mailbox = I420FrameMailbox()
        val y = plane(99, 1, 2, 3, 4, 99, 99, 5, 6, 7, 8).apply { position(1) }
        val u = plane(99, 21, 22).apply { position(1) }
        val v = plane(99, 31, 32).apply { position(1) }
        mailbox.submit(y.asReadOnlyBuffer(), u.asReadOnlyBuffer(), v.asReadOnlyBuffer(), 4, 2, 6, 3, 4)
        // The SDK may reuse shared memory as soon as the lease is closed.
        y.put(1, 88); u.put(1, 88); v.put(1, 88)
        val frame = mailbox.frameForDraw()!!
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), bytes(frame.y))
        assertEquals(listOf(21, 22), bytes(frame.u))
        assertEquals(listOf(31, 32), bytes(frame.v))
        assertEquals(1, y.position()); assertEquals(1, u.position()); assertEquals(1, v.position())
    }

    @Test fun respectsChromaStridesOnEveryRow() {
        val mailbox = I420FrameMailbox()
        mailbox.submit(plane(*IntArray(16) { 10 }), plane(1, 2, 99, 3, 4),
            plane(5, 6, 99, 99, 7, 8), 4, 4, 4, 3, 4)
        val frame = mailbox.frameForDraw()!!
        assertEquals(listOf(1, 2, 3, 4), bytes(frame.u))
        assertEquals(listOf(5, 6, 7, 8), bytes(frame.v))
    }

    @Test fun replacesPendingFrameAcrossResolutionChangesWithoutTouchingDrawnFrame() {
        val mailbox = I420FrameMailbox()
        submit(mailbox, 10, 4, 2)
        val drawing = mailbox.frameForDraw()!!
        submit(mailbox, 20, 4, 2)
        submit(mailbox, 30, 8, 4)
        assertEquals(10, drawing.y.get(0).toInt())
        val latest = mailbox.frameForDraw()!!
        assertEquals(8, latest.width); assertEquals(4, latest.height)
        assertEquals(32, latest.y.remaining()); assertEquals(8, latest.u.remaining())
        assertEquals(30, latest.y.get(0).toInt())
        assertEquals(1L, mailbox.droppedFrameCount)
        submit(mailbox, 40, 2, 2)
        assertEquals(30, latest.y.get(0).toInt())
        assertEquals(2, mailbox.frameForDraw()!!.width)
    }

    @Test fun redrawUsesOwnedFrameUntilStopClearsIt() {
        val mailbox = I420FrameMailbox()
        submit(mailbox, 10, 4, 2)
        mailbox.frameForDraw()!!.y.position(3)
        assertEquals(8, mailbox.frameForDraw()!!.y.remaining())
        mailbox.clear()
        assertNull(mailbox.frameForDraw())
        assertEquals(0L, mailbox.receivedFrameCount)
        submit(mailbox, 20, 4, 2)
        assertEquals(20, mailbox.frameForDraw()!!.y.get(0).toInt())
    }

    @Test fun invalidPlaneCannotPartiallyOverwritePendingFrame() {
        val mailbox = I420FrameMailbox()
        submit(mailbox, 10, 4, 2)
        val cases = listOf<() -> Unit>(
            { mailbox.submit(plane(*IntArray(8)), plane(1, 2), plane(1), 4, 2, 4, 2, 2) },
            { mailbox.submit(plane(*IntArray(8)), plane(1, 2), plane(1, 2), 4, 2, 3, 2, 2) },
            { mailbox.submit(plane(1), plane(1), plane(1), 3, 2, 3, 2, 2) },
        )
        cases.forEach { invalid ->
            try { invalid(); fail("Malformed I420 must be rejected") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(10, mailbox.frameForDraw()!!.y.get(0).toInt())
        assertEquals(1L, mailbox.receivedFrameCount)
    }

    private fun submit(mailbox: I420FrameMailbox, value: Int, width: Int, height: Int) {
        mailbox.submit(plane(*IntArray(width * height) { value }),
            plane(*IntArray(width * height / 4) { value + 1 }),
            plane(*IntArray(width * height / 4) { value + 2 }), width, height, width, width / 2, width / 2)
    }
    private fun plane(vararg values: Int) = ByteBuffer.wrap(ByteArray(values.size) { values[it].toByte() })
    private fun bytes(data: ByteBuffer) = List(data.remaining()) { data.get(data.position() + it).toInt() and 255 }
}
