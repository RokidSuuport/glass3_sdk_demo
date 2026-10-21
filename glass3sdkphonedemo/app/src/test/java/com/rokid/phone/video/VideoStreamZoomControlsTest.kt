package com.rokid.phone.video

import org.junit.Assert.*
import org.junit.Test

class VideoStreamZoomControlsTest {
    @Test fun `old peer can receive an explicitly selected ratio`() {
        val state = VideoStreamZoomControls.parsePeerState("""{"type":"VIDEO_STREAM_RESOLUTION_CHANGE","extra":"{\"width\":1920,\"height\":1080}"}""")!!
        assertNull(state.capabilities)
        assertFalse(state.supportsFloatZoom)
        assertEquals(1.575f, VideoStreamZoomControls().apply { update(state) }.requestRatio(1.575f))
    }

    @Test fun `explicit capability enables decimal request and reports actual separately`() {
        val state = VideoStreamZoomControls.parsePeerState(
            """{"type":"VIDEO_STREAM_RESOLUTION_CHANGE","extra":{"cameraZoomCapabilities":3,"appliedZoomRatio":1.5,"width":2400,"height":1800,"fps":15}}"""
        )!!
        val controls = VideoStreamZoomControls().apply { update(state) }
        assertTrue(state.supportsFloatZoom)
        assertEquals(1.575f, controls.requestRatio(1.575f))
        assertEquals(1.5f, state.appliedRatio)
        assertEquals(2400, state.announcedWidth)
        assertEquals(1800, state.announcedHeight)
        assertEquals(15, state.announcedFps)
        assertNull(controls.requestRatio(null))
    }

    @Test fun `malformed or unrelated packets cannot enable controls`() {
        assertNull(VideoStreamZoomControls.parsePeerState("not json"))
        assertNull(VideoStreamZoomControls.parsePeerState("""{"type":"CUSTOM_API_ACTION","extra":{"cameraZoomCapabilities":1}}"""))
        for (invalid in listOf("null", "true", "\"1\"", "1.5", "-1", "2147483648")) {
            val state = VideoStreamZoomControls.parsePeerState("""{"type":"VIDEO_STREAM_RESOLUTION_CHANGE","extra":{"cameraZoomCapabilities":$invalid}}""")!!
            assertFalse(state.supportsFloatZoom)
        }
    }

    @Test fun `disconnect clears peer evidence and actual value`() {
        val controls = VideoStreamZoomControls()
        controls.update(VideoStreamZoomControls.PeerState(1, 2f))
        controls.clearAppliedRatio()
        assertTrue(controls.peerState.supportsFloatZoom)
        assertNull(controls.peerState.appliedRatio)
        controls.resetConnection()
        assertEquals(2f, controls.requestRatio(2f))
        assertNull(controls.peerState.capabilities)
    }

    @Test fun `custom ratio accepts finite values in supported range`() {
        val controls = VideoStreamZoomControls()

        assertEquals(1.25f, controls.requestRatio(1.25f))
        assertEquals(7.75f, controls.requestRatio(7.75f))
        assertNull(controls.requestRatio(0.99f))
        assertNull(controls.requestRatio(8.01f))
        assertNull(controls.requestRatio(Float.NaN))
    }
}
