package com.rokid.glass.media;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

public final class BufferPool {

    private static final int MAX_POOL_SIZE = 10;
    private static final ArrayDeque<ByteBuffer> pool = new ArrayDeque<>();

    public static synchronized ByteBuffer obtain(int size) {
        ByteBuffer buffer;
        if (!pool.isEmpty()) {
            buffer = pool.poll();
            if (buffer.capacity() < size) {
                buffer = ByteBuffer.allocateDirect(size);
            }
        } else {
            buffer = ByteBuffer.allocateDirect(size);
        }
        buffer.clear();
        return buffer;
    }

    public static synchronized void recycle(ByteBuffer buffer) {
        if (buffer == null || pool.size() >= MAX_POOL_SIZE) return;
        buffer.clear();
        pool.offer(buffer);
    }
}
