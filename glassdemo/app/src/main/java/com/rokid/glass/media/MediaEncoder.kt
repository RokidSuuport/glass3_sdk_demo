package com.rokid.glass.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.WorkerThread
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 对音频和视频等媒体编码的抽象类
 * @param callback 回调
 */
abstract class MediaEncoder(private val callback: Callback,private val bufferHelper: ByteBufferHelper) {

    interface Callback {
        /**
         * 回调开始
         * @param mediaType 媒体类型
         * @param mediaFormat 媒体格式
         */
        @WorkerThread
        fun onStarted(mediaType: MediaType, mediaFormat: MediaFormat)

        /**
         * 编码回调
         * @param trackId 媒体轨ID
         * @param buffer 编码后的缓冲区
         * @param bufferInfo 缓冲区信息
         */
        @WorkerThread
        fun onEncodedBuffer(
            trackId: Int,
            buffer: ByteBuffer,
            bufferInfo: MediaCodec.BufferInfo
        )

        /**
         * 编码过程结束.
         * @param t 异常终止原因，正常终止为null
         */
        @WorkerThread
        fun onFinished(t: Throwable?)
    }

    companion object {
        // 出队超时 [us]
        private const val CODEC_DEQUEUE_TIMEOUT_US = 10 * 1000L
    }

    // 媒体类型
    protected abstract val mediaType: MediaType

    // 媒体编解码器
    protected abstract val mediaCodec: MediaCodec

    // 媒体轨ID
    var trackId: Int = -1

    // 响应时间戳 [us]
    private var resTimeStampUs = -1L

    // 开始时间 时间戳 [us]
    private var firstTimeStampUs = -1L

    // 可用编码
    // INFO_OUTPUT_FORMAT_CHANGED  true
    var isFormatChanged = false
        private set

    protected var isEncoding = false
        private set

    protected var isCalledEndStream = false

    protected val lockEnqueue = ReentrantLock()

    private val lockDequeue = ReentrantLock()

    private val mThread = HandlerThread("MediaEncoder")

    private val mHandler: Handler by lazy {
        mThread.start()
        Handler(mThread.looper)
    }

    /**
     * 开始编码.
     */
    fun start() {
        if (isEncoding) {
            logI("Encode is already started.")
            return
        }
        logI("Start encoder.")
        try {
            // 编解码器开始处理.
            mediaCodec.start()
            // 启动 dequeue 线程。启动一个单独的线程专门用于 dequeue.
            mHandler.post {
                dequeueBuffer()
            }
        } catch (e: Exception) {
            logE("Failed to start mediaCodec.")
            callback.onFinished(e)
        }
    }

    /**
     * 编码结束.
     */
    fun stop() {
        if (!isEncoding) {
            logI("Encode is already stopped.")
            return
        }
        logI("Stop encoder.")
        enqueueEndStream()
    }

    /**
     * 释放
     */
    protected open fun release(t: Throwable?) {
        logI("Release encoder $this" )
        try {
            mediaCodec.stop()
            mediaCodec.release()
        } catch (e: IllegalStateException) {
            logE("MediaCodec is already released.")
        } finally {
            trackId = -1
            resTimeStampUs = -1L
            firstTimeStampUs = -1L
            isFormatChanged = false
            isEncoding = false
            isCalledEndStream = false

            callback.onFinished(t)
            mThread.quit()
        }
    }

    /**
     * 将结束流加入队列
     */
    @WorkerThread
    protected abstract fun enqueueEndStream()

    /**
     * 将缓冲区出列
     */
    @WorkerThread
    private fun dequeueBuffer() {
        logI("Start dequeue.")
        isEncoding = true
        var t: Throwable? = null
        try {
            val bufferInfo = MediaCodec.BufferInfo()
            while (isEncoding) {
                lockDequeue.withLock {
                    val indexOrStatus =
                        mediaCodec.dequeueOutputBuffer(bufferInfo, CODEC_DEQUEUE_TIMEOUT_US)

                    if (indexOrStatus < 0) {
                        dequeueBufferStatus(indexOrStatus)
                    } else {
                        dequeueBufferIndex(indexOrStatus, bufferInfo)
                        // 如果流结束标志则终止
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            logI("Flag is BUFFER_FLAG_END_OF_STREAM  $this")
                            isEncoding = false
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logE("Failed to dequeue buffer.")
            t = e
        } finally {
            logI("End dequeue. resTimeStampUs: $resTimeStampUs")
            release(t)
        }
    }

    fun checkForRelease() {
        if (isEncoding) {
            release(null)
        }
    }


    @WorkerThread
    private fun dequeueBufferStatus(status: Int) {
        when (status) {
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                logI("flag is INFO_OUTPUT_FORMAT_CHANGED")
                isFormatChanged = true
                callback.onStarted(mediaType, mediaCodec.outputFormat)
            }
            else -> {}
        }
    }

    @WorkerThread
    private fun dequeueBufferIndex(index: Int, bufferInfo: MediaCodec.BufferInfo) {
//        logI("dequeueBufferIndex: $index")
        try {
            val buffer = mediaCodec.getOutputBuffer(index) ?: return
            if (bufferInfo.size < 0) {
                return
            }
            // 输出编解码器设置信息时的标志，因为不是编码结果，所以跳过
            // 当设置在编码开始和处理过程中发生变化时，输出此标志
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                logI("flag is BUFFER_FLAG_CODEC_CONFIG")
                return
            }

            if (firstTimeStampUs < 0L) {
                // 记录时间戳开始时间
                firstTimeStampUs = System.nanoTime() / 1000
            }

            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                // 编码结束时该值可能有问题，因此在最后一个时间戳上加 1
                bufferInfo.presentationTimeUs = resTimeStampUs + 1L
            } else {
                // 通过减去开始时间来重置时间戳
                val timestamp = (System.nanoTime() / 1000 - firstTimeStampUs)
                if (timestamp < resTimeStampUs) {
                    // 时间戳存在问题
                    logE("Invalid timestamp. before: $timestamp, after: $resTimeStampUs")
                    return
                }
                resTimeStampUs = timestamp
                bufferInfo.presentationTimeUs = resTimeStampUs
            }
//            logI("BufferSize: ${bufferInfo.size} TimeStamp: ${bufferInfo.presentationTimeUs}")
            val managedBuffer= bufferHelper.allocateBuffer(buffer.capacity())
            // 写入缓冲区.
            val copyBuffer = managedBuffer.buffer.put(buffer)
            callback.onEncodedBuffer(trackId, copyBuffer, bufferInfo)
            bufferHelper.recycle(managedBuffer)
        } finally {
            mediaCodec.releaseOutputBuffer(index, false)
        }
    }

    fun mediaFormat() = mediaCodec.outputFormat

}