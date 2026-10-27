package com.rokid.phone.ai

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiVisionAnswerPolicyTest {
    @Test
    fun structuredInspectionSupersedesCommercialResultInSameBatch() {
        val commercial = AiVisionRecord(
            questionId = "commercial",
            answer = "商品名称：金属窗\n品牌：示例\n单价：¥380",
            timestamp = 100L,
            completed = true,
        )
        val inspection = AiVisionRecord(
            questionId = "inspection",
            answer = "隐患问题：未见明显隐患\n法律依据：需核实\n整改建议：定期复核",
            timestamp = 100L,
            completed = true,
        )

        assertTrue(AiVisionAnswerPolicy.supersedes(commercial, inspection))
    }

    @Test
    fun differentBatchIsNotRemoved() {
        val commercial = AiVisionRecord(
            questionId = "commercial",
            answer = "商品名称：金属窗\n单价：¥380",
            imageUrl = "content://image/one",
            timestamp = 100L,
            completed = true,
        )
        val inspection = AiVisionRecord(
            questionId = "inspection",
            answer = "隐患问题：未见明显隐患\n法律依据：需核实\n整改建议：定期复核",
            imageUrl = "content://image/two",
            timestamp = 200L,
            completed = true,
        )

        assertFalse(AiVisionAnswerPolicy.supersedes(commercial, inspection))
    }

    @Test
    fun extractsAiGeneratedReportHeader() {
        val answer = """
            报告标题：高处设备巡检发现松脱线缆
            **巡检摘要：** 现场存在线缆松脱风险，建议停机后复核固定状态。
            隐患问题：设备顶部线缆松脱
            法律依据：需结合属地法规、行业标准核实
            整改建议：停机断电后重新固定并复查
        """.trimIndent()

        assertEquals(
            "高处设备巡检发现松脱线缆",
            AiVisionAnswerPolicy.extractReportTitle(answer),
        )
        assertEquals(
            "现场存在线缆松脱风险，建议停机后复核固定状态。",
            AiVisionAnswerPolicy.extractReportSummary(answer),
        )
    }

    @Test
    fun derivesReportHeaderFromAiFindingsWhenTitleIsMissing() {
        val record = AiVisionRecord(
            questionId = "inspection",
            answer = """
                隐患问题：配电柜门未关闭，内部线缆裸露
                法律依据：需结合属地法规、行业标准核实
                整改建议：断电后关闭柜门并检查线缆绝缘
            """.trimIndent(),
            completed = true,
        )

        assertEquals(
            "配电柜门未关闭，内部线缆裸露",
            AiVisionAnswerPolicy.resolveReportTitle(record),
        )
        assertEquals(
            "配电柜门未关闭，内部线缆裸露",
            AiVisionAnswerPolicy.resolveReportSummary(record),
        )
        assertTrue(AiVisionAnswerPolicy.isReportReady(record))
    }

    @Test
    fun unavailableResultIsNotIncludedInReport() {
        val record = AiVisionRecord(
            questionId = "unavailable",
            answer = "无法识别",
            completed = true,
        )

        assertFalse(AiVisionAnswerPolicy.isReportReady(record))
    }

    @Test
    fun englishInspectionAnswerProducesReportFields() {
        val answer = """
            Report title: Open electrical cabinet
            Inspection summary: Cabinet door is open with exposed wiring.
            Hazard findings: Exposed wiring may be touched accidentally.
            Legal basis: Verify local regulations and industry standards.
            Recommended actions: 1. Close the door. 2. Lock it out for review.
        """.trimIndent()
        val record = AiVisionRecord(questionId = "english", answer = answer, completed = true)

        assertTrue(AiVisionAnswerPolicy.isReportReady(record))
        assertEquals("Open electrical cabinet", AiVisionAnswerPolicy.resolveReportTitle(record))
        assertEquals(
            "Cabinet door is open with exposed wiring.",
            AiVisionAnswerPolicy.resolveReportSummary(record),
        )
    }

    @Test
    fun legacyRecordWithoutReportFieldsRemainsReadable() {
        val record = Gson().fromJson(
            """
                {
                  "questionId":"legacy",
                  "answer":"隐患问题：未见明显隐患\n法律依据：需核实\n整改建议：定期复核",
                  "completed":true
                }
            """.trimIndent(),
            AiVisionRecord::class.java,
        )

        assertEquals("未见明显隐患", AiVisionAnswerPolicy.resolveReportTitle(record))
        assertTrue(AiVisionAnswerPolicy.isReportReady(record))
    }

    @Test
    fun aiVisionRelayPayloadKeepsImageStreamTag() {
        val payload = Gson().fromJson(
            """
                {
                  "questionId":"relay-1",
                  "question":"工业现场巡检",
                  "answer":"隐患问题：未见明显隐患\n法律依据：—\n整改建议：定期复核",
                  "reportTitle":"现场巡检未见明显隐患",
                  "reportSummary":"现场状态正常",
                  "imageTag":"AI_VISION_IMAGE:relay-1",
                  "timestamp":100
                }
            """.trimIndent(),
            AiVisionRelayPayload::class.java,
        )

        assertEquals("relay-1", payload.questionId)
        assertEquals("AI_VISION_IMAGE:relay-1", payload.imageTag)
    }

    @Test
    fun sdkFallbackOnPhoneRemovesCommercialPrice() {
        val answer = AiVisionAnswerPolicy.formatSdkFallback(
            "其他物品\n---\n* 名称: 苹果 MacBook Pro\n- 单价: ¥12999\n- 价格来源: 京东",
        )

        assertTrue(answer.startsWith("报告标题：苹果 MacBook Pro巡检需复核"))
        assertTrue(answer.contains("隐患问题：当前SDK结果无法判断现场隐患"))
        assertFalse(answer.contains("12999"))
        assertFalse(answer.contains("京东"))
    }

    @Test
    fun duplicateRecordsFromTwoOldSourcesCollapseToTheBetterOne() {
        val orderedAnswer = """
            报告标题：门窗顶部线路检查
            巡检摘要：线路未穿管
            隐患问题：线路绝缘层裸露
            法律依据：需结合属地法规、行业标准核实
            整改建议：穿管固定并复核
        """.trimIndent()
        val first = AiVisionRecord(
            questionId = "runtime",
            question = "工业现场巡检",
            answer = "隐患问题：线路裸露\n整改建议：穿管固定\n法律依据：需核实",
            timestamp = 1_000L,
            completed = true,
        )
        val second = AiVisionRecord(
            questionId = "relay",
            question = "工业现场巡检",
            answer = orderedAnswer,
            imageUrl = "content://inspection/1",
            timestamp = 1_002L,
            completed = true,
        )

        val records = AiVisionAnswerPolicy.deduplicateStoredRecords(listOf(first, second))

        assertEquals(1, records.size)
        assertEquals("relay", records.single().questionId)
        assertEquals("content://inspection/1", records.single().imageUrl)
    }
}
