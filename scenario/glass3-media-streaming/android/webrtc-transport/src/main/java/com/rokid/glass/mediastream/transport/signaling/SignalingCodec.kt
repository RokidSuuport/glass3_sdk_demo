package com.rokid.glass.mediastream.transport.signaling

import java.math.BigInteger
import java.net.URI
import org.json.JSONObject

class SignalingCodec {
    fun encode(message: SignalingMessage): String {
        validate(message)
        return JSONObject().apply {
            put("type", message.type.wireValue)
            put("roomId", message.roomId)
            message.role?.let { put("role", it) }
            message.sdp?.let { put("sdp", it) }
            message.candidate?.let { candidate ->
                put(
                    "candidate",
                    JSONObject().apply {
                        put("sdpMid", candidate.sdpMid ?: JSONObject.NULL)
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                        put("candidate", candidate.candidate)
                    },
                )
            }
            message.message?.let { put("message", it) }
        }.toString().also(::requireMessageSize)
    }

    fun decode(raw: String): SignalingMessage {
        requireMessageSize(raw)
        val json = try {
            JSONObject(raw)
        } catch (error: Exception) {
            throw IllegalArgumentException("Malformed signaling message", error)
        }
        val message = try {
            SignalingMessage(
                type = SignalingType.fromWireValue(json.requiredString("type")),
                roomId = if (json.has("roomId")) {
                    json.requiredString("roomId")
                } else {
                    SignalingMessage.DEFAULT_ROOM_ID
                },
                role = json.optionalString("role"),
                sdp = json.optionalString("sdp"),
                candidate = json.optionalObject("candidate")?.let { candidate ->
                    IceCandidatePayload(
                        sdpMid = candidate.optionalString("sdpMid"),
                        sdpMLineIndex = candidate.requiredInt("sdpMLineIndex"),
                        candidate = candidate.requiredString("candidate"),
                    )
                },
                message = json.optionalString("message"),
            )
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid signaling message", error)
        }
        validate(message)
        return message
    }

    private fun validate(message: SignalingMessage) {
        require(ROOM_ID_PATTERN.matches(message.roomId)) {
            "roomId must contain 1-32 letters, digits, underscores, or hyphens"
        }
        when (message.type) {
            SignalingType.JOIN -> require(message.role in ALLOWED_ROLES) {
                "join role must be sender or receiver"
            }

            SignalingType.OFFER,
            SignalingType.ANSWER,
            -> require(!message.sdp.isNullOrBlank()) {
                message.type.wireValue + " requires SDP"
            }

            SignalingType.ICE_CANDIDATE -> requireNotNull(message.candidate) {
                "ice-candidate requires candidate"
            }

            SignalingType.PEER_READY,
            SignalingType.LEAVE,
            SignalingType.ERROR,
            -> Unit
        }
    }

    private fun requireMessageSize(raw: String) {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES) {
            "Signaling message exceeds 64 KiB"
        }
    }

    private fun JSONObject.requiredString(name: String): String =
        optionalString(name)?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Missing or invalid '" + name + "'")

    private fun JSONObject.optionalString(name: String): String? {
        if (!has(name)) return null
        val value = opt(name)
        if (value === JSONObject.NULL) return null
        require(value is String) { "Invalid '" + name + "'" }
        return value
    }

    private fun JSONObject.optionalObject(name: String): JSONObject? {
        if (!has(name)) return null
        val value = opt(name)
        if (value === JSONObject.NULL) return null
        require(value is JSONObject) { "Invalid '" + name + "'" }
        return value
    }

    private fun JSONObject.requiredInt(name: String): Int {
        require(has(name) && !isNull(name)) { "Missing '" + name + "'" }
        val value = opt(name)
        val longValue = when (value) {
            is Int -> value.toLong()
            is Long -> value
            is BigInteger -> {
                require(value >= LONG_MIN && value <= LONG_MAX) {
                    "Invalid '" + name + "'"
                }
                value.toLong()
            }
            else -> throw IllegalArgumentException("Invalid '" + name + "'")
        }
        require(longValue in Int.MIN_VALUE..Int.MAX_VALUE) { "Invalid '" + name + "'" }
        return longValue.toInt()
    }

    companion object {
        const val MAX_MESSAGE_BYTES = 64 * 1024
        private val ROOM_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,32}")
        private val ALLOWED_ROLES = setOf("sender", "receiver")
        private val LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE)
        private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
    }
}

fun validateSignalingUrl(raw: String): String {
    val normalized = raw.trim()
    val uri = try {
        URI(normalized)
    } catch (error: Exception) {
        throw IllegalArgumentException("Invalid signaling URL", error)
    }
    require(uri.scheme == "ws" || uri.scheme == "wss") { "Signaling URL must use ws or wss" }
    require(!uri.host.isNullOrBlank()) { "Signaling URL must contain a host" }
    require(uri.port == -1 || uri.port in 1..65_535) { "Signaling URL port must be between 1 and 65535" }
    require(uri.userInfo == null && uri.fragment == null) {
        "Signaling URL must not contain credentials or fragments"
    }
    return normalized
}
