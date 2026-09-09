package com.rokid.glass.mediastream.sender.capture

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 将 16-bit 小端 PCM 计算为相对满幅的 RMS 音量（dBFS）。
 *
 * 该计算只遍历调用方传入的当前音频帧，不保存 PCM，也不执行文件 I/O，适合直接在
 * 音频回调中使用。结果始终是有限值，静音统一返回 [MIN_DBFS]，便于页面稳定显示。
 */
internal object AudioLevelMeter {
    const val MIN_DBFS = -96.0

    fun dbfs(pcm16LittleEndian: ByteArray): Double {
        val sampleCount = pcm16LittleEndian.size / BYTES_PER_SAMPLE
        if (sampleCount == 0) return MIN_DBFS

        var squaredSum = 0.0
        var offset = 0
        repeat(sampleCount) {
            val lowByte = pcm16LittleEndian[offset].toInt() and 0xff
            val highByte = pcm16LittleEndian[offset + 1].toInt() shl 8
            val sample = (highByte or lowByte).toShort().toInt().toDouble()
            squaredSum += sample * sample
            offset += BYTES_PER_SAMPLE
        }

        val rms = sqrt(squaredSum / sampleCount.toDouble())
        if (rms == 0.0) return MIN_DBFS

        val level = 20.0 * log10(rms / PCM16_FULL_SCALE)
        return level.coerceIn(MIN_DBFS, 0.0)
    }

    private const val BYTES_PER_SAMPLE = 2
    private const val PCM16_FULL_SCALE = 32_768.0
}
