package com.rokid.glass.mediastream.transport.webrtc

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsAccumulatorTest {
    @Test
    fun encoded_video_metrics_come_from_outbound_rtp_not_camera_or_remote_reports() {
        val result = StatsAccumulator().calculate(1000, listOf(
            RawRtcStat("media-source", mapOf("kind" to "video", "width" to 1280, "height" to 720, "framesPerSecond" to 15.0)),
            RawRtcStat("outbound-rtp", mapOf("kind" to "video", "frameWidth" to 640, "frameHeight" to 360,
                "framesPerSecond" to 12.5, "qualityLimitationReason" to "bandwidth")),
            RawRtcStat("inbound-rtp", mapOf("kind" to "video", "frameWidth" to 1920, "frameHeight" to 1080)),
        ))
        assertEquals(640, result.encodedVideoWidth)
        assertEquals(360, result.encodedVideoHeight)
        assertEquals(12.5, result.encodedVideoFps, 0.0)
        assertEquals("bandwidth", result.videoQualityLimitationReason)
    }

    @Test
    fun unavailable_or_invalid_encoded_metrics_remain_unknown() {
        val result = StatsAccumulator().calculate(1000, listOf(
            RawRtcStat("outbound-rtp", mapOf("kind" to "video", "frameWidth" to -2, "frameHeight" to 0,
                "framesPerSecond" to Double.NaN, "qualityLimitationReason" to "")),
        ))
        assertEquals(0, result.encodedVideoWidth)
        assertEquals(0, result.encodedVideoHeight)
        assertEquals(0.0, result.encodedVideoFps, 0.0)
        assertEquals("unknown", result.videoQualityLimitationReason)
    }

    @Test
    fun computes_bitrate_and_outbound_diagnostics_from_all_matching_ssrcs() {
        val accumulator = StatsAccumulator()
        accumulator.calculate(
            nowMs = 1_000,
            stats = listOf(
                RawRtcStat("outbound-rtp", mapOf("kind" to "video", "bytesSent" to 1_000L)),
                RawRtcStat("outbound-rtp", mapOf("mediaType" to "audio", "bytesSent" to BigInteger.valueOf(500L))),
            ),
        )

        val result = accumulator.calculate(
            nowMs = 2_000,
            stats = listOf(
                RawRtcStat(
                    "outbound-rtp",
                    mapOf(
                        "kind" to "video",
                        "bytesSent" to 2_000L,
                        "framesSent" to 20L,
                        "framesEncoded" to 19L,
                    ),
                ),
                RawRtcStat(
                    "outbound-rtp",
                    mapOf(
                        "kind" to "video",
                        "bytesSent" to 1_000L,
                        "framesSent" to 10L,
                        "framesEncoded" to 10L,
                    ),
                ),
                RawRtcStat("outbound-rtp", mapOf("mediaType" to "audio", "bytesSent" to 1_000L)),
                RawRtcStat("inbound-rtp", mapOf("kind" to "video", "bytesSent" to 999_999L)),
                RawRtcStat(
                    "remote-inbound-rtp",
                    mapOf("kind" to "video", "packetsLost" to 2L, "roundTripTime" to 0.042),
                ),
                RawRtcStat(
                    "remote-inbound-rtp",
                    mapOf("kind" to "audio", "packetsLost" to 1L, "roundTripTime" to 0.021),
                ),
            ),
        )

        assertEquals(3_000L, result.videoBytesSent)
        assertEquals(1_000L, result.audioBytesSent)
        assertEquals(16_000L, result.videoBitrateBps)
        assertEquals(4_000L, result.audioBitrateBps)
        assertEquals(30L, result.framesSent)
        assertEquals(29L, result.framesEncoded)
        assertEquals(3L, result.packetsLost)
        assertEquals(42L, result.roundTripTimeMs)
    }

    @Test
    fun each_counter_reset_is_clamped_without_hiding_the_other_media_bitrate() {
        val accumulator = StatsAccumulator()
        accumulator.calculate(
            1_000,
            listOf(
                RawRtcStat("outbound-rtp", mapOf("kind" to "video", "bytesSent" to 5_000L)),
                RawRtcStat("outbound-rtp", mapOf("kind" to "audio", "bytesSent" to 1_000L)),
            ),
        )
        val reset = accumulator.calculate(
            2_000,
            listOf(
                RawRtcStat("outbound-rtp", mapOf("kind" to "video", "bytesSent" to 10L)),
                RawRtcStat("outbound-rtp", mapOf("kind" to "audio", "bytesSent" to 2_000L)),
            ),
        )

        assertEquals(0L, reset.videoBitrateBps)
        assertEquals(8_000L, reset.audioBitrateBps)
    }

    @Test
    fun non_positive_elapsed_time_negative_loss_and_missing_members_are_safe() {
        val accumulator = StatsAccumulator()
        accumulator.calculate(1_000, emptyList())
        val result = accumulator.calculate(
            1_000,
            listOf(
                RawRtcStat("outbound-rtp", mapOf("kind" to "video", "bytesSent" to "invalid")),
                RawRtcStat("remote-inbound-rtp", mapOf("packetsLost" to -9L, "roundTripTime" to -1.0)),
            ),
        )

        assertEquals(0L, result.videoBitrateBps)
        assertEquals(0L, result.audioBitrateBps)
        assertEquals(0L, result.packetsLost)
        assertEquals(0L, result.roundTripTimeMs)
    }
}
