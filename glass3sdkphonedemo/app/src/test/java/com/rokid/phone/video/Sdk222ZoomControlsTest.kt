package com.rokid.phone.video

import org.junit.Assert.*
import org.junit.Test

class Sdk222ZoomControlsTest {
    @Test fun `old peer missing capability does not inherit local default`() {
        val state = Sdk222ZoomControls.parsePeerState("""{"type":"VIDEO_STREAM_RESOLUTION_CHANGE","extra":"{\"width\":1920,\"height\":1080}"}""")!!
        assertNull(state.capabilities)
        assertFalse(state.supportsFloatZoom)
        assertNull(Sdk222ZoomControls().apply { update(state) }.requestRatio(1.575f))
    }

    @Test fun `explicit capability enables decimal request and reports actual separately`() {
        val state = Sdk222ZoomControls.parsePeerState("""{"type":"VIDEO_STREAM_RESOLUTION_CHANGE","extra":{"cameraZoomCapabilities":3,"appliedZoomRatio":1.5}}""")!!
        val controls = Sdk222ZoomControls().apply { update(state) }
        assertTrue(state.supportsFloatZoom)
        assertEquals(1.575f, controls.requestRatio(1.575f))
        assertEquals(1.5f, state.appliedRatio)
        assertNull(controls.requestRatio(null))
    }

    @Test fun `malformed or unrelated packets cannot enable controls`() {
        assertNull(Sdk222ZoomControls.parsePeerState("not json"))
        assertNull(Sdk222ZoomControls.parsePeerState("""{"type":"CUSTOM_API_ACTION","extra":{"cameraZoomCapabilities":1}}"""))
        for (invalid in listOf("null", "true", "\"1\"", "1.5", "-1", "2147483648")) {
            val state = Sdk222ZoomControls.parsePeerState("""{"type":"VIDEO_STREAM_RESOLUTION_CHANGE","extra":{"cameraZoomCapabilities":$invalid}}""")!!
            assertFalse(state.supportsFloatZoom)
        }
    }

    @Test fun `disconnect clears peer evidence and actual value`() {
        val controls = Sdk222ZoomControls()
        controls.update(Sdk222ZoomControls.PeerState(1, 2f))
        controls.clearAppliedRatio()
        assertTrue(controls.peerState.supportsFloatZoom)
        assertNull(controls.peerState.appliedRatio)
        controls.resetConnection()
        assertNull(controls.requestRatio(2f))
        assertNull(controls.peerState.capabilities)
    }
}
