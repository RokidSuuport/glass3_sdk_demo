package com.rokid.glass.mediastream.transport.webrtc.video

import com.rokid.glass.mediastream.capture.Nv21Frame
import java.lang.reflect.InvocationTargetException
import kotlin.Function1
import livekit.org.webrtc.NV21Buffer
import livekit.org.webrtc.VideoFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class WebRtcVideoAdapterTest {
    @Test
    fun `actual nv21 dimensions timestamp and zero rotation survive until webrtc releases its frame`() {
        val sourceBytes = ByteArray(12) { (it + 1).toByte() }
        var slotReleaseCount = 0
        val sourceFrame = nv21Frame(
            data = sourceBytes,
            width = 4,
            height = 2,
            timestampNs = 987_654L,
            onFinalRelease = { slotReleaseCount += 1 },
        )
        var retainedByWebRtc: VideoFrame? = null
        val adapter = WebRtcVideoAdapter(
            VideoFrameTarget { frame ->
                assertEquals(4, frame.buffer.width)
                assertEquals(2, frame.buffer.height)
                assertEquals(4, frame.rotatedWidth)
                assertEquals(2, frame.rotatedHeight)
                assertEquals(0, frame.rotation)
                assertEquals(987_654L, frame.timestampNs)
                assertEquals(NV21Buffer::class.java, frame.buffer.javaClass)
                frame.retain()
                retainedByWebRtc = frame
            },
        )

        adapter.onVideoFrame(sourceFrame)
        sourceFrame.close()

        assertEquals(0, slotReleaseCount)
        retainedByWebRtc!!.release()
        assertEquals(1, slotReleaseCount)
        assertSame(sourceBytes, sourceFrame.data)
    }

    @Test
    fun `delivery failure releases the retained nv21 lease`() {
        var slotReleaseCount = 0
        val sourceFrame = nv21Frame(
            data = ByteArray(12),
            width = 4,
            height = 2,
            timestampNs = 10L,
            onFinalRelease = { slotReleaseCount += 1 },
        )
        val failure = IllegalStateException("observer failed")
        val adapter = WebRtcVideoAdapter(VideoFrameTarget { throw failure })

        val thrown = assertThrows(IllegalStateException::class.java) {
            adapter.onVideoFrame(sourceFrame)
        }
        sourceFrame.close()

        assertSame(failure, thrown)
        assertEquals(1, slotReleaseCount)
    }

    private fun nv21Frame(
        data: ByteArray,
        width: Int,
        height: Int,
        timestampNs: Long,
        onFinalRelease: (ByteArray) -> Unit,
    ): Nv21Frame {
        val companion = Nv21Frame::class.java.getField("Companion").get(null)
        val create = companion.javaClass.getDeclaredMethod(
            "create",
            ByteArray::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Function1::class.java,
        )
        return try {
            create.invoke(
                companion,
                data,
                width,
                height,
                timestampNs,
                onFinalRelease,
            ) as Nv21Frame
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }
}
