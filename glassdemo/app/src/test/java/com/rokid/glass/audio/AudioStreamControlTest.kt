package com.rokid.glass.audio

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioStreamControlTest {
    private val gson = Gson()

    @Test
    fun `encodes start and stop as custom message json`() {
        assertEquals(
            AudioStreamControl.Action.START,
            AudioStreamControl.decode(gson, AudioStreamControl.encode(gson, AudioStreamControl.Action.START))
        )
        assertEquals(
            AudioStreamControl.Action.STOP,
            AudioStreamControl.decode(gson, AudioStreamControl.encode(gson, AudioStreamControl.Action.STOP))
        )
    }

    @Test
    fun `ignores unrelated and malformed payloads`() {
        assertNull(AudioStreamControl.decode(gson, "plain text"))
        assertNull(AudioStreamControl.decode(gson, "{\"type\":\"OTHER\",\"message\":\"START\"}"))
    }

    @Test
    fun `session only starts and stops once`() {
        val session = AudioStreamSession()
        assertTrue(session.markStarted())
        assertFalse(session.markStarted())
        assertTrue(session.markStopped())
        assertFalse(session.markStopped())
    }
}
