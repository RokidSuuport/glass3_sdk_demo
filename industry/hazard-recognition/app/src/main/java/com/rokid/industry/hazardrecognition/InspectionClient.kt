package com.rokid.industry.hazardrecognition

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 用例：在协程中调用 client.inspect(本批帧, ledger.known())，得到包含隐患内容和整改建议的结果。
 * 调用方负责相机生命周期和采样；本类只负责 NV21 编码、HTTPS 请求及响应解析。
 * 页面销毁时调用 close()。这里使用模型 API Key，不调用 Rokid ASR/TTS 接口。
 */
class InspectionClient(private val config: ModelConfig) {
    // 禁止自动重定向，避免携带鉴权头跳转到其他地址；失败后由 Activity 等待一段时间再重试。
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    /** 用例：传入 1～3 张已复制的 NV21 帧。图片转换和网络请求在后台执行，不阻塞眼镜界面。 */
    suspend fun inspect(frames: List<Nv21Frame>, known: List<KnownHazard>): InspectionResult = withContext(Dispatchers.IO) {
        require(frames.size in 1..3)
        require(config.error() == null) { config.error().orEmpty() }
        // 通用图文接口接收 JPEG 图片，并非原始 NV21；此处用质量参数 80 压缩为 JPEG，再转成接口所需的 Base64 字符串。
        val images = frames.map { frame ->
            val jpeg = ByteArrayOutputStream().use { out ->
                check(YuvImage(frame.bytes, ImageFormat.NV21, frame.width, frame.height, null)
                    .compressToJpeg(Rect(0, 0, frame.width, frame.height), 80, out)) { "画面编码失败" }
                out.toByteArray()
            }
            Base64.encodeToString(jpeg, Base64.NO_WRAP)
        }
        // 用例：切换供应商时修改 ModelConfig 的完整接口地址、视觉模型名和 API Key。
        // 不打印请求正文或 Authorization，避免把现场图片和密钥写入日志。
        val request = Request.Builder().url(config.endpoint)
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(InspectionProtocol.request(config.model, images, config.isDeepSeek, known)
                .toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val body = execute(http.newCall(request))
        InspectionProtocol.parse(body, frames.size)
    }

    private suspend fun execute(call: Call): String = suspendCancellableCoroutine { continuation ->
        // 用例：页面退出导致协程取消时，同步取消 OkHttp 请求，避免后台继续占用连接。
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("网络请求失败或超时，请检查 Wi-Fi", e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.use {
                        if (!it.isSuccessful) throw IOException(when (it.code) {
                            401, 403 -> "模型鉴权失败，请检查 API Key 和权限"
                            402 -> "模型账户余额不足，请充值后重试"
                            400 -> "模型请求参数不支持，请确认使用视觉模型"
                            404 -> "模型或接口不存在，请检查配置"
                            429 -> "模型限流或额度不足，请稍后重试"
                            else -> "模型请求失败（HTTP ${it.code}）"
                        })
                        val source = it.body?.source() ?: throw IOException("模型响应为空")
                        // Demo 只接收小型 JSON；超过 256 KiB 就拒绝，避免异常响应占满眼镜内存。
                        if (source.request(256 * 1024L + 1)) throw IOException("模型响应过大")
                        source.readUtf8()
                    }
                    if (continuation.isActive) continuation.resume(body)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    fun close() {
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }
}
