package com.rokid.glass.camera

import com.rokid.security.glass3.sdk.base.data.media.CameraSurfaceContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSurfaceBufferPlanTest {
    @Test fun negotiatedBufferWinsOverRequestAndCallbackGeometry() {
        val encoded = CameraSurfaceContract.encodeV1(CameraSurfaceContract.PATH_COMPOSED, 960, 720, true)
        val plan = CameraSurfaceBufferPlan.resolve(encoded, 1280, 720)
        assertTrue(plan.negotiated)
        assertEquals(960, plan.width)
        assertEquals(720, plan.height)
        assertNull(plan.legacyWatermarkResize(720, 1280))
    }

    @Test fun directContractPreservesExactBufferAndCallbackSemantics() {
        val encoded = CameraSurfaceContract.encodeV1(CameraSurfaceContract.PATH_DIRECT_CAMERA, 720, 1280, false)
        val plan = CameraSurfaceBufferPlan.resolve(encoded, 1280, 720)
        assertEquals(CameraSurfaceContract.PATH_DIRECT_CAMERA, plan.path)
        assertFalse(plan.callbackUsesCameraSourceSize)
        assertEquals(720, plan.width)
        assertEquals(1280, plan.height)
        assertNull(plan.legacyWatermarkResize(1280, 720))
    }

    @Test fun unsupportedProbeKeepsOnlyTheExplicitLegacyWatermarkCompatibilityPath() {
        val plan = CameraSurfaceBufferPlan.resolve(0L, 640, 480)
        assertFalse(plan.negotiated)
        assertEquals(640, plan.width)
        assertEquals(480 to 640, plan.legacyWatermarkResize(640, 480))
        assertNull(plan.legacyWatermarkResize(0, 480))
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedNonzeroContractMustNotFallback() {
        CameraSurfaceBufferPlan.resolve(123L, 640, 480)
    }

    @Test fun legacyDirectConfiguresRotatedBufferBeforeStartAndNeverResizesOnCallback() {
        val plan = CameraSurfaceBufferPlan.resolve(0L, 1280, 720, false)
        assertEquals(CameraSurfaceContract.PATH_DIRECT_CAMERA, plan.path)
        assertEquals(720, plan.width)
        assertEquals(1280, plan.height)
        assertNull(plan.legacyWatermarkResize(1280, 720))
    }
}
