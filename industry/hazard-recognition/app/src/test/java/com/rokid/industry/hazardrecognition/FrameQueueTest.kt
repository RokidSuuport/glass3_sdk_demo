package com.rokid.industry.hazardrecognition

import org.junit.Assert.*
import org.junit.Test

/** 画面去重用例：使用内存中的小型 NV21 帧和手动时间戳，无需相机、网络或等待真实时间。 */
class FrameQueueTest {
    private fun frame(y: Int, at: Long = 1000, chroma: Int = 128) = Nv21Frame(
        ByteArray(64 * 36 * 3 / 2) { if (it < 64 * 36) y.toByte() else chroma.toByte() }, 64, 36, at, at)

    // 用例：轻微亮度变化跳过，明显不同的画面进入下一批。
    @Test fun similarFramesAreSkippedButChangedFramesAreQueued() {
        val queue = FrameQueue()
        queue.offer(frame(50), 1000); assertEquals(1, queue.begin(1000).size)
        queue.offer(frame(52), 1000); queue.complete(1000, true)
        assertTrue(queue.begin(1000).isEmpty())
        queue.offer(frame(52), 1000); assertTrue(queue.begin(1000).isEmpty())
        queue.offer(frame(180), 1000); assertEquals(1, queue.begin(1000).size)
    }
    // 用例：模拟慢网络：请求中只保留最新三张不同候选帧。
    @Test fun onlyLatestThreeDistinctFramesSurviveSlowNetwork() {
        val queue = FrameQueue()
        queue.offer(frame(0), 1000); queue.begin(1000)
        for (y in listOf(40, 80, 120, 160, 200)) queue.offer(frame(y), 1000)
        assertTrue(queue.begin(1000).isEmpty())
        queue.complete(1000, true)
        assertEquals(listOf(120, 160, 200), queue.begin(1000).map { it.frame.bytes[0].toInt() and 255 })
    }
    // 用例：请求失败允许重试；超过 2 秒的旧帧禁止上传。
    @Test fun failuresAreNotCachedAndStaleFramesNeverUpload() {
        val queue = FrameQueue()
        queue.offer(frame(100), 1000); queue.begin(1000); queue.complete(1000, false)
        queue.offer(frame(100, 2000), 2000); assertEquals(1, queue.begin(2000).size)
        queue.complete(2000, false)
        queue.offer(frame(120, 2000), 2000); assertTrue(queue.begin(4001).isEmpty())
    }
    // 用例：成功缓存 30 秒到期后复查；force=true 允许用户提前复检。
    @Test fun cacheExpiresAndManualChecksBypassIt() {
        val queue = FrameQueue()
        queue.offer(frame(100), 1000); queue.begin(1000); queue.complete(1000, true)
        queue.offer(frame(100, 31000), 31000); assertEquals(1, queue.begin(31000).size)
        queue.complete(31000, true)
        queue.offer(frame(100, 32000), 32000, force = true); assertEquals(1, queue.begin(32000).size)
    }
    // 用例：无法判断时 3 秒后复查；颜色变化也参与画面比较。
    @Test fun uncertainResultsRecheckSoonerAndChromaChangesAreNotLost() {
        val queue = FrameQueue()
        queue.offer(frame(100), 1000); queue.begin(1000); queue.complete(1000, true, uncertain = true)
        queue.offer(frame(100, 4000), 4000); assertEquals(1, queue.begin(4000).size)
        assertFalse(FrameSignature.from(frame(100)).similar(FrameSignature.from(frame(100, chroma = 200))))
    }
    // 用例：切换会话清空队列；重复候选帧使用最新时间戳。
    @Test fun clearDropsOldSessionAndPendingDuplicatesRefreshTimestamp() {
        val queue = FrameQueue()
        queue.offer(frame(100), 1000); queue.offer(frame(101, 3000), 3000)
        assertEquals(3000L, queue.begin(4000).single().frame.receivedAt)
        queue.clear(); assertTrue(queue.begin(4000).isEmpty())
        queue.offer(frame(100, 4000), 4000); assertEquals(1, queue.begin(4000).size)
    }

    // 用例：只有成功识别过的隐患才能提示已收录，进行中和失败均不算。
    @Test fun duplicateIsCollectedOnlyAfterSuccessfulRecognition() {
        val queue = FrameQueue()
        assertEquals(FrameOffer.QUEUED, queue.offer(frame(100), 1000))
        queue.begin(1000)
        assertEquals(FrameOffer.IN_PROGRESS, queue.offer(frame(100), 1000))
        queue.complete(1000, false)
        assertEquals(FrameOffer.QUEUED, queue.offer(frame(100), 1000))
        queue.begin(1000)
        queue.complete(1000, true, hazardFrameIndices = setOf(0))
        assertEquals(FrameOffer.KNOWN_HAZARD, queue.offer(frame(100, 2000), 2000))
    }

    // 用例：无隐患画面重复仅提示已识别，不能提示隐患已收录。
    @Test fun aRepeatedClearFrameIsNotReportedAsACollectedHazard() {
        val queue = FrameQueue()
        queue.offer(frame(100), 1000)
        queue.begin(1000)
        queue.complete(1000, true)
        assertEquals(FrameOffer.KNOWN_FRAME, queue.offer(frame(100, 2000), 2000))
    }
}
