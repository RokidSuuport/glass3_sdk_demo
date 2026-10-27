package com.rokid.glass

import android.util.Log
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.rokid.glass.base.BaseGlassActivity
import com.rokid.glass.base.GlassKeyEvent
import com.rokid.glass.bean.CustomMessage
import com.rokid.glass.inspection.IndustrialInspectionProtocol
import com.rokid.glass.inspection.SdkFallbackStreamFormatter
import com.rokid.glass.inspection.VisualInspectionClient
import com.rokid.glass.inspection.VisualModelConfig
import com.rokid.glass.utils.demoString
import com.rokid.glesse.BuildConfig
import com.rokid.glesse.R
import com.rokid.glesse.databinding.ActivithyIdentBinding
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.sdk.base.data.aichat.bean.AiContentType
import com.rokid.security.glass3.sdk.base.data.offlineCmd.bean.VoiceAction
import com.rokid.security.glass3.sdk.base.data.offlineCmd.listener.IVoiceCallback
import com.rokid.security.system.server.aichat.listener.AiChatListener
import com.rokid.security.system.server.message.callback.IResultCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.UUID

/**
 * 工业巡检识图页：实时预览环境，通过“识别”指令或单击截取最新帧并一次提交视觉模型。
 * Industrial inspection page: preview the scene and submit the latest frame and inspection prompt once,
 * triggered by the “识别” offline command or a touchpad/ring click.
 */
class IdentificationActivity : BaseGlassActivity() {
    private lateinit var mBinding: ActivithyIdentBinding
    private lateinit var rotateAnimation: Animation
    private val gson = Gson()
    private val config = VisualModelConfig(
        BuildConfig.VISION_MODEL_ENDPOINT,
        BuildConfig.VISION_MODEL_NAME,
        BuildConfig.VISION_MODEL_API_KEY,
    )
    private val visionEngine = BuildConfig.VISION_ENGINE.trim().lowercase()
    private val client = VisualInspectionClient(config)
    private val registeredVoiceActions = mutableListOf<VoiceAction>()
    private var inspectionJob: Job? = null
    private var isTaskRunning = false
    private var sdkFallbackActive = false
    private var sdkChatActive = false
    private var foreground = false
    private var previewRetryCount = 0
    private lateinit var sdkStreamFormatter: SdkFallbackStreamFormatter
    private val sdkAiChatListener = object : AiChatListener.Stub() {
        override fun onAiChatAnswer(
            answer: String?,
            isFinish: Boolean,
            contentType: String?,
            sessionId: String?,
        ) {
            runOnUiThread {
                if (sdkFallbackActive) {
                    handleSdkAiChatAnswer(answer.orEmpty(), contentType.orEmpty(), isFinish)
                }
            }
        }

        override fun onError(code: Int, message: String) {
            runOnUiThread {
                if (sdkFallbackActive) {
                    failSdkAiChat(
                        message.takeIf { it.isNotBlank() }
                            ?: demoString(R.string.demo_sdk_fallback_failed, code),
                    )
                }
            }
        }

        override fun onAiTakePhoto(filePath: String) {
            Log.d(TAG, "AI chat inspection photo captured path=$filePath")
        }

        override fun onContinuousModeUpdate(
            continuousMode: Boolean,
            timeout: Long,
            keepSessionActive: Boolean,
        ) = Unit
    }

    private val registerVoiceCommandRunnable = object : Runnable {
        override fun run() {
            if (registerVoiceCommand()) return
            if (!isFinishing && !isDestroyed) mBinding.root.postDelayed(this, 500L)
        }
    }

