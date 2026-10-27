package com.rokid.industry.hazardrecognition

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** 接口协议用例：本地构造 Chat Completions JSON 响应，不调用模型、不使用真实 Key。 */
class InspectionProtocolTest {
    private fun response(content: Any, finish: String = "stop") = JSONObject().put("choices", JSONArray()
        .put(JSONObject().put("finish_reason", finish)
            .put("message", JSONObject().put("content", content)))).toString()
    private fun hazard(index: Int = 0) = JSONObject().put("category", "electrical").put("target", "左侧插线板")
        .put("content", "插座明显烧蚀").put("advice", "断电更换").put("frame_index", index).put("existing_id", JSONObject.NULL)
    private fun result(status: String) = JSONObject().put("status", status).put("summary", "现场消防检查")
        .put("hazards", if (status == "hazard") JSONArray().put(hazard()) else JSONArray()).toString()

    // 用例：分别解析疑似隐患、未见隐患和无法判断三种状态。
    @Test fun parsesAllRiskStates() {
        listOf("hazard" to Risk.HAZARD, "clear" to Risk.CLEAR, "unknown" to Risk.UNKNOWN).forEach { (status, risk) ->
            assertEquals(risk, InspectionProtocol.parse(response(result(status))).risk)
        }
    }
    // 用例：兼容模型在 JSON 外添加 Markdown 代码块。
    @Test fun acceptsFencedJson() {
        assertEquals(Risk.HAZARD, InspectionProtocol.parse(response("```json\n${result("hazard")}\n```")).risk)
    }
    // 用例：截断、拒绝、空值或错误字段必须失败，不能当作安全结果。
    @Test fun rejectsTruncationRefusalAndMalformedResults() {
        val invalid = listOf(response(result("clear"), "length"), response(result("clear"), "content_filter"),
            response("没有隐患"), response(""), response(JSONObject.NULL), response(result("safe")),
            response("""{"status":"clear","summary":true,"hazards":[]}"""), "{}", "not-json",
            JSONObject(response(result("clear"))).apply {
                getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("refusal", "refused")
            }.toString())
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { InspectionProtocol.parse(it) } }
    }
    // 用例：状态与隐患列表必须一致，图片编号必须落在实际批次内。
    @Test fun rejectsContradictoryResultsAndWrongFrameIndices() {
        val contradictory = JSONObject(result("clear")).put("hazards", JSONArray().put(hazard())).toString()
        val noHazards = JSONObject(result("hazard")).put("hazards", JSONArray()).toString()
        val wrongIndex = JSONObject(result("hazard")).put("hazards", JSONArray().put(hazard(3))).toString()
        listOf(contradictory, noHazards, wrongIndex).forEach {
            assertThrows(IllegalArgumentException::class.java) { InspectionProtocol.parse(response(it), 3) }
        }
        val valid = JSONObject(result("hazard")).put("hazards", JSONArray().put(hazard(2))).toString()
        assertEquals(2, InspectionProtocol.parse(response(valid), 3).hazards.single().frameIndex)
    }
    // 用例：拼装 1～3 图请求及已有记录摘要，验证图片编号顺序。
    @Test fun encodesUpToThreeImagesAndKnownRecords() {
        val request = JSONObject(InspectionProtocol.request("vision-\"demo", listOf("a", "b", "c"),
            known = listOf(KnownHazard(2, "electrical", "左侧插座", "烧蚀"))))
        assertEquals("vision-\"demo", request.getString("model"))
        assertFalse(request.getBoolean("stream"))
        val content = request.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertEquals(7, content.length())
        assertTrue(content.getJSONObject(0).getString("text").contains("左侧插座"))
        assertEquals("data:image/jpeg;base64,c", content.getJSONObject(6).getJSONObject("image_url").getString("url"))
        assertThrows(IllegalArgumentException::class.java) { InspectionProtocol.request("v", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { InspectionProtocol.request("v", List(4) { "a" }) }
    }
    // 用例：演示填写正确的 HTTPS 配置可以通过检查；空配置、HTTP 地址和格式错误的密钥会被拒绝。
    @Test fun validatesConfigurationBeforeSendingFrames() {
        assertNull(ModelConfig("https://example.com/v1/chat/completions", "vision", "key").error())
        assertNotNull(ModelConfig("", "", "").error())
        assertNotNull(ModelConfig("http://example.com", "vision", "key").error())
        assertNotNull(ModelConfig("https://user:password@example.com", "vision", "key").error())
        assertNotNull(ModelConfig("https://example.com", "vision", "key\nInjected: header").error())
    }
    // 用例：演示 NV21 数据长度、偶数尺寸及 收到帧后 2 秒的有效期。
    @Test fun validatesNv21DimensionsAndRejectsOldFrames() {
        assertTrue(Nv21Frame.validSize(12, 4, 2))
        assertFalse(Nv21Frame.validSize(11, 4, 2))
        assertFalse(Nv21Frame.validSize(12, 3, 2))
        assertFalse(Nv21Frame.validSize(0, Int.MAX_VALUE, Int.MAX_VALUE))
        val frame = Nv21Frame(ByteArray(12), 4, 2, 1000L, 0L)
        assertTrue(frame.isFresh(3000)); assertFalse(frame.isFresh(3001)); assertFalse(frame.isFresh(999))
    }
    // 用例：仅 DeepSeek 官方域名启用专用参数；其他兼容接口保留通用结构。
    @Test fun deepSeekUsesVisionWithThinkingDisabledAndJsonOutput() {
        val config = ModelConfig("https://api.deepseek.com/chat/completions", "deepseek-v4-flash-vision-exp", "test-key")
        assertTrue(config.isDeepSeek); assertNull(config.error())
        val request = JSONObject(InspectionProtocol.request(config.model, listOf("image"), config.isDeepSeek))
        assertEquals("disabled", request.getJSONObject("thinking").getString("type"))
        assertEquals("json_object", request.getJSONObject("response_format").getString("type"))
        assertNotNull(config.copy(model = "deepseek-v4-flash").error())
        val generic = JSONObject(InspectionProtocol.request("vision", listOf("image")))
        assertFalse(generic.has("thinking")); assertFalse(generic.has("response_format"))
        assertFalse(config.copy(endpoint = "https://api.deepseek.com.example.org/chat/completions").isDeepSeek)
    }
    // 用例：模型额外返回的法条不采纳，展示依据始终来自本地映射。
    @Test fun modelCannotInventLegalReferences() {
        val item = hazard().put("regulation", "某虚构法律第999条")
        val data = JSONObject(result("hazard")).put("hazards", JSONArray().put(item)).toString()
        val parsed = InspectionProtocol.parse(response(data)).hazards.single()
        assertTrue(RegulationCatalog.reference(parsed.category).contains("第27条第2款"))
        assertFalse(RegulationCatalog.reference(parsed.category).contains("999"))
        assertTrue(RegulationCatalog.reference("evacuation_blockage").contains("第28条"))
        assertTrue(RegulationCatalog.reference("other").startsWith("待核实"))
    }
}
