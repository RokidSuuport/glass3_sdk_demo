package com.rokid.glass.mediastream.capture.internal.video

import com.rokid.glass.mediastream.capture.Nv21Frame

internal class FrameSlotPool(
    capacity: Int = DEFAULT_CAPACITY,
) {
    private class Slot {
        var data = ByteArray(0)
        var inUse = false
    }

    private val lock = Any()
    private val slots: List<Slot>
    private var lastTimestampNs = Long.MIN_VALUE
    private var acceptedCount = 0L
    private var droppedCount = 0L

    init {
        require(capacity > 0) { "capacity must be positive" }
        slots = List(capacity) { Slot() }
    }

    val acceptedFrames: Long
        get() = synchronized(lock) { acceptedCount }

    val droppedFrames: Long
        get() = synchronized(lock) { droppedCount }

    val inUseSlots: Int
        get() = synchronized(lock) { slots.count { it.inUse } }

    val allocatedSlotCount: Int
        get() = synchronized(lock) { slots.count { it.data.isNotEmpty() } }

    fun acquireFrame(
        source: ByteArray,
        length: Int,
        width: Int,
        height: Int,
        timestampNs: Long,
    ): Nv21Frame? {
        val frameSize = nv21FrameSize(width, height)
        require(length in frameSize..source.size) {
            "NV21 input is shorter than the expected frame size or exceeds its source"
        }

        val reservation = synchronized(lock) {
            val index = slots.indexOfFirst { !it.inUse }
            if (index < 0) {
                droppedCount += 1
                return null
            }
            val slot = slots[index]
            slot.inUse = true
            if (slot.data.size != frameSize) slot.data = ByteArray(frameSize)
            val adjustedTimestampNs = if (timestampNs > lastTimestampNs) {
                timestampNs
            } else {
                require(lastTimestampNs < Long.MAX_VALUE) { "timestamp cannot be advanced" }
                lastTimestampNs + 1
            }
            lastTimestampNs = adjustedTimestampNs
            acceptedCount += 1
            Triple(index, slot.data, adjustedTimestampNs)
        }

        System.arraycopy(source, 0, reservation.second, 0, frameSize)
        return Nv21Frame.create(
            data = reservation.second,
            width = width,
            height = height,
            timestampNs = reservation.third,
            finalRelease = { release(reservation.first) },
        )
    }

    private fun release(index: Int) {
        synchronized(lock) {
            slots[index].inUse = false
        }
    }

    private fun nv21FrameSize(width: Int, height: Int): Int {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "NV21 dimensions must be positive and even"
        }
        val byteCount = try {
            val pixelCount = Math.multiplyExact(width.toLong(), height.toLong())
            Math.multiplyExact(pixelCount, 3L) / 2L
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("NV21 frame is too large", error)
        }
        require(byteCount <= Int.MAX_VALUE) { "NV21 frame is too large" }
        return byteCount.toInt()
    }

    private companion object {
        const val DEFAULT_CAPACITY = 2
    }
}
