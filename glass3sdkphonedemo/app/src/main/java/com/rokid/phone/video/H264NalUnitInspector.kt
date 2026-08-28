package com.rokid.phone.video

import java.nio.ByteBuffer

object H264NalUnitInspector {
    fun containsFrame(source: ByteBuffer): Boolean {
        return containsNalType(source) { it in 1..5 }
    }

    fun containsIdr(source: ByteBuffer): Boolean {
        return containsNalType(source) { it == 5 }
    }

    private fun containsNalType(source: ByteBuffer, predicate: (Int) -> Boolean): Boolean {
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
                if (nalIndex < bytes.size && predicate(nalType(bytes[nalIndex]))) return true
                index = nalIndex
            } else {
                index++
            }
        }
        if (foundStartCode) return false

        var offset = 0
        var parsedLengthPrefixedNal = false
        while (offset + 4 < bytes.size) {
            val nalLength =
                ((bytes[offset].toInt() and 0xFF) shl 24) or
                    ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                    (bytes[offset + 3].toInt() and 0xFF)
            val nalOffset = offset + 4
            if (nalLength <= 0 || nalOffset + nalLength > bytes.size) break
            parsedLengthPrefixedNal = true
            if (predicate(nalType(bytes[nalOffset]))) return true
            offset = nalOffset + nalLength
        }
        if (parsedLengthPrefixedNal && offset == bytes.size) return false

        return predicate(nalType(bytes[0]))
    }

    private fun nalType(header: Byte): Int = header.toInt() and 0x1F
}
