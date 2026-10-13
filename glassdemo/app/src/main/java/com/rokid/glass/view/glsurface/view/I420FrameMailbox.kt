package com.rokid.glass.view.glsurface.view

import java.nio.ByteBuffer

/**
 * I420 预览专用副本：在租约 close 前按 stride 复制有效像素，GL 线程只读取本地内存。
 * 这次复制是 Demo 显示画面的成本；真正支持 I420 租约的消费者可直接持有原平面至最终释放。
 * 最多保留绘制中、待绘制、可复用三组缓冲；生产更快时覆盖待绘制帧，不积压预览延迟。
 */
internal class I420FrameMailbox {
    data class Frame(val y: ByteBuffer, val u: ByteBuffer, val v: ByteBuffer, val width: Int, val height: Int)
    private var pending: Frame? = null
    private var drawing: Frame? = null
    private var spare: Frame? = null
    @Volatile var receivedFrameCount = 0L
        private set
    @Volatile var droppedFrameCount = 0L
        private set

    @Synchronized fun submit(y: ByteBuffer, u: ByteBuffer, v: ByteBuffer,
        width: Int, height: Int, strideY: Int, strideU: Int, strideV: Int) {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "I420 dimensions must be positive even numbers"
        }
        val size = width.toLong() * height
        require(size <= Int.MAX_VALUE) { "I420 dimensions are too large" }
        // Validate all planes before modifying a pending frame, including a short last row.
        validate(y, strideY, width, height)
        validate(u, strideU, width / 2, height / 2)
        validate(v, strideV, width / 2, height / 2)
        val ySize = size.toInt()
        val candidate = pending ?: spare.also { spare = null }
        val target = candidate?.takeIf { it.y.capacity() == ySize }?.copy(width = width, height = height)
            ?: Frame(ByteBuffer.allocateDirect(ySize), ByteBuffer.allocateDirect(ySize / 4),
                ByteBuffer.allocateDirect(ySize / 4), width, height)
        copyPlane(y, strideY, width, height, target.y)
        copyPlane(u, strideU, width / 2, height / 2, target.u)
        copyPlane(v, strideV, width / 2, height / 2, target.v)
        if (pending != null) droppedFrameCount++
        pending = target
        receivedFrameCount++
    }

    /** Only the GL thread calls this. Redraws retain the image until clear (e.g. after layout changes). */
    @Synchronized fun frameForDraw(): Frame? {
        pending?.let { next ->
            spare = drawing
            drawing = next
            pending = null
        }
        return drawing?.let { it.copy(y = it.y.asReadOnlyBuffer(), u = it.u.asReadOnlyBuffer(), v = it.v.asReadOnlyBuffer()) }
    }

    @Synchronized fun clear() {
        pending = null; drawing = null; spare = null
        receivedFrameCount = 0; droppedFrameCount = 0
    }

    private fun validate(data: ByteBuffer, stride: Int, columns: Int, rows: Int) {
        require(stride >= columns && (rows - 1L) * stride + columns <= data.remaining().toLong()) {
            "I420 plane is smaller than its visible rows or stride"
        }
    }

    private fun copyPlane(source: ByteBuffer, stride: Int, columns: Int, rows: Int, target: ByteBuffer) {
        val data = source.duplicate()
        val start = data.position()
        target.clear()
        repeat(rows) { row ->
            data.limit(start + row * stride + columns)
            data.position(start + row * stride)
            target.put(data)
        }
        target.flip()
    }
}
