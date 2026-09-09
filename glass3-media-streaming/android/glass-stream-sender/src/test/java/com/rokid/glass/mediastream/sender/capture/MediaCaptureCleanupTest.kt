package com.rokid.glass.mediastream.sender.capture

import com.rokid.glass.mediastream.capture.MediaErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCaptureCleanupTest {
    @Test
    fun cleanup_failure_is_contained_and_forwarded_to_the_page_error_handler() {
        val cleanupError = IllegalStateException("audio stop failed")
        var handled: Throwable? = null

        val completed = runCaptureCleanup(
            action = { throw cleanupError },
            onFailure = { handled = it },
        )

        assertFalse(completed)
        assertSame(cleanupError, handled)
    }

    @Test
    fun cleanup_failure_uses_a_stable_customer_error_and_keeps_technical_detail_private() {
        val cleanupError = IllegalStateException("vendor binder detail")

        val failure = captureCleanupFailure(cleanupError)

        assertEquals(MediaErrorCode.SDK_DISCONNECTED, failure.code)
        assertEquals("SDK 运行中断开", failure.userMessage)
        assertFalse(failure.userMessage.contains("vendor binder detail"))
        assertTrue(failure.technicalMessage.contains("vendor binder detail"))
        assertSame(cleanupError, failure.cause)
    }
}
