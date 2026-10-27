package com.rokid.glass.mediastream.transport.signaling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SignalingCodecTest {
    private val codec = SignalingCodec()

    @Test
    fun supported_messages_round_trip_with_exact_payloads() {
        val messages = listOf(
            SignalingMessage(type = SignalingType.JOIN, roomId = "default", role = "sender"),
            SignalingMessage(type = SignalingType.PEER_READY, roomId = "room_1"),
            SignalingMessage(type = SignalingType.OFFER, roomId = "default", sdp = "v=0\r\nm=video\r\n"),
            SignalingMessage(type = SignalingType.ANSWER, roomId = "default", sdp = "v=0\r\nm=audio\r\n"),
            SignalingMessage(
                type = SignalingType.ICE_CANDIDATE,
                roomId = "default",
                candidate = IceCandidatePayload(
                    sdpMid = "0",
                    sdpMLineIndex = 0,
                    candidate = "candidate:1 1 udp 1 192.168.1.2 50000 typ host",
                ),
            ),
            SignalingMessage(type = SignalingType.LEAVE, roomId = "default"),
            SignalingMessage(type = SignalingType.ERROR, message = "peer not ready"),
        )

        for (message in messages) {
            assertEquals(message, codec.decode(codec.encode(message)))
        }
    }

    @Test
    fun sdp_line_endings_survive_json_encoding() {
        val sdp = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\na=sendonly\r\n"
        val decoded = codec.decode(
            codec.encode(SignalingMessage(SignalingType.OFFER, roomId = "default", sdp = sdp)),
        )
        assertEquals(sdp, decoded.sdp)
    }

    @Test
    fun message_above_64_kib_is_rejected_before_json_parsing() {
        val raw = "{\"type\":\"offer\",\"roomId\":\"default\",\"sdp\":\"" + "x".repeat(65_537) + "\"}"
        assertThrows(IllegalArgumentException::class.java) { codec.decode(raw) }
        assertThrows(IllegalArgumentException::class.java) {
            codec.encode(SignalingMessage(SignalingType.ERROR, message = "界".repeat(30_000)))
        }
    }

    @Test
    fun unknown_or_non_string_message_type_is_rejected() {
        for (raw in listOf(
            "{\"type\":\"execute\",\"roomId\":\"default\"}",
            "{\"type\":7,\"roomId\":\"default\"}",
            "[]",
        )) {
            assertThrows(IllegalArgumentException::class.java) { codec.decode(raw) }
        }
    }

    @Test
    fun room_id_is_restricted_to_one_through_32_safe_characters() {
        assertEquals(
            "A_b-9",
            codec.decode("{\"type\":\"join\",\"roomId\":\"A_b-9\",\"role\":\"receiver\"}").roomId,
        )
        assertEquals("a".repeat(32), codec.decode(codec.encode(SignalingMessage(SignalingType.LEAVE, "a".repeat(32)))).roomId)
        for (roomId in listOf("", "contains space", "a".repeat(33), "../other", "房间")) {
            assertThrows(IllegalArgumentException::class.java) {
                codec.encode(SignalingMessage(SignalingType.JOIN, roomId = roomId, role = "sender"))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            codec.decode("{\"type\":\"join\",\"roomId\":null,\"role\":\"sender\"}")
        }
    }

    @Test
    fun join_role_must_be_sender_or_receiver_and_must_be_a_string() {
        for (raw in listOf(
            "{\"type\":\"join\",\"roomId\":\"default\",\"role\":\"admin\"}",
            "{\"type\":\"join\",\"roomId\":\"default\",\"role\":1}",
        )) {
            assertThrows(IllegalArgumentException::class.java) { codec.decode(raw) }
        }
    }

    @Test
    fun offer_answer_and_ice_require_typed_non_empty_payloads() {
        val invalid = listOf(
            "{\"type\":\"offer\",\"roomId\":\"default\",\"sdp\":1}",
            "{\"type\":\"answer\",\"roomId\":\"default\",\"sdp\":\"\"}",
            "{\"type\":\"ice-candidate\",\"roomId\":\"default\",\"candidate\":\"bad\"}",
            "{\"type\":\"ice-candidate\",\"roomId\":\"default\",\"candidate\":{\"sdpMid\":\"0\",\"sdpMLineIndex\":\"0\",\"candidate\":\"candidate:1\"}}",
            "{\"type\":\"ice-candidate\",\"roomId\":\"default\",\"candidate\":{\"sdpMid\":\"0\",\"sdpMLineIndex\":-1,\"candidate\":\"candidate:1\"}}",
        )
        for (raw in invalid) {
            assertThrows(IllegalArgumentException::class.java) { codec.decode(raw) }
        }
    }

    @Test
    fun signaling_url_accepts_only_websocket_schemes_and_a_valid_host() {
        assertEquals("ws://192.168.1.10:8080/ws", validateSignalingUrl("ws://192.168.1.10:8080/ws"))
        assertEquals("wss://example.test/ws", validateSignalingUrl(" wss://example.test/ws "))
        for (invalid in listOf(
            "http://example.test",
            "ws:///ws",
            "ws://user:secret@example.test/ws",
            "ws://example.test:70000/ws",
            "ws://example.test/ws#fragment",
            "not a url",
        )) {
            assertThrows(IllegalArgumentException::class.java) { validateSignalingUrl(invalid) }
        }
    }
}
