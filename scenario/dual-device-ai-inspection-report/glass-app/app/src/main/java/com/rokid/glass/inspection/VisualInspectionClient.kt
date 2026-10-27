package com.rokid.glass.inspection

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 使用图片和一次视觉 Prompt 请求兼容 Chat Completions 的模型。
 * Request a Chat Completions-compatible vision model with one image and one inspection prompt.
 */
class VisualInspectionClient(private val config: VisualModelConfig) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun inspectStream(jpeg: ByteArray, onDelta: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
        config.error()?.let { throw IllegalStateException(it) }
        val safeUrl = config.endpoint.toHttpUrlOrNull()
        Log.i(
            TAG,
            "vision request host=${safeUrl?.host} path=${safeUrl?.encodedPath} " +
                "model=${config.model} imageBytes=${jpeg.size}",
        )
        val imageBase64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
        val request = Request.Builder()
            .url(config.endpoint)
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(
                IndustrialInspectionProtocol.request(config.model, imageBase64, config.isDeepSeek)
                    .toRequestBody("application/json; charset=utf-8".toMediaType()),
            )
            .build()

        val call = http.newCall(request)
        val cancellation = currentCoroutineContext().job.invokeOnCompletion {
            if (it != null) call.cancel()
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException(
                        when (response.code) {
                            401, 403 -> "视觉模型鉴权失败"
                            402 -> "视觉模型账户余额不足"
                            404 -> "视觉模型或接口不存在"
                            429 -> "视觉模型限流或额度不足"
                            else -> "视觉模型请求失败（HTTP ${response.code}）"
                        },
                    )
                }
                val source = response.body?.source()
                    ?: throw IOException("视觉模型响应为空")
                val answer = StringBuilder()
                var totalBytes = 0L
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    totalBytes += line.length
                    if (totalBytes > MAX_RESPONSE_CHARS) {
                        throw IOException("视觉模型响应过大")
                    }
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val root = JSONObject(data)
                    root.optJSONObject("error")?.let {
                        throw IOException(it.optString("message", "视觉模型返回错误"))
                    }
                    root.optString("model").takeIf { it.isNotBlank() }?.let { model ->
                        Log.i(TAG, "vision response model=$model")
                    }
                    val delta = extractVisionStreamDelta(root)
                    if (delta.isNotEmpty()) {
                        answer.append(delta)
                        onDelta(delta)
                    }
                }
                if (answer.isBlank()) throw IOException("视觉模型返回内容为空")
                answer.toString()
            }
        } finally {
            cancellation.dispose()
        }
    }

    fun close() {
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }

    private companion object {
        const val TAG = "VisualInspectionClient"
        const val MAX_RESPONSE_CHARS = 256 * 1024
    }
}

/**
 * 从一个 SSE data JSON 中提取增量文本。
 * Extract incremental text from one SSE data JSON object.
 */
internal fun extractVisionStreamDelta(root: JSONObject): String =
    root.optJSONArray("choices")
        ?.optJSONObject(0)
        ?.optJSONObject("delta")
        ?.optString("content")
        .orEmpty()
