package com.rokid.phone.ai

/**
 * 眼镜端一次视觉巡检结果及其图片流标签。
 * One vision-inspection result and its image-stream tag from the glasses.
 */
data class AiVisionRelayPayload(
    val questionId: String = "",
    val question: String = "",
    val answer: String = "",
    val reportTitle: String = "",
    val reportSummary: String = "",
    val imageTag: String = "",
    val timestamp: Long = System.currentTimeMillis(),
) {
    companion object {
        const val MESSAGE_TYPE = "AI_VISION_RESULT"
        const val IMAGE_TAG_PREFIX = "AI_VISION_IMAGE:"
    }
}
