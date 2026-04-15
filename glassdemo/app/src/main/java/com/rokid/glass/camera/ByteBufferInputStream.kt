package com.rokid.glass.camera

import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Author: zhangshengwei
 * Date: 2025/8/5
 */
class ByteBufferInputStream(private val buffer: ByteBuffer) : InputStream() {

    /**
     * 读取单个字节
     * @return 字节值（0-255），若已读完返回 -1
     */
    override fun read(): Int {
        return if (buffer.hasRemaining()) {
            // 将字节转换为无符号整数（0-255）
            buffer.get().toInt() and 0xFF
        } else {
            -1 // 已到达缓冲区末尾
        }
    }

    /**
     * 批量读取字节到数组
     * @param b 目标字节数组
     * @param off 数组起始偏移量
     * @param len 最大读取长度
     * @return 实际读取的字节数，若已读完返回 -1
     */
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (!buffer.hasRemaining()) {
            return -1
        }
        // 计算实际可读取的长度（不超过缓冲区剩余量和请求长度）
        val bytesToRead = minOf(len, buffer.remaining())
        buffer.get(b, off, bytesToRead)
        return bytesToRead
    }

    /**
     * 跳过指定字节数
     * @param n 要跳过的字节数
     * @return 实际跳过的字节数
     */
    override fun skip(n: Long): Long {
        val skipBytes = minOf(n, buffer.remaining().toLong()).toInt()
        buffer.position(buffer.position() + skipBytes)
        return skipBytes.toLong()
    }

    /**
     * 查看剩余可读取的字节数
     */
    override fun available(): Int = buffer.remaining()

    /**
     * 标记当前位置（ByteBuffer 本身支持标记，这里直接使用）
     */
    override fun mark(readlimit: Int) {
        buffer.mark()
    }

    /**
     * 重置到上次标记的位置
     */
    override fun reset() {
        buffer.reset()
    }

    /**
     * 是否支持标记/重置操作（这里返回 true）
     */
    override fun markSupported(): Boolean = true
}