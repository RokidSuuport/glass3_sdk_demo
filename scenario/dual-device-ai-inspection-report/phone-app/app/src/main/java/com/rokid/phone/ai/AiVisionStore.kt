package com.rokid.phone.ai

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 一条 AI 查验记录。
 * One AI image-recognition conversation record.
 */
data class AiVisionRecord(
    val questionId: String,
    var question: String = "",
    var answer: String = "",
    var reportTitle: String? = "",
    var reportSummary: String? = "",
    var imageUrl: String = "",
    var timestamp: Long = System.currentTimeMillis(),
    var completed: Boolean = false,
)

/**
 * 将完成的查验记录持久化到应用私有目录。
 * Persist completed recognition records in the app's private directory.
 */
object AiVisionStore {
    const val ACTION_RECORDS_UPDATED = "com.rokid.phone.action.AI_VISION_RECORDS_UPDATED"
    private const val FILE_NAME = "ai_vision_records.json"
    private val gson = Gson()

    @Synchronized
    fun load(context: Context): MutableList<AiVisionRecord> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return mutableListOf()
        return runCatching {
            val type = object : TypeToken<MutableList<AiVisionRecord>>() {}.type
            gson.fromJson<MutableList<AiVisionRecord>>(file.readText(), type) ?: mutableListOf()
        }.getOrDefault(mutableListOf())
    }

    @Synchronized
    fun save(context: Context, records: Collection<AiVisionRecord>) {
        val target = File(context.filesDir, FILE_NAME)
        val temp = File(context.filesDir, "$FILE_NAME.tmp")
        val completed = records.filter { it.completed }.sortedBy { it.timestamp }
        runCatching {
            temp.writeText(gson.toJson(completed))
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }.onFailure {
            temp.delete()
        }
    }
}

/**
 * 将 SDK 为 WebView 兼容处理的图片地址还原为 Android 可读取的 URI。
 * Restore the image address rewritten for WebView compatibility to an Android-readable URI.
 */
fun resolveAiVisionImageUri(imageUrl: String?): Uri? {
    val value = imageUrl?.trim().orEmpty()
    if (value.isEmpty()) return null
    val normalized = if (
        (value.startsWith("https:") && !value.startsWith("https://")) ||
        (value.startsWith("https://") && value.contains(".fileprovider/"))
    ) {
        value.replaceFirst("https:", "content:")
    } else {
        value
    }
    return runCatching { Uri.parse(normalized) }.getOrNull()
}
