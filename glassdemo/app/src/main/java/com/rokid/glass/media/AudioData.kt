package com.rokid.glass.media

import android.media.AudioFormat

/**
 * @param samplingRate 采样率
 * @param bitRate 比特率
 * @param bytesPerSample 采样大小
 */
data class AudioData(
    val samplingRate: Int = 16000,
    val bitRate: Int = 64000,
    val bytesPerSample: Int = AudioFormat.ENCODING_PCM_16BIT,
    val enable:Boolean = true
) {

    fun isAvailable(): Boolean =
        samplingRate > 0 && bytesPerSample > 0 && bitRate > 0
}