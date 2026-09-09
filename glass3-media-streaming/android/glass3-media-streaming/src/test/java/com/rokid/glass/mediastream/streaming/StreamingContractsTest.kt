package com.rokid.glass.mediastream.streaming

import com.rokid.glass.mediastream.streaming.internal.StreamingOptionsValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingContractsTest {
    @Test
    fun options_default_to_audio_video_and_the_default_room() {
        val options = StreamingOptions("ws://192.168.1.10:8080/ws")

        assertTrue(options.videoEnabled)
        assertTrue(options.audioEnabled)
        assertEquals("default", options.roomId)
    }

    @Test
    fun validator_normalizes_the_url_and_accepts_room_boundaries() {
        val room = "a".repeat(32)

        val validated = StreamingOptionsValidator.validate(
            StreamingOptions("  wss://example.test/ws  ", audioEnabled = false, roomId = room),
        )

        assertEquals("wss://example.test/ws", validated.serverUrl)
        assertEquals(room, validated.roomId)
        assertTrue(validated.videoEnabled)
        assertFalse(validated.audioEnabled)
    }

    @Test
    fun validator_rejects_invalid_urls_rooms_and_empty_media_selection() {
        val invalidUrls = listOf(
            "http://example.test/ws",
            "ws:///ws",
            "ws://user:secret@example.test/ws",
            "ws://example.test/ws#fragment",
            "ws://example.test:70000/ws",
        )
        for (url in invalidUrls) {
            assertThrows(IllegalArgumentException::class.java) {
                StreamingOptionsValidator.validate(StreamingOptions(url))
            }
        }
        for (room in listOf("", "space room", "a".repeat(33))) {
            assertThrows(IllegalArgumentException::class.java) {
                StreamingOptionsValidator.validate(
                    StreamingOptions("ws://example.test/ws", roomId = room),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            StreamingOptionsValidator.validate(
                StreamingOptions(
                    serverUrl = "ws://example.test/ws",
                    videoEnabled = false,
                    audioEnabled = false,
                ),
            )
        }
    }
}
