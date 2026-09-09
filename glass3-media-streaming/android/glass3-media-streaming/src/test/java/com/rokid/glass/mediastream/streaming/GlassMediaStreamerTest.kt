package com.rokid.glass.mediastream.streaming

import com.rokid.glass.mediastream.streaming.internal.StreamingController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class GlassMediaStreamerTest {
    @Test
    fun simple_start_uses_customer_defaults() {
        val controller = FakeStreamingController()
        val streamer = GlassMediaStreamer.create(controller)

        streamer.start("ws://192.168.1.10:8080/ws")

        assertEquals(
            StreamingOptions("ws://192.168.1.10:8080/ws"),
            controller.starts.single().first,
        )
        assertNull(controller.starts.single().second)
    }

    @Test
    fun advanced_start_and_lifecycle_delegate_without_replacing_the_listener() {
        val controller = FakeStreamingController()
        val streamer = GlassMediaStreamer.create(controller)
        val options = StreamingOptions(
            serverUrl = "wss://example.test/ws",
            videoEnabled = false,
            roomId = "field_team",
        )
        val listener = StreamingStatusListener { }

        streamer.start(options, listener)
        streamer.stop()
        streamer.release()

        assertSame(options, controller.starts.single().first)
        assertSame(listener, controller.starts.single().second)
        assertEquals(1, controller.stopCount)
        assertEquals(1, controller.releaseCount)
        assertSame(controller.status, streamer.currentStatus())
    }

    private class FakeStreamingController : StreamingController {
        val starts = mutableListOf<Pair<StreamingOptions, StreamingStatusListener?>>()
        var stopCount = 0
        var releaseCount = 0
        val status = StreamingStatus(StreamingState.IDLE)

        override fun start(options: StreamingOptions, listener: StreamingStatusListener?) {
            starts += options to listener
        }

        override fun stop() {
            stopCount += 1
        }

        override fun release() {
            releaseCount += 1
        }

        override fun currentStatus(): StreamingStatus = status
    }
}
