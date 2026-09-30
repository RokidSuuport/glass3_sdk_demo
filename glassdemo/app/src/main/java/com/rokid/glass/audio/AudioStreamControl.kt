package com.rokid.glass.audio

import com.google.gson.Gson
import com.rokid.glass.bean.CustomMessage

object AudioStreamControl {
    const val TYPE = "AUDIO_STREAM_CONTROL"

    enum class Action {
        START,
        STOP
    }

    fun encode(gson: Gson, action: Action): String = gson.toJson(CustomMessage().apply {
        type = TYPE
        message = action.name
    })

    fun decode(gson: Gson, payload: String): Action? {
        val message = runCatching { gson.fromJson(payload, CustomMessage::class.java) }.getOrNull()
            ?: return null
        if (message.type != TYPE) return null
        return actionOf(message.message)
    }

    private fun actionOf(value: String): Action? = Action.entries.firstOrNull { it.name == value }
}

class AudioStreamSession {
    private var active = false

    @Synchronized
    fun markStarted(): Boolean {
        if (active) return false
        active = true
        return true
    }

    @Synchronized
    fun markStopped(): Boolean {
        if (!active) return false
        active = false
        return true
    }
}
