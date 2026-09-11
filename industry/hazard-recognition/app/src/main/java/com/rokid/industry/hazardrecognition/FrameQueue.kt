package com.rokid.industry.hazardrecognition

import kotlin.math.abs

/** 用例：直接从 NV21 抽取 32×18 个区域的 Y/V/U 特征，先在眼镜端筛帧，无需先生成 JPEG/Bitmap。 */
class FrameSignature private constructor(private val values: IntArray) {
    // 默认阈值用于上传前去重；loose=true 仅用于隐患合并，还必须结合位置、类别等条件。
    // 这是轻量近似比较，不是物体跟踪；调整阈值时应使用现场移动、光照变化的样本验证。
    fun similar(other: FrameSignature, loose: Boolean = false): Boolean {
        var total = 0L
        var changed = 0
        for (i in values.indices) {
            val difference = abs(values[i] - other.values[i])
            total += difference
            if (difference > if (loose) 35 else 18) changed++
        }
        return total.toDouble() / values.size <= (if (loose) 12.0 else 5.0) &&
            changed.toDouble() / values.size <= (if (loose) 0.18 else 0.025)
    }

    companion object {
        fun from(frame: Nv21Frame): FrameSignature {
            val w = frame.width
            val h = frame.height
            val values = IntArray(32 * 18 * 3)
            var index = 0
            for (row in 0 until 18) for (col in 0 until 32) {
                var y = 0; var v = 0; var u = 0
                for (sy in 0..3) for (sx in 0..3) {
                    val px = ((col + (sx + 0.5) / 4) * w / 32).toInt().coerceIn(0, w - 1)
                    val py = ((row + (sy + 0.5) / 4) * h / 18).toInt().coerceIn(0, h - 1)
                    // NV21 前 w*h 字节为 Y，后面按 V、U 交错排列，每个色度值对应 2×2 像素。
                    val uv = w * h + (py / 2) * w + (px / 2) * 2
                    y += frame.bytes[py * w + px].toInt() and 255
                    v += frame.bytes[uv].toInt() and 255
                    u += frame.bytes[uv + 1].toInt() and 255
                }
                values[index++] = y / 16
                values[index++] = v / 16
                values[index++] = u / 16
            }
            return FrameSignature(values)
        }
    }
}

data class FrameSample(val frame: Nv21Frame, val signature: FrameSignature)

/** 用例：根据 offer 的状态更新提示，只有 KNOWN_HAZARD 才表示重复隐患已收录。 */
enum class FrameOffer { QUEUED, IN_PROGRESS, KNOWN_HAZARD, KNOWN_FRAME, STALE }

/**
 * 用例顺序：offer(最新帧, 单调时钟) → begin(单调时钟) → 请求完成后 complete(...)。
 * 由主线程持有，最多等待三帧且只允许一批请求进行中；更换相机会话时调用 clear()。
 */
class FrameQueue {
    private data class CachedFrame(val signature: FrameSignature, val expiresAt: Long, val hasHazard: Boolean)
    private val pending = mutableListOf<FrameSample>()
    private var inFlight = emptyList<FrameSample>()
    private val recent = ArrayDeque<CachedFrame>()
    var skipped = 0
        private set

    /** 自动采样使用默认 force=false；用户主动复检使用 force=true，绕过已完成/进行中画面缓存。 */
    fun offer(frame: Nv21Frame, now: Long, force: Boolean = false): FrameOffer {
        if (!frame.isFresh(now)) return FrameOffer.STALE
        val sample = FrameSample(frame, FrameSignature.from(frame))
        recent.removeAll { it.expiresAt <= now }
        if (!force) {
            val cached = recent.lastOrNull { it.signature.similar(sample.signature) }
            if (cached != null) {
                skipped++
                return if (cached.hasHazard) FrameOffer.KNOWN_HAZARD else FrameOffer.KNOWN_FRAME
            }
            if (inFlight.any { it.signature.similar(sample.signature) }) {
                skipped++
                return FrameOffer.IN_PROGRESS
            }
        }
        // 等待队列里的相似画面用最新一帧替换：既避免重复，又刷新时间戳，减少上传过期画面。
        val duplicate = pending.indexOfFirst { it.signature.similar(sample.signature) }
        if (duplicate >= 0) {
            pending.removeAt(duplicate)
            skipped++
        }
        pending.add(sample)
        while (pending.size > 3) pending.removeAt(0)
        return FrameOffer.QUEUED
    }

    /** 用例：网络空闲时取批次；返回空列表表示已有请求或没有新鲜候选帧，无需发送请求。 */
    fun begin(now: Long): List<FrameSample> {
        if (inFlight.isNotEmpty()) return emptyList()
        pending.removeAll { !it.frame.isFresh(now) }
        inFlight = pending.toList()
        pending.clear()
        return inFlight
    }

    /** 用例：在请求 finally 中调用；hazardFrameIndices 是成功结果里有隐患的图片编号集合。 */
    fun complete(now: Long, success: Boolean, uncertain: Boolean = false, hazardFrameIndices: Set<Int> = emptySet()) {
        if (success) {
            // 无法判断的画面 3 秒后可重查，明确结果 30 秒后可重查；失败不写缓存。
            // 这里的缓存期限与界面结果显示 10 秒互相独立，清空表格不会立即触发重复上传。
            val lifetime = if (uncertain) 3000L else 30000L
            inFlight.forEachIndexed { index, sample ->
                recent.addLast(CachedFrame(sample.signature, now + lifetime, index in hazardFrameIndices))
            }
            while (recent.size > 12) recent.removeFirst()
        }
        inFlight = emptyList()
        // 请求等待期间积累的候选帧若与成功帧重复，也一并移除，避免下一批重复检查。
        pending.removeAll { sample -> recent.any { it.expiresAt > now && it.signature.similar(sample.signature) } }
    }

    fun clear() {
        pending.clear(); inFlight = emptyList(); recent.clear(); skipped = 0
    }
}
