package com.rokid.phone.video

import java.nio.ByteBuffer

object H264NalUnitInspector {
    fun containsFrame(source: ByteBuffer): Boolean {
        val data = source.duplicate()
        if (!data.hasRemaining()) return false
        val bytes = ByteArray(data.remaining())
        data.get(bytes)

        var foundStartCode = false
        var index = 0
        while (index <= bytes.size - 4) {
            val startCodeSize = when {
                bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() &&
                    bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte() -> 4
                bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() &&
                    bytes[index + 2] == 1.toByte() -> 3
                else -> 0
            }
            if (startCodeSize > 0) {
                foundStartCode = true
                val nalIndex = index + startCodeSize
                if (nalIndex < bytes.size && isFrameNal(bytes[nalIndex])) return true
                index = nalIndex
            } else {
                index++
            }
        }
        return !foundStartCode && isFrameNal(bytes[0])
    }

    private fun isFrameNal(header: Byte): Boolean = (header.toInt() and 0x1F) in 1..5
}
