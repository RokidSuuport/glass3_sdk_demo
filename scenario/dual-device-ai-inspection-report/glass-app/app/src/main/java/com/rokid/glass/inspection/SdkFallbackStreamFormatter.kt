package com.rokid.glass.inspection

/**
 * 净化 AI 问答 SDK 的流式巡检文本，并过滤价格、采购链接等非巡检内容。
 * Sanitize streamed inspection text from the AI chat SDK and remove prices or purchase links.
 */
internal class SdkFallbackStreamFormatter(private val placeholder: String) {
    private val merged = StringBuilder()

    fun append(delta: String): String {
        mergeChunk(delta)
        return sanitize(merged.toString()).ifBlank { placeholder }
    }

    fun finish(): String = sanitize(merged.toString())

    fun isBlank(): Boolean = merged.isBlank()

    /**
     * SDK 有些版本返回累计文本，有些版本返回增量片段，这里统一成一份完整回答。
     */
    private fun mergeChunk(chunk: String) {
        if (chunk.isBlank()) return
        val current = merged.toString()
        when {
            current.isEmpty() -> merged.append(chunk)
            chunk.startsWith(current) -> {
                merged.clear()
                merged.append(chunk)
            }
            current.endsWith(chunk) -> Unit
            else -> {
                val overlap = (1..minOf(current.length, chunk.length))
                    .reversed()
                    .firstOrNull { size ->
                        current.regionMatches(current.length - size, chunk, 0, size, ignoreCase = false)
                    }
                    ?: 0
                merged.append(chunk.substring(overlap))
            }
        }
    }

    private fun sanitize(text: String): String =
        text.replace("\r\n", "\n")
            .lineSequence()
            .map { line ->
                line.replace("**", "")
                    .trim()
            }
            .filterNot { line -> line.startsWith("```") }
            .filterNot(::isCommercialLine)
            .joinToString("\n")
            .trim()

    private fun isCommercialLine(line: String): Boolean {
        val normalized = line.lowercase()
        return commercialMarkers.any(normalized::contains)
    }

    private companion object {
        val commercialMarkers = listOf(
            "单价",
            "价格",
            "价格来源",
            "商品名称",
            "购买链接",
            "采购链接",
            "淘宝",
            "京东",
            "天猫",
            "price",
            "purchase link",
            "buy now",
            "product recommendation",
        )
    }
}
