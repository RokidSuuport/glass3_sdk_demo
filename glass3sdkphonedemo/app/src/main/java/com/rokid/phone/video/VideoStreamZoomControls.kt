package com.rokid.phone.video

import com.google.gson.JsonElement
import com.google.gson.JsonParser

/** Zoom status reported by the glasses, never inferred from local defaults. */
internal class VideoStreamZoomControls {
    data class PeerState(
        val capabilities: Int?,
        val appliedRatio: Float?,
        val announcedWidth: Int = 0,
        val announcedHeight: Int = 0,
        val announcedFps: Int = 0,
    ) {
        val supportsFloatZoom: Boolean get() = capabilities?.let { it and 1 != 0 } == true
    }

    var peerState = PeerState(null, null)
        private set

    fun resetConnection() { peerState = PeerState(null, null) }

    fun clearAppliedRatio() { peerState = peerState.copy(appliedRatio = null) }

    fun update(state: PeerState) { peerState = state }

    fun requestRatio(selectedRatio: Float?): Float? = selectedRatio?.takeIf {
        it.isFinite() && it in 1f..8f
    }

    companion object {
        private const val RESOLUTION_MESSAGE = "VIDEO_STREAM_RESOLUTION_CHANGE"

        /** Parse only a real transport envelope; unrelated custom messages cannot declare support. */
        fun parsePeerState(message: String): PeerState? = runCatching {
            // Status packets are small. Avoid parsing arbitrary media or large business payloads.
            if (message.length > 16_384 || !message.contains(RESOLUTION_MESSAGE)) return null
            val envelope = JsonParser.parseString(message).asJsonObject
            if (envelope.get("type")?.asString != RESOLUTION_MESSAGE) return null
            val extra = envelope.get("extra") ?: return null
            val body = when {
                extra.isJsonObject -> extra.asJsonObject
                extra.isJsonPrimitive && extra.asJsonPrimitive.isString ->
                    JsonParser.parseString(extra.asString).asJsonObject
                else -> return null
            }
            val caps = number(body.get("cameraZoomCapabilities"))?.let { value ->
                value.takeIf { it >= 0 && it <= Int.MAX_VALUE && it == it.toInt().toDouble() }?.toInt()
            }
            val ratio = number(body.get("appliedZoomRatio"))?.toFloat()?.takeIf { it.isFinite() && it >= 1f }
            PeerState(
                capabilities = caps,
                appliedRatio = ratio,
                announcedWidth = positiveInt(body.get("width")),
                announcedHeight = positiveInt(body.get("height")),
                announcedFps = positiveInt(body.get("fps")),
            )
        }.getOrNull()

        private fun number(value: JsonElement?): Double? = value
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asDouble?.takeIf { it.isFinite() }

        private fun positiveInt(value: JsonElement?): Int = number(value)
            ?.takeIf { it > 0 && it <= Int.MAX_VALUE && it == it.toInt().toDouble() }
            ?.toInt() ?: 0
    }
}
