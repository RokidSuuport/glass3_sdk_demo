package com.rokid.glass.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFrameEvidenceTest {
    @Test fun acceptsACompleteFrameFromTheConfirmedGeneration() {
        assertTrue(CameraFrameEvidence.accepts(true, 1.575f, 4, 4, 640, 480, 460800))
    }

    @Test fun rejectsLateOldFramesEvenWhenZoomIsReady() {
        assertFalse(CameraFrameEvidence.accepts(true, 1.575f, 3, 4, 640, 480, 460800))
    }

    @Test fun rejectsUnconfirmedFramesAndUnavailableZoom() {
        assertFalse(CameraFrameEvidence.accepts(false, 1.575f, 4, 4, 640, 480, 460800))
        assertFalse(CameraFrameEvidence.accepts(true, Float.NaN, 4, 4, 640, 480, 460800))
        assertFalse(CameraFrameEvidence.accepts(true, 1.575f, -1, -1, 640, 480, 460800))
    }

    @Test fun rejectsTruncatedAndInvalidFrameGeometry() {
        assertFalse(CameraFrameEvidence.accepts(true, 1.575f, 4, 4, 640, 480, 460799))
        assertFalse(CameraFrameEvidence.accepts(true, 1.575f, 4, 4, 641, 480, Int.MAX_VALUE))
        assertFalse(CameraFrameEvidence.accepts(true, 1.575f, 4, 4, 0, 480, Int.MAX_VALUE))
        assertFalse(CameraFrameEvidence.accepts(true, 1.575f, 4, 4, 2_000_000_000, 2_000_000_000, Int.MAX_VALUE))
    }
}
