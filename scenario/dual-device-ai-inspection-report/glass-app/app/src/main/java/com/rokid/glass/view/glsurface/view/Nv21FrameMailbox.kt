package com.rokid.glass.view.glsurface.view

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Keeps at most one frame waiting for GL. A faster producer replaces the pending frame instead
 * of queuing work and increasing preview latency.
 */
internal class Nv21FrameMailbox {
    data class Frame(
        val y: ByteBuffer,
        val uv: ByteBuffer,
        val width: Int,
        val height: Int,
    )

    private data class Buffers(
        val y: ByteBuffer,
        val uv: ByteBuffer,
        var width: Int,
        var height: Int,
    )

    private var pending: Buffers? = null
    private var rendering: Buffers? = null
    private var spare: Buffers? = null

    var receivedFrameCount: Long = 0
        private set
    var renderedFrameCount: Long = 0
        private set
    var droppedFrameCount: Long = 0
        private set

    @Synchronized
    fun submit(data: ByteArray, width: Int, height: Int) {
        val ySize = checkedYSize(width, height)
        val uvSize = ySize / 2
        require(data.size >= ySize + uvSize) { "NV21 frame is smaller than declared dimensions" }
        val target = writableBuffers(width, height, ySize, uvSize)
        target.y.clear()
        target.y.put(data, 0, ySize)
        target.y.flip()
        target.uv.clear()
        target.uv.put(data, ySize, uvSize)
        target.uv.flip()
        pending = target
        receivedFrameCount++
    }

    @Synchronized
    fun acquireLatest(): Frame? {
        val next = pending ?: return null
        pending = null
        spare = rendering
        rendering = next
        renderedFrameCount++
        return Frame(
            next.y.asReadOnlyBuffer().apply { position(0) },
            next.uv.asReadOnlyBuffer().apply { position(0) },
            next.width,
            next.height,
        )
    }

    @Synchronized
    fun clear() {
        pending = null
        rendering = null
        spare = null
        receivedFrameCount = 0
        renderedFrameCount = 0
        droppedFrameCount = 0
    }

    private fun checkedYSize(width: Int, height: Int): Int {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "NV21 dimensions must be positive even numbers"
        }
        val size = width.toLong() * height.toLong()
        require(size <= Int.MAX_VALUE) { "NV21 frame dimensions are too large" }
        return size.toInt()
    }

    private fun writableBuffers(width: Int, height: Int, ySize: Int, uvSize: Int): Buffers {
        val target = pending?.also {
            droppedFrameCount++
        } ?: spare?.takeIf { it.y.capacity() == ySize && it.uv.capacity() == uvSize }?.also {
            spare = null
        } ?: Buffers(
            ByteBuffer.allocateDirect(ySize).order(ByteOrder.nativeOrder()),
            ByteBuffer.allocateDirect(uvSize).order(ByteOrder.nativeOrder()),
            width,
            height,
        )
        target.width = width
        target.height = height
        return target
    }
}
