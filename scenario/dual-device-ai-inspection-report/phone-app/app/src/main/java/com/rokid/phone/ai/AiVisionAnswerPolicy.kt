package com.rokid.phone.ai

/**
 * 识别结果归并规则：结构化巡检结果替代同批次的商品价格结果。
 * Recognition merge rule: structured inspection findings replace commercial results from the same capture.
 */
object AiVisionAnswerPolicy {
    private val inspectionHeadingGroups = listOf(
        listOf("隐患问题", "Hazard findings"),
        listOf("法律依据", "Legal basis"),
        listOf("整改建议", "Recommended actions"),
    )
    private val commercialMarkers = listOf("商品名称", "品牌", "单价", "价格来源", "淘宝")
    private val reportTitlePrefixes = listOf("报告标题", "Report title")
    private val reportSummaryPrefixes = listOf("巡检摘要", "Inspection summary")

    fun isStructuredInspection(record: AiVisionRecord): Boolean =
        hasAllInspectionHeadings(record.answer) ||
            hasAnyPrefix(record.answer, reportTitlePrefixes) &&
            hasAnyPrefix(record.answer, reportSummaryPrefixes) ||
            record.answer.trim() == "无法识别"

    fun isReportReady(record: AiVisionRecord): Boolean =
        hasAllInspectionHeadings(record.answer) ||
            hasAnyPrefix(record.answer, reportTitlePrefixes) &&
            hasAnyPrefix(record.answer, reportSummaryPrefixes)

    /**
     * 合并旧版本因双通道监听产生的同批次重复记录。
     */
    fun deduplicateStoredRecords(records: List<AiVisionRecord>): List<AiVisionRecord> {
        val kept = mutableListOf<AiVisionRecord>()
        records.sortedBy { it.timestamp }.forEach { record ->
            val duplicateIndex = kept.indexOfFirst { candidate ->
                val sameQuestion = candidate.question == record.question
                val sameBatch = kotlin.math.abs(candidate.timestamp - record.timestamp) <= 2_000L
                val oneLacksImage = candidate.imageUrl.isBlank() || record.imageUrl.isBlank()
                sameQuestion && sameBatch && oneLacksImage
            }
            if (duplicateIndex < 0) {
                kept.add(record)
            } else if (recordQuality(record) > recordQuality(kept[duplicateIndex])) {
                kept[duplicateIndex] = record
            }
        }
        return kept
    }

    private fun recordQuality(record: AiVisionRecord): Int {
        val headings = listOf("报告标题", "巡检摘要", "隐患问题", "法律依据", "整改建议")
        val positions = headings.map { record.answer.indexOf(it) }
        val ordered = positions.all { it >= 0 } && positions == positions.sorted()
        return (if (record.imageUrl.isNotBlank()) 100 else 0) +
            positions.count { it >= 0 } * 10 +
            (if (ordered) 5 else 0) +
            record.answer.length.coerceAtMost(99)
    }

    private fun isCommercialIdentification(record: AiVisionRecord): Boolean =
        !isStructuredInspection(record) && commercialMarkers.any { record.answer.contains(it) }

    fun extractReportTitle(answer: String): String =
        extractField(answer, reportTitlePrefixes, maxLength = 30)

    fun extractReportSummary(answer: String): String =
        extractField(answer, reportSummaryPrefixes, maxLength = 80)

    fun resolveReportTitle(record: AiVisionRecord): String {
        if (!record.reportTitle.isNullOrBlank()) return record.reportTitle.orEmpty()
        return extractReportTitle(record.answer)
            .ifBlank {
                extractField(
                    record.answer,
                    listOf("隐患问题", "Hazard findings"),
                    maxLength = 30,
                )
            }
            .ifBlank { extractReportSummary(record.answer).take(30) }
            .ifBlank {
                record.answer.lineSequence().map(String::trim).firstOrNull { it.isNotBlank() }.orEmpty()
                    .take(30)
            }
    }

    fun resolveReportSummary(record: AiVisionRecord): String {
        if (!record.reportSummary.isNullOrBlank()) return record.reportSummary.orEmpty()
        return extractReportSummary(record.answer)
            .ifBlank {
                extractField(
                    record.answer,
                    listOf("隐患问题", "Hazard findings"),
                    maxLength = 80,
                )
            }
    }

    /**
     * 手机端收到的 SDK 兜底原文统一转换为巡检格式，不保留商品价格或采购信息。
     * Normalize SDK fallback text on the phone into inspection format without product price or purchase data.
     */
    fun formatSdkFallback(rawAnswer: String): String {
        val nameLine = rawAnswer.lineSequence()
            .map { it.trim().trimStart('*', '-', ' ') }
            .firstOrNull {
                it.startsWith("名称:") || it.startsWith("名称：")
            }
        val objectName = nameLine
            ?.let { if (it.contains('：')) it.substringAfter('：') else it.substringAfter(':') }
            ?.trim()
            .orEmpty()
            .take(20)
        val title = if (objectName.isBlank()) {
            "SDK现场识别需复核"
        } else {
            "${objectName}巡检需复核"
        }
        return buildString {
            appendLine("报告标题：$title")
            appendLine("巡检摘要：SDK仅返回物品信息，未提供巡检隐患依据")
            appendLine("隐患问题：当前SDK结果无法判断现场隐患")
            appendLine("法律依据：需结合属地法规、行业标准核实")
            append("整改建议：对准设备或作业区域重新识别，并结合现场复核")
        }
    }

    /**
     * SDK 可能为同一次补充识别分配新的 questionId，因此同时使用图片地址或批次时间识别替代关系。
     * The SDK can assign a new questionId to a follow-up, so image identity or batch time identifies the replacement.
     */
    fun supersedes(commercial: AiVisionRecord, inspection: AiVisionRecord): Boolean {
        if (commercial.questionId == inspection.questionId) return false
        if (!isCommercialIdentification(commercial) || !isStructuredInspection(inspection)) return false
        val sameImage = commercial.imageUrl.isNotBlank() && commercial.imageUrl == inspection.imageUrl
        val sameBatch = commercial.timestamp > 0L && commercial.timestamp == inspection.timestamp
        return sameImage || sameBatch
    }

    /**
     * 提取模型生成的单行报告字段，兼容 Markdown 标记和模型偶发添加的项目符号。
     * Extract a single-line model-generated report field, tolerating Markdown markers and occasional bullets.
     */
    private fun extractField(answer: String, prefixes: List<String>, maxLength: Int): String {
        val line = answer.lineSequence()
            .map { line ->
                line.trim()
                    .trimStart('#', '*', '-', ' ', '•')
                    .trim()
            }
            .firstOrNull { line -> prefixes.any { prefix -> line.startsWith(prefix, ignoreCase = true) } }
            ?: return ""
        val value = if (line.contains('：')) {
            line.substringAfter('：')
        } else {
            line.substringAfter(':', missingDelimiterValue = "")
        }
        return value
            .trimStart('#', '*', '-', ' ', '•')
            .trim()
            .take(maxLength)
    }

    private fun hasAllInspectionHeadings(answer: String): Boolean =
        inspectionHeadingGroups.all { aliases ->
            aliases.any { alias -> answer.contains(alias, ignoreCase = true) }
        }

    private fun hasAnyPrefix(answer: String, prefixes: List<String>): Boolean =
        prefixes.any { prefix -> answer.contains(prefix, ignoreCase = true) }
}
