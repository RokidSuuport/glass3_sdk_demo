package com.rokid.glass.mediastream.transport.webrtc.audio

import android.content.Context
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import livekit.org.webrtc.audio.JavaAudioDeviceModule.AudioBufferCallback

internal data class AudioDeviceModuleConfig(
    val inputSampleRateHz: Int = INPUT_SAMPLE_RATE_HZ,
    val stereoInput: Boolean = false,
    val recordEnabled: Boolean = false,
) {
    init {
        require(inputSampleRateHz == INPUT_SAMPLE_RATE_HZ) {
            "External Glass PCM must use 16 kHz"
        }
        require(!stereoInput) { "External Glass PCM must be mono" }
        require(!recordEnabled) { "WebRTC AudioRecord must remain disabled" }
    }

    private companion object {
        const val INPUT_SAMPLE_RATE_HZ = 16_000
    }
}

internal object WebRtcAudioDeviceModuleFactory {
    fun create(
        context: Context,
        audioAdapter: WebRtcAudioAdapter,
        config: AudioDeviceModuleConfig = AudioDeviceModuleConfig(),
    ): JavaAudioDeviceModule = configureCreatedModule(
        config = config,
        audioBufferCallback = audioAdapter,
        create = { inputSampleRateHz, stereoInput, callback ->
            JavaAudioDeviceModule.builder(context.applicationContext)
                .setInputSampleRate(inputSampleRateHz)
                .setUseStereoInput(stereoInput)
                .setAudioBufferCallback(callback)
                .createAudioDeviceModule()
        },
        setAudioRecordEnabled = { module, enabled ->
            check(!enabled) { "WebRTC AudioRecord must remain disabled" }
            module.setAudioRecordEnabled(false)
        },
        setSpeakerMute = { module, muted ->
            check(muted) { "WebRTC playback must remain muted" }
            module.setSpeakerMute(true)
        },
    )

    internal fun <Module> configureCreatedModule(
        config: AudioDeviceModuleConfig,
        audioBufferCallback: AudioBufferCallback,
        create: (inputSampleRateHz: Int, stereoInput: Boolean, AudioBufferCallback) -> Module,
        setAudioRecordEnabled: (Module, Boolean) -> Unit,
        setSpeakerMute: (Module, Boolean) -> Unit,
    ): Module {
        val module = create(
            config.inputSampleRateHz,
            config.stereoInput,
            audioBufferCallback,
        )
        setAudioRecordEnabled(module, config.recordEnabled)
        setSpeakerMute(module, true)
        return module
    }
}
