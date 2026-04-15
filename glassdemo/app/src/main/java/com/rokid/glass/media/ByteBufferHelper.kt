package com.rokid.glass.media

import java.nio.ByteBuffer

/**
 * author: yichen.huang
 * email: yichen.huang@rokid.com
 * since: 2022/10/9    15:07
 * 用于编码过程中ByteBuffer的缓存，以降低GC频率
 *
 */
class ByteBufferHelper {

    private val mBuffers = ArrayList<ManagedByteBuffer>()

    @Synchronized
    fun allocateBuffer(size: Int): ManagedByteBuffer {
        if (mBuffers.isEmpty()) {
            return allocateNew(size)
        }
        var buffer: ManagedByteBuffer? = null
        mBuffers.forEach {
            if (it.originSize >= size) {
                buffer = it
                return@forEach
            }
        }
        buffer?.let {
            it.buffer.limit(size)
            it.buffer.position(0)
            mBuffers.remove(it)
            return it
        }
        return allocateNew(size)
    }

    @Synchronized
    fun recycle(managedByteBuffer: ManagedByteBuffer) {
        mBuffers.add(managedByteBuffer)
    }

    fun release() {
        mBuffers.clear()
    }

    private fun allocateNew(size: Int): ManagedByteBuffer {
        val buffer = ByteBuffer.allocate(size)
        return ManagedByteBuffer(buffer, size)
    }


    data class ManagedByteBuffer(val buffer: ByteBuffer, val originSize: Int)

}