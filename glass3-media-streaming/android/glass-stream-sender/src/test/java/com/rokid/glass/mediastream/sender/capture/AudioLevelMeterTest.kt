package com.rokid.glass.mediastream.sender.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLevelMeterTest {

    @Test
    fun empty_pcm_returns_a_finite_silence_floor() {
        val level = AudioLevelMeter.dbfs(byteArrayOf())

        assertEquals(AudioLevelMeter.MIN_DBFS, level, 0.0)
        assertTrue(level.isFinite())
    }

    @Test
    fun digital_silence_returns_the_configured_silence_floor() {
        val pcm = byteArrayOf(0x00, 0x00, 0x00, 0x00)

        assertEquals(AudioLevelMeter.MIN_DBFS, AudioLevelMeter.dbfs(pcm), 0.0)
    }

    @Test
    fun full_scale_positive_pcm_reports_near_zero_dbfs() {
        val pcm = byteArrayOf(0xff.toByte(), 0x7f, 0xff.toByte(), 0x7f)

        assertEquals(0.0, AudioLevelMeter.dbfs(pcm), 0.01)
    }

    @Test
    fun half_scale_pcm_reports_about_minus_six_dbfs() {
        val pcm = byteArrayOf(0x00, 0x40, 0x00, 0x40)

        assertEquals(-6.0206, AudioLevelMeter.dbfs(pcm), 0.01)
    }

    @Test
    fun negative_pcm_is_decoded_as_signed_little_endian() {
        // 0xC000 is -16384 in signed little-endian PCM16. Decoding it as
        // big-endian or unsigned would produce a very different level.
        val pcm = byteArrayOf(0x00, 0xc0.toByte(), 0x00, 0xc0.toByte())

        assertEquals(-6.0206, AudioLevelMeter.dbfs(pcm), 0.01)
    }

    @Test
    fun one_odd_trailing_byte_is_ignored() {
        val pcm = byteArrayOf(0x00, 0x40, 0x7f)

        assertEquals(-6.0206, AudioLevelMeter.dbfs(pcm), 0.01)
    }

    @Test
    fun most_negative_pcm_sample_is_clamped_to_zero_dbfs() {
        val pcm = byteArrayOf(0x00, 0x80.toByte(), 0x00, 0x80.toByte())
        val level = AudioLevelMeter.dbfs(pcm)

        assertEquals(0.0, level, 0.0)
        assertTrue(level <= 0.0)
    }

    @Test
    fun every_result_is_finite_and_within_the_supported_dbfs_range() {
        val inputs = listOf(
            byteArrayOf(),
            byteArrayOf(0x00),
            byteArrayOf(0x00, 0x00),
            byteArrayOf(0xff.toByte(), 0x7f),
            byteArrayOf(0x00, 0x80.toByte()),
            byteArrayOf(0x34, 0x12, 0xcb.toByte(), 0xed.toByte(), 0x55),
        )

        inputs.forEach { pcm ->
            val level = AudioLevelMeter.dbfs(pcm)
            assertTrue("level must be finite for ${pcm.contentToString()}", level.isFinite())
            assertFalse("level must never be NaN", level.isNaN())
            assertTrue("level must not exceed 0 dBFS", level <= 0.0)
            assertTrue("level must not be below the silence floor", level >= AudioLevelMeter.MIN_DBFS)
        }
    }
}
