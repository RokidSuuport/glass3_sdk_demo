package com.rokid.phone.audio

import com.google.gson.Gson
import com.rokid.phone.data.CustomMessage

object AudioStreamControl {
    const val TYPE = "AUDIO_STREAM_CONTROL"
    private const val LEGACY_START = "AUDIO_STREAM_START"
    private const val LEGACY_STOP = "AUDIO_STREAM_STOP"

    enum class Action {
        START,
        STOP
    }

    fun decode(gson: Gson, payload: String): Action? {
        when (payload) {
            LEGACY_START -> return Action.START
            LEGACY_STOP -> return Action.STOP
        }

        val message = CustomMessage.fromClassicBtPayload(gson, payload) ?: return null
        if (message.type != TYPE) return null
        return Action.entries.firstOrNull { it.name == message.message }
    }
}
