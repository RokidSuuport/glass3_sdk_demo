package com.rokid.glass.media

import android.os.Environment
import com.rokid.glass.utils.demoString
import com.rokid.glesse.R
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.system.server.media.callback.AudioCallback
import java.io.File

/**
 * 设备工具页拥有的音频采集会话；从该页离开时停止并归还编码器。
 * Audio capture owned by Device tools; leaving the page stops it and releases the encoder.
 */
internal class DeviceAudioCapture(private val log: (String) -> Unit) {
    private val audioLock = Any()
    private var audioEncoder: WorkAudioEncoder? = null
    private var capturingAudio = false
    private val audioCallback = object : AudioCallback.Stub() {
        override fun getCallbackId() = "demo-device-audio-${System.identityHashCode(this@DeviceAudioCapture)}"

        override fun onAudioStream(buffer: ByteArray?, bufferLen: Int) {
            if (buffer == null || bufferLen <= 0 || bufferLen > buffer.size) return
            // 编码与停止串行，避免释放正在消费的编码器。
            // Serialize encoding and stop to avoid releasing an encoder while it consumes a frame.
            synchronized(audioLock) { audioEncoder?.encode(buffer, bufferLen) }
        }
    }

    fun start() {
        if (capturingAudio) return
        val service = GlassSdk.getGlassMediaService()
            ?: error(demoString(R.string.device_audio_media_service_unavailable))
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val path = File(directory, "audio_${System.currentTimeMillis()}.m4a").absolutePath
        synchronized(audioLock) { audioEncoder = WorkAudioEncoder().apply { start(path) } }
        capturingAudio = true
        try { service.startAudioRecord(audioCallback) }
        catch (error: Exception) { stop(); throw error }
        log("${demoString(R.string.demo_starting_audio_recording)}\n$path")
    }

    fun stop() {
        if (!capturingAudio) return
        capturingAudio = false
        val encoder = synchronized(audioLock) { audioEncoder.also { audioEncoder = null } }
        try { GlassSdk.getGlassMediaService()?.stopAudioRecord(audioCallback) }
        finally { encoder?.stop() }
    }
}
