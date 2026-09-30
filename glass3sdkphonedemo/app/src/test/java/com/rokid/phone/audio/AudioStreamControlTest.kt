package com.rokid.phone.audio

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioStreamControlTest {
    private val gson = Gson()

    @Test
    fun `decodes structured controls`() {
        assertEquals(
            AudioStreamControl.Action.START,
            AudioStreamControl.decode(gson, "{\"type\":\"AUDIO_STREAM_CONTROL\",\"message\":\"START\"}")
        )
        assertEquals(
            AudioStreamControl.Action.STOP,
            AudioStreamControl.decode(gson, "{\"type\":\"AUDIO_STREAM_CONTROL\",\"message\":\"STOP\"}")
        )
    }

    @Test
    fun `keeps legacy raw controls compatible`() {
        assertEquals(AudioStreamControl.Action.START, AudioStreamControl.decode(gson, "AUDIO_STREAM_START"))
        assertEquals(AudioStreamControl.Action.STOP, AudioStreamControl.decode(gson, "AUDIO_STREAM_STOP"))
    }

    @Test
    fun `ignores arbitrary text malformed json and unknown actions`() {
        assertNull(AudioStreamControl.decode(gson, "plain text"))
        assertNull(AudioStreamControl.decode(gson, "{"))
        assertNull(AudioStreamControl.decode(gson, "{\"type\":\"AUDIO_STREAM_CONTROL\",\"message\":\"PAUSE\"}"))
    }
}
