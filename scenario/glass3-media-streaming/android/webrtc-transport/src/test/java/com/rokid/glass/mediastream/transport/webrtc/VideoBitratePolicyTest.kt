package com.rokid.glass.mediastream.transport.webrtc

import livekit.org.webrtc.RtpParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoBitratePolicyTest {
    @Test
    fun requested_ceiling_is_applied_to_sender_encodings_and_committed() {
        val parameters = parameters(listOf(RtpParameters.Encoding("", true, 1.0)))
        var committed: RtpParameters? = null
        configureVideoBitrate(900_000, { parameters }) { committed = it; true }

        assertSame(parameters, committed)
        assertEquals(900_000, committed!!.encodings.single().maxBitrateBps)
    }

    @Test
    fun absent_ceiling_does_not_override_webrtc_parameters() {
        configureVideoBitrate(null, { error("default must not read or change sender parameters") }) {
            error("default must not commit parameters")
        }
    }

    @Test
    fun rejected_parameters_report_the_requested_ceiling() {
        val error = assertThrows(IllegalStateException::class.java) {
            configureVideoBitrate(900_000, { parameters(listOf(RtpParameters.Encoding("", true, 1.0))) }) { false }
        }
        assertTrue(error.message.orEmpty().contains("maxVideoBitrateBps=900000"))
    }

    @Test
    fun missing_encoding_is_not_silently_accepted() {
        assertThrows(IllegalStateException::class.java) {
            configureVideoBitrate(900_000, { parameters(emptyList()) }) { true }
        }
    }

    private fun parameters(encodings: List<RtpParameters.Encoding>): RtpParameters =
        RtpParameters::class.java.declaredConstructors.single().apply { isAccessible = true }
            .newInstance("test-transaction", RtpParameters.DegradationPreference.BALANCED, null,
                emptyList<RtpParameters.HeaderExtension>(), encodings, emptyList<RtpParameters.Codec>()) as RtpParameters
}
