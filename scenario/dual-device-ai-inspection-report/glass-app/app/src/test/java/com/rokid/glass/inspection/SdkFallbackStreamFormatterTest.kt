package com.rokid.glass.inspection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdkFallbackStreamFormatterTest {
    @Test
    fun showsPlaceholderBeforeFirstChunk() {
        val formatter = SdkFallbackStreamFormatter("正在等待巡检结果")

        assertTrue(formatter.append("").contains("正在等待巡检结果"))
    }

    @Test
    fun streamsStructuredInspectionTextAndRemovesCommercialLines() {
        val formatter = SdkFallbackStreamFormatter("正在等待巡检结果")
        val first = formatter.append(
            """
            报告标题：配电柜门未关闭
            巡检摘要：配电柜门开启，内部线缆可见
            """.trimIndent(),
        )
        val second = formatter.append(
            """
            隐患问题：柜门未关闭，存在误触风险
            单价：¥100
            法律依据：需结合属地法规、行业标准核实
            整改建议：关闭柜门并挂牌复核
            """.trimIndent(),
        )
        val final = formatter.finish()

        assertTrue(first.contains("报告标题：配电柜门未关闭"))
        assertTrue(second.contains("隐患问题：柜门未关闭"))
        assertTrue(final.contains("整改建议：关闭柜门并挂牌复核"))
        assertFalse(second.contains("单价"))
        assertFalse(final.contains("单价"))
        assertFalse(final.contains("¥100"))
    }

    @Test
    fun handlesCumulativeAndOverlappingChunksWithoutDuplicatingText() {
        val formatter = SdkFallbackStreamFormatter("等待结果")

        formatter.append("报告标题：")
        formatter.append("报告标题：门窗线路")
        val final = formatter.append("门窗线路\n\n隐患问题：线路未穿管")

        assertTrue(final.startsWith("报告标题：门窗线路"))
        assertTrue(final.contains("隐患问题：线路未穿管"))
        assertEquals(1, Regex("门窗线路").findAll(final).count())
    }
}
