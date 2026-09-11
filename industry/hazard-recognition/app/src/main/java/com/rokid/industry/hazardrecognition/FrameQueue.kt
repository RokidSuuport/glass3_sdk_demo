package com.rokid.industry.hazardrecognition

import kotlin.math.abs

/** 用例：把 NV21 画面分成 32×18 个小区域，比较亮度和颜色来筛掉相似帧，无需先转成图片。 */
class FrameSignature private constructor(private val values: IntArray) {
    // 上传前使用较严格的相似度判断；loose=true 放宽要求，供隐患合并时结合位置和类别判断。
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
 * 用例顺序：offer() 放入新帧 → begin() 取出一批 → 请求结束后 complete() 更新缓存。
 * now 均传 SystemClock.elapsedRealtime()，保持与帧的 receivedAt 使用同一种计时方式。
 * 在主线程使用；最多等待三帧，同一时间只处理一批。停止相机时调用 clear()。
 */
class FrameQueue {
    private data class CachedFrame(val signature: FrameSignature, val expiresAt: Long, val hasHazard: Boolean)
    private val pending = mutableListOf<FrameSample>()
    private var inFlight = emptyList<FrameSample>()
    private val recent = ArrayDeque<CachedFrame>()
    var skipped = 0
        private set

    /** 自动识别用 force=false；手动复检用 force=true 允许相似帧入队，仍需等待上一批结束。 */
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

    /** 用例：准备上传时取出一批；返回空列表表示还在处理上一批，或没有两秒内的新帧。 */
    fun begin(now: Long): List<FrameSample> {
        if (inFlight.isNotEmpty()) return emptyList()
        pending.removeAll { !it.frame.isFresh(now) }
        inFlight = pending.toList()
        pending.clear()
        return inFlight
    }

    /** 用例：请求结束时在 finally 中调用；hazardFrameIndices 填本批中发现隐患的图片编号，从 0 开始。 */
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
