package com.rokid.glass.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomDisplayStatusTest {
    @Test fun pendingTimesOutEvenWithoutFramesOrState() {
        val display = ZoomDisplayStatus()
        assertEquals(ZoomDisplayStatus.Status.LOADING, display.update(null, false, Float.NaN, 100))
        assertEquals(ZoomDisplayStatus.Status.LOADING, display.update(null, false, Float.NaN, 3099))
        assertEquals(1L, display.remainingMs)
        assertEquals(ZoomDisplayStatus.Status.UNAVAILABLE, display.update(null, false, Float.NaN, 3100))
    }
    @Test fun repeatedPendingCallbacksDoNotExtendDeadline() {
        val display = ZoomDisplayStatus()
        display.update(7, false, 1f, 100)
        display.update(7, false, 1f, 2000)
        assertEquals(ZoomDisplayStatus.Status.UNAVAILABLE, display.update(7, false, 1f, 3100))
    }
    @Test fun newRevisionStartsNewWaitAndLateEvidenceRecovers() {
        val display = ZoomDisplayStatus()
        display.update(7, false, Float.NaN, 0)
        display.update(7, false, Float.NaN, 3000)
        assertEquals(ZoomDisplayStatus.Status.LOADING, display.update(8, false, Float.NaN, 4000))
        assertEquals(ZoomDisplayStatus.Status.AVAILABLE, display.update(8, true, 1.575f, 8000))
        assertEquals(ZoomDisplayStatus.Status.LOADING, display.update(8, false, 1.575f, 9000))
        assertEquals(ZoomDisplayStatus.Status.UNAVAILABLE, display.update(8, false, 1.575f, 12000))
    }
    @Test fun invalidActualValueIsNeverDisplayedAsAvailable() {
        val display = ZoomDisplayStatus()
        assertEquals(ZoomDisplayStatus.Status.LOADING, display.update(1, true, Float.NaN, 0))
        assertEquals(ZoomDisplayStatus.Status.UNAVAILABLE, display.update(1, true, Float.POSITIVE_INFINITY, 3000))
    }
}
