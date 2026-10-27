package com.rokid.glass.inspection

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class VisualInspectionStreamTest {
    @Test
    fun extractsIncrementalTextFromSseChoice() {
        val first = extractVisionStreamDelta(
            JSONObject(
                """{"choices":[{"delta":{"content":"报告标题："}}]}""",
            ),
        )
        val second = extractVisionStreamDelta(
            JSONObject(
                """{"choices":[{"delta":{"content":"现场巡检"}}]}""",
            ),
        )

        assertEquals("报告标题：", first)
        assertEquals("现场巡检", second)
    }
}
