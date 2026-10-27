package com.rokid.glass.inspection

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndustrialInspectionProtocolTest {
    @Test
    fun requestCarriesImageAndInspectionPromptTogether() {
        val request = JSONObject(
            IndustrialInspectionProtocol.request("vision-test", "image-base64"),
        )
        val system = request.getJSONArray("messages")
            .getJSONObject(0)
            .getString("content")
        val user = request.getJSONArray("messages")
            .getJSONObject(1)
            .getJSONArray("content")

        assertTrue(request.getBoolean("stream"))
        assertTrue(system.contains("工业现场巡检"))
        assertTrue(system.contains("报告标题："))
        assertTrue(system.contains("巡检摘要："))
        assertTrue(system.contains("隐患问题："))
        assertTrue(system.contains("法律依据："))
        assertTrue(system.contains("整改建议："))
        assertEquals(
            "data:image/jpeg;base64,image-base64",
            user.getJSONObject(1)
                .getJSONObject("image_url")
                .getString("url"),
        )
    }

    @Test
    fun extractsReportFieldsFromStreamedPlainText() {
        val answer = """
            报告标题：配电柜线缆裸露巡检
            巡检摘要：发现一处用电隐患
            隐患问题：配电柜右侧：线缆裸露
            法律依据：需结合属地法规核实
            整改建议：断电后封闭处理
        """.trimIndent()

        assertEquals(
            "配电柜线缆裸露巡检",
            IndustrialInspectionProtocol.extractReportField(answer, "报告标题"),
        )
        assertEquals(
            "发现一处用电隐患",
            IndustrialInspectionProtocol.extractReportField(answer, "巡检摘要"),
        )
    }
}