    private val startPreviewRunnable = object : Runnable {
        override fun run() {
            if (foreground) mBinding.previewView.startPreview()
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        mBinding = ActivithyIdentBinding.inflate(layoutInflater)
        setContentView(mBinding.root)
        rotateAnimation = AnimationUtils.loadAnimation(this, R.anim.rotate_loading)
        reset()
        loadStatus(STATUS_IDLE)
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        mBinding.previewView.onPreviewOpened = {
            previewRetryCount = 0
            if (!isTaskRunning) loadStatus(STATUS_IDLE)
        }
        mBinding.previewView.onPreviewError = { code, message ->
            if (foreground && !isFinishing && !isDestroyed) {
                loadStatus(
                    STATUS_ERROR,
                    demoString(R.string.demo_camera_preview_failed, code, message),
                )
                if (previewRetryCount < 20) {
                    previewRetryCount++
                    mBinding.root.removeCallbacks(startPreviewRunnable)
                    mBinding.root.postDelayed(startPreviewRunnable, 500L)
                }
            }
        }
        mBinding.root.post(startPreviewRunnable)
        mBinding.root.removeCallbacks(registerVoiceCommandRunnable)
        mBinding.root.post(registerVoiceCommandRunnable)
    }

    override fun onPause() {
        foreground = false
        mBinding.root.removeCallbacks(startPreviewRunnable)
        unregisterVoiceCommands()
        inspectionJob?.cancel()
        inspectionJob = null
        isTaskRunning = false
        sdkFallbackActive = false
        mBinding.previewView.onPreviewError = null
        mBinding.previewView.onPreviewOpened = null
        mBinding.previewView.stopPreview()
        stopSdkAiChat()
        super.onPause()
    }

    override fun onDestroy() {
        unregisterVoiceCommands()
        inspectionJob?.cancel()
        sdkFallbackActive = false
        mBinding.previewView.stopPreview()
        stopSdkAiChat()
        client.close()
        super.onDestroy()
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        if (keyEvent == GlassKeyEvent.KEYCODE_CLICK) {
            startInspection()
            return true
        }
        return super.onGlassKeyEvent(keyEvent)
    }

    /**
     * 注册端侧离线“识别”指令，不依赖在线 ASR。
     * Register the on-device offline “识别” command without online ASR.
     */
    private fun registerVoiceCommand(): Boolean {
        if (registeredVoiceActions.isNotEmpty()) return true
        val service = GlassSdk.getGlassOfflineCmdService() ?: return false
        val action = VoiceAction(
            "识别",
            "shi bie",
            object : IVoiceCallback.Stub() {
                private val activityRef = WeakReference(this@IdentificationActivity)

                override fun onVoiceTriggered() {
                    Log.d(TAG, "offline voice command triggered: 识别")
                    activityRef.get()?.runOnUiThread {
                        if (!isFinishing && !isDestroyed) startInspection()
                    }
                }
            },
        )
        return runCatching {
            service.add(action)
            registeredVoiceActions.add(action)
        }.isSuccess
    }

    private fun unregisterVoiceCommands() {
        if (::mBinding.isInitialized) mBinding.root.removeCallbacks(registerVoiceCommandRunnable)
        val service = GlassSdk.getGlassOfflineCmdService()
        if (service != null) {
            registeredVoiceActions.forEach { runCatching { service.remove(it) } }
        }
        registeredVoiceActions.clear()
    }

    /**
     * 截取当前预览帧，一次请求视觉模型；运行期间忽略重复触发。
     * Capture the current preview frame and call the vision model once; ignore repeated triggers while running.
     */
    private fun startInspection() {
        if (isTaskRunning) return
        when (visionEngine) {
            "sdk" -> startSdkFallback()
            "api" -> startApiInspection(fallbackOnFailure = false)
            "auto" -> {
                if (config.endpoint.isBlank() && config.model.isBlank() && config.apiKey.isBlank()) {
                    startSdkFallback()
                } else {
                    startApiInspection(fallbackOnFailure = true)
                }
            }
            else -> loadStatus(
                STATUS_ERROR,
                demoString(R.string.demo_vision_engine_invalid, visionEngine),
            )
        }
    }

    private fun startApiInspection(fallbackOnFailure: Boolean) {
        config.error()?.let { error ->
            loadStatus(STATUS_ERROR, error)
            return
        }
        isTaskRunning = true
        inspectionJob?.cancel()
        resetResult()
        loadStatus(STATUS_ANALYZING)
        inspectionJob = lifecycleScope.launch {
            var fallbackStarted = false
            try {
                val frame = withContext(Dispatchers.Default) {
                    mBinding.previewView.captureJpeg()
                } ?: throw IllegalStateException("实时预览尚未返回画面，请稍后重试")
                val streamedAnswer = StringBuilder()
                val answer = client.inspectStream(frame.jpeg) { delta ->
                    runOnUiThread {
                        if (isTaskRunning) {
                            streamedAnswer.append(delta)
                            appendAnswerContent(streamedAnswer.toString())
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    appendAnswerContent(answer)
                    loadStatus(STATUS_DONE)
                    relayToPhone(
                        jpeg = frame.jpeg,
                        answer = answer,
                        reportTitle = IndustrialInspectionProtocol.extractReportField(
                            answer,
                            "报告标题",
                        ),
                        reportSummary = IndustrialInspectionProtocol.extractReportField(
                            answer,
                            "报告摘要",
                        ).ifBlank {
                            IndustrialInspectionProtocol.extractReportField(answer, "巡检摘要")
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "inspection failed", e)
                if (fallbackOnFailure) {
                    Log.i(TAG, "vision API failed; falling back to SDK")
                    fallbackStarted = true
                    startSdkFallback()
                } else {
                    loadStatus(STATUS_ERROR, e.message ?: "识别失败，请重试")
                }
            } finally {
                if (!fallbackStarted) isTaskRunning = false
            }
        }
    }

    /**
     * 未配置云端视觉模型时，使用 AI 问答 SDK 的视觉意图作为兜底。
     * Use the AI chat SDK's vision intent as the fallback when no cloud vision model is configured.
     */
    private fun startSdkFallback() {
        val service = GlassSdk.getGlassAiChatService()
        if (service == null) {
            loadStatus(STATUS_ERROR, demoString(R.string.demo_sdk_fallback_unavailable))
            return
        }
        isTaskRunning = true
        sdkFallbackActive = true
        sdkStreamFormatter = SdkFallbackStreamFormatter(
            demoString(R.string.demo_sdk_fallback_stream_placeholder),
        )
        resetResult()
        appendAnswerContent(sdkStreamFormatter.append(""))
        runCatching {
            // 预览由本页面持有，因此向 AI 问答 SDK 声明相机已经打开。
            // The page owns preview, so tell the AI chat SDK that the camera is already open.
            service.startAiChat(true)
            sdkChatActive = true
            service.toAiChatWithIntent(
                demoString(R.string.demo_sdk_fallback_inspection_prompt),
                SDK_VISION_INTENT,
                null,
                sdkAiChatListener,
            )
        }.onFailure {
            failSdkAiChat(it.message ?: demoString(R.string.demo_sdk_fallback_unavailable))
        }
    }

    private fun handleSdkAiChatAnswer(answer: String, contentType: String, isFinish: Boolean) {
        if (answer.isNotEmpty() && contentType != AiContentType.GUIDE) {
            appendAnswerContent(sdkStreamFormatter.append(answer))
        }
        if (isFinish) {
            finishSdkAiChat()
        }
    }

    private fun finishSdkAiChat() {
        if (!sdkFallbackActive) return
        val finalAnswer = sdkStreamFormatter.finish()
        sdkFallbackActive = false
        isTaskRunning = false
        if (sdkStreamFormatter.isBlank() || finalAnswer.isBlank()) {
            loadStatus(STATUS_ERROR, demoString(R.string.demo_sdk_fallback_empty))
            return
        }
        appendAnswerContent(finalAnswer)
        loadStatus(STATUS_DONE)
        lifecycleScope.launch {
            val frame = withContext(Dispatchers.Default) {
                mBinding.previewView.captureJpeg()
            }
            if (frame != null) {
                relayToPhone(
                    jpeg = frame.jpeg,
                    answer = finalAnswer,
                    reportTitle = IndustrialInspectionProtocol.extractReportField(
                        finalAnswer,
                        "报告标题",
                    ),
                    reportSummary = IndustrialInspectionProtocol.extractReportField(
                        finalAnswer,
                        "巡检摘要",
                    ).ifBlank {
                        IndustrialInspectionProtocol.extractReportField(finalAnswer, "报告摘要")
                    },
                )
            } else {
                Log.w(TAG, "SDK fallback finished without a preview frame to relay")
            }
        }
    }

    private fun failSdkAiChat(message: String) {
        sdkFallbackActive = false
        isTaskRunning = false
        loadStatus(STATUS_ERROR, message)
    }

    private fun stopSdkAiChat() {
        if (!sdkChatActive) return
        val service = GlassSdk.getGlassAiChatService()
        runCatching {
            service?.endAiChat()
            service?.removeAIChatListener()
        }
        sdkChatActive = false
    }

    /**
     * 通过 SDK 自定义消息把结果和图片流同步到手机，保持手机记录及 PDF 功能。
     * Relay the result and image stream to the phone through SDK custom messages for history and PDF export.
     */
    private fun relayToPhone(
        jpeg: ByteArray,
        answer: String,
        reportTitle: String,
        reportSummary: String,
    ) {
        val service = GlassSdk.getGlassMessageService() ?: return
        val questionId = UUID.randomUUID().toString()
        val imageTag = "$AI_VISION_IMAGE_TAG$questionId"
        val payload = JSONObject()
            .put("questionId", questionId)
            .put("question", "工业现场巡检")
            .put("answer", answer)
            .put("reportTitle", reportTitle)
            .put("reportSummary", reportSummary)
            .put("imageTag", imageTag)
            .put("timestamp", System.currentTimeMillis())
        val message = CustomMessage().apply {
            type = AI_VISION_RESULT_TYPE
            this.message = payload.toString()
        }
        runCatching {
            service.sendTextMessageByClassicBTWithClient(gson.toJson(message), PHONE_CLIENT_ID)
            service.sendStreamData(
                imageTag,
                jpeg,
                PHONE_CLIENT_ID,
                object : IResultCallback.Stub() {
                    override fun onSuccess(result: Boolean) = Unit

                    override fun onFailed(code: Int, message: String?) {
                        Log.w(TAG, "AI vision image relay failed code=$code message=$message")
                    }
                },
            )
        }.onFailure {
            Log.w(TAG, "AI vision result relay failed: ${it.message}")
        }
    }

    private fun appendAnswerContent(content: String) {
        mBinding.tvAiContent.text = content
        mBinding.tvAiContent.visibility = View.VISIBLE
        mBinding.layoutStatus.visibility = View.GONE
        mBinding.scrollView.visibility = View.VISIBLE
        mBinding.scrollView.post {
            mBinding.scrollView.requestLayout()
            mBinding.scrollView.scrollTo(0, 0)
        }
    }

    private fun resetResult() {
        mBinding.tvAiContent.text = ""
        mBinding.tvAiContent.visibility = View.GONE
        mBinding.layoutStatus.visibility = View.GONE
        mBinding.scrollView.visibility = View.VISIBLE
    }

    private fun reset() {
        resetResult()
        mBinding.ivLoad.clearAnimation()
    }

    private fun loadStatus(status: Int, content: String = "") {
        mBinding.ivLoad.clearAnimation()
        when (status) {
            STATUS_IDLE -> {
                mBinding.layoutStatus.visibility = View.GONE
                mBinding.ivLoad.setImageResource(R.mipmap.icon_id_load)
                mBinding.tvLoadTips.text = ""
            }

            STATUS_ANALYZING -> {
                mBinding.layoutStatus.visibility = View.VISIBLE
                mBinding.ivLoad.setImageResource(R.mipmap.icon_id_load)
                mBinding.tvLoadTips.text = content.ifBlank {
                    demoString(R.string.demo_vision_analyzing)
                }
                mBinding.ivLoad.startAnimation(rotateAnimation)
            }

            STATUS_DONE -> {
                mBinding.layoutStatus.visibility = View.GONE
                mBinding.ivLoad.setImageResource(R.mipmap.icon_load_complete)
                mBinding.tvLoadTips.text = ""
            }

            STATUS_ERROR -> {
                mBinding.layoutStatus.visibility = View.VISIBLE
                mBinding.ivLoad.setImageResource(R.mipmap.icon_id_load)
                mBinding.tvLoadTips.text = content
            }
        }
    }

    private companion object {
        const val TAG = "IdentificationActivity"
        const val STATUS_IDLE = 0
        const val STATUS_ANALYZING = 1
        const val STATUS_DONE = 2
        const val STATUS_ERROR = 3
        const val PHONE_CLIENT_ID = "SecurityPhone"
        const val AI_VISION_RESULT_TYPE = "AI_VISION_RESULT"
        const val AI_VISION_IMAGE_TAG = "AI_VISION_IMAGE:"
        const val SDK_VISION_INTENT = 3
    }
}
