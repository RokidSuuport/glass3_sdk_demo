package com.rokid.industry.hazardrecognition

data class KnownHazard(val id: Int, val category: String, val target: String, val content: String)
data class HazardRecord(val id: Int, var hazard: Hazard, var lastSeen: Long, var signature: FrameSignature)

/**
 * 用例：请求前用 known() 提供已发现的隐患摘要；收到结果后用 merge() 合并重复项，并返回新增条数。
 * 最多在内存中保留 30 条，重启进程后清空；它不是数据库，也不用于展示历史列表。
 */
class HazardLedger {
    private val records = mutableListOf<HazardRecord>()
    private var nextId = 1
    fun all(): List<HazardRecord> = records.sortedByDescending { it.lastSeen }
    // 只向模型提供最近 12 条摘要，控制请求长度；不重复携带历史照片。
    fun known(): List<KnownHazard> = all().take(12).map { KnownHazard(it.id, it.hazard.category, it.hazard.target, it.hazard.content) }

    fun merge(hazards: List<Hazard>, samples: List<FrameSample>): Int {
        var added = 0
        hazards.forEach { hazard ->
            val sample = samples[hazard.frameIndex]
            // 即使模型返回 existingId，仍需类别、场景和对象相似；不能仅凭 ID 合并不同地点。
            val existing = records.firstOrNull { record ->
                record.hazard.category == hazard.category && record.signature.similar(sample.signature, loose = true) &&
                    similarTarget(record.hazard.target, hazard.target) &&
                    (hazard.existingId == record.id || similarTarget(record.hazard.content, hazard.content))
            }
            if (existing != null) {
                existing.hazard = hazard
                existing.lastSeen = sample.frame.wallTime
                existing.signature = sample.signature
            } else {
                records.add(HazardRecord(nextId++, hazard, sample.frame.wallTime, sample.signature))
                added++
            }
        }
        while (records.size > 30) records.removeAt(records.indices.minBy { records[it].lastSeen })
        return added
    }

    private fun similarTarget(a: String, b: String): Boolean {
        fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
            .replace("画面", "").replace("位置", "")
        val x = normalize(a); val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        // 用例：“左侧插座”和“右侧插座”不能因为名称相似就合并为同一处隐患。
        for ((left, right) in listOf("左" to "右", "前" to "后")) {
            if ((left in x && right in y) || (right in x && left in y)) return false
        }
        // 比较文字中连续两个字的重复比例，容忍轻微措辞差异；这只是简单规则，不理解文字含义。
        val xs = x.windowed(2).toSet(); val ys = y.windowed(2).toSet()
        return xs.isNotEmpty() && ys.isNotEmpty() && 2.0 * xs.intersect(ys).size / (xs.size + ys.size) >= 0.65
    }
}
