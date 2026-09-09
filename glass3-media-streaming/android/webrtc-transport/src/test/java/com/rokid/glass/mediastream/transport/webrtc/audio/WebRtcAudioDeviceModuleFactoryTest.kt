package com.rokid.glass.mediastream.transport.webrtc.audio

import java.nio.ByteBuffer
import livekit.org.webrtc.audio.JavaAudioDeviceModule.AudioBufferCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class WebRtcAudioDeviceModuleFactoryTest {
    @Test
    fun `default module is built for glass pcm before android recording is disabled and playback muted`() {
        val calls = mutableListOf<String>()
        val callback = AudioBufferCallback { _: ByteBuffer, _: Int, _: Int, _: Int, _: Int, _: Long -> 0L }
        val fakeModule = Any()

        val result = WebRtcAudioDeviceModuleFactory.configureCreatedModule(
            config = AudioDeviceModuleConfig(),
            audioBufferCallback = callback,
            create = { sampleRateHz, stereoInput, actualCallback ->
                calls += "create:$sampleRateHz:$stereoInput"
                assertSame(callback, actualCallback)
                fakeModule
            },
            setAudioRecordEnabled = { module, enabled ->
                assertSame(fakeModule, module)
                calls += "record:$enabled"
            },
            setSpeakerMute = { module, muted ->
                assertSame(fakeModule, module)
                calls += "speaker:$muted"
            },
        )

        assertSame(fakeModule, result)
        assertEquals(listOf("create:16000:false", "record:false", "speaker:true"), calls)
    }

    @Test
    fun `audio device configuration cannot enable a second recorder or change glass pcm format`() {
        val defaults = AudioDeviceModuleConfig()
        assertEquals(16_000, defaults.inputSampleRateHz)
        assertFalse(defaults.stereoInput)
        assertFalse(defaults.recordEnabled)

        assertThrows(IllegalArgumentException::class.java) {
            AudioDeviceModuleConfig(inputSampleRateHz = 48_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioDeviceModuleConfig(stereoInput = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioDeviceModuleConfig(recordEnabled = true)
        }
    }
}
