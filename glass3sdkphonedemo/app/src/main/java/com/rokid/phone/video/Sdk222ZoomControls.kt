package com.rokid.phone.video

import com.google.gson.JsonElement
import com.google.gson.JsonParser

/** Camera capability evidence from the peer, never from a model's default values. */
internal class Sdk222ZoomControls {
    data class PeerState(val capabilities: Int?, val appliedRatio: Float?) {
        val supportsFloatZoom: Boolean get() = capabilities?.let { it and 1 != 0 } == true
    }

    var peerState = PeerState(null, null)
        private set

    fun resetConnection() { peerState = PeerState(null, null) }

    fun clearAppliedRatio() { peerState = peerState.copy(appliedRatio = null) }

    fun update(state: PeerState) { peerState = state }

    fun requestRatio(selectedRatio: Float?): Float? = selectedRatio?.takeIf {
        peerState.supportsFloatZoom && it.isFinite() && it >= 1f
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
            PeerState(caps, ratio)
        }.getOrNull()

        private fun number(value: JsonElement?): Double? = value
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asDouble?.takeIf { it.isFinite() }
    }
}
