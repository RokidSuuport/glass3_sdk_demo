package com.rokid.glass

import android.graphics.BitmapFactory
import android.graphics.drawable.AnimationDrawable
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import androidx.appcompat.widget.AppCompatButton
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.rokid.glass.base.BaseGlassActivity
import com.rokid.glass.base.GlassKeyEvent
import com.rokid.glass.utils.Scopes.workScope
import com.rokid.glesse.R
import com.rokid.glesse.databinding.ActivithyIdentBinding
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.uitls.log.L
import com.rokid.security.sdk.base.common.IdentificationInputType
import com.rokid.security.system.server.identification.IIdentificationService
import com.rokid.security.system.server.identification.listener.IdentificationListener
import com.rokid.security.system.server.identification.listener.ImageListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * 证件/图片识别演示页。
 *
 * 负责启动和结束 SDK 识别任务，接收图片、文本、错误回调，并适配眼镜触摸板按键。
 */
class IdentificationActivity : BaseGlassActivity() {

    private val contentString = StringBuilder()
    private lateinit var mBinding: ActivithyIdentBinding
    private val TAG = "IdentifiyActivity"

    // 页面状态枚举，统一交给 loadStatus() 修改按钮、图标、文案和动画。
    private val STATUS_IDLE = -1
    private val STATUS_START_LOAD = 0
    private val STATUS_WORK = 1
    private val STATUS_TOOL_LOAD = 2
    private val STATUS_WORK_FINISH = 3
    private val STATUS_STOP = 4
    private val STATUS_ERROR = 5
    private val STATUS_CAPTURE = 6
    private var rotateAnimation: Animation? = null
    private var identificationListener: IdentificationListener? = null
    private var imageListener: MyImageListener? = null

    // 拍照图片回调超时兜底，避免相机异常时一直停留在 ivImage 预览区域。
    private var captureTimeoutJob: Job? = null

    // AnimationDrawable 没有结束回调，用协程按总帧时长模拟结束事件。
    private var captureAnimationEndJob: Job? = null

    // 当前是否有识别任务正在执行，用于控制按钮可用状态和异常兜底。
    private var isTaskRunning = false

    // 当前任务是否已经结束；迟到的 SDK 回调需要根据它直接忽略。
    private var isIdentificationEnded = false

    // 触摸板滑动当前选中的按钮 id，和按钮 selected 状态保持一致。
    private var currentSelectedButtonId = R.id.btStartIdent

    // 记录上一次真正 requestFocus 的按钮，避免流式回调期间重复抢焦点和刷日志。
    private var currentFocusedButtonId = View.NO_ID

    // 按钮 OnClick 已消费同一次触摸序列时，忽略 BaseGlassActivity 延迟补发的一次 KEYCODE_CLICK。
    private var ignoreNextTouchPadClick = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBinding = ActivithyIdentBinding.inflate(layoutInflater)
        setContentView(mBinding.root)
        identificationListener = MyIdentificationListener(this)
        imageListener = MyImageListener(this)
        rotateAnimation = AnimationUtils.loadAnimation(this, R.anim.rotate_loading)
        identificationService()?.apply {
            // 进入识别模式后同时注册文本和图片回调，图片回调用于展示本次识别拍到的画面。
            setIdentificationListener(identificationListener)
            setImageListener(imageListener)
        }
        reset()
        loadStatus(STATUS_IDLE, "")
        updateButtons()
        mBinding.btStartIdent.setOnClickListener {
            Log.d(TAG, "-------点击了开始识别按钮-------")
            ignoreNextTouchPadClick = true
            startIdentification()
        }
        mBinding.btEndIdent.setOnClickListener {
            Log.d(TAG, "-------点击了结束识别按钮-------")
            ignoreNextTouchPadClick = true
            endIdentification()
        }
    }

    /**
     * 处理触摸板/指环按键。
     *
     * 滑动只切换“开始识别/结束识别”的选中态；点击才执行当前选中的动作。
     */
    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        when (keyEvent) {
            GlassKeyEvent.KEYCODE_BEHIND -> {
                ignoreNextTouchPadClick = false
                selectIdentificationButton(R.id.btStartIdent)
                return true
            }

            GlassKeyEvent.KEYCODE_FRONT -> {
                ignoreNextTouchPadClick = false
                selectIdentificationButton(R.id.btEndIdent)
                return true
            }

            GlassKeyEvent.KEYCODE_CLICK -> {
                clickSelectedIdentificationButton()
                return true
            }
        }
        return super.onGlassKeyEvent(keyEvent)
    }

    /**
     * 开始一轮识别。
     *
     * 先清理上一轮遗留任务，再重置状态，最后启动拍照动画和 SDK 识别任务。
     */
    private fun startIdentification() {
        lifecycleScope.launch {
            clearIdentificationTask()
            isTaskRunning = true
            isIdentificationEnded = false
            updateButtons()
            // 识别任务和拍照动画并行开始；动画只负责视觉反馈，不阻塞 executeIdentificationTask。
            startCaptureAnimation()
            loadStatus(STATUS_CAPTURE, "")
            runCatching {
                identificationService()?.executeIdentificationTask(IDENTIFICATION_ID)
            }.onFailure {
                logErrorStack("executeIdentificationTask failed", it)
                errorHandle(0, "启动识别失败")
            }
        }
    }

    /**
     * 图片回调 listener。
     *
     * SDK listener 可能比 Activity 活得更久，因此只持有 Activity 弱引用。
     */
    private class MyImageListener(activity: IdentificationActivity) : ImageListener.Stub() {
        private val activityRef = WeakReference(activity)
        override fun onImageBack(imageBase64: String?) {
            activityRef.get()?.apply {
                workScope.launch {
                    Log.d(TAG, "------onImageBack-----")
                    if (imageBase64.isNullOrBlank()) {
                        withContext(Dispatchers.Main) {
                            logErrorStack("handleCameraImageError")
                            handleCameraImageError("拍照返回为空")
                        }
                    } else {
                        imageBack(imageBase64)
                    }
                }
            }
        }
    }

    /**
     * 处理图片回调。
     *
     * 当前页面主要把它当作“相机链路已返回”的信号，收到后取消拍照超时兜底。
     */
    suspend fun imageBack(imageBase64: String) {
        // 收到图片说明相机链路已返回，不再需要超时兜底。
        cancelCaptureTimeout()
        try {
            // 兼容 data:image/jpeg;base64,xxxx 这类带前缀的返回值。
            var base64Data = imageBase64
            if (base64Data.contains(",")) {
                base64Data = base64Data.substringAfter(",")
            }
            val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
            if (bitmap != null) {
                withContext(Dispatchers.Main) {
                    if (isIdentificationEnded) {
                        return@withContext
                    }
                    mBinding.ivImage.clearAnimation()
                    mBinding.ivImage.visibility = View.VISIBLE
                    mBinding.ivImage.setImageBitmap(bitmap)
                    Log.d(TAG, "imageBack: image displayed successfully")
                }
            } else {
                withContext(Dispatchers.Main) {
                    handleCameraImageError("拍照图片解析失败")
                }
            }
        } catch (e: Exception) {
            logErrorStack("imageBack: error processing image", e)
            withContext(Dispatchers.Main) {
                handleCameraImageError("拍照图片处理异常")
            }
        }
    }

    private fun handleCameraImageError(message: String) {
        // 拍照图片为空、解析失败或超时都视为相机链路异常；识别依赖当前相机帧，异常时直接结束任务。
        if (isTaskRunning && !isIdentificationEnded) {
            endIdentification(STATUS_ERROR, "$message，已结束识别")
        }
    }

    /**
     * 清理上一轮识别任务，并按需重置 UI。
     *
     * 连续点击开始时，先 endTask 再短暂等待，可降低旧回调串入新任务的概率。
     */
    private suspend fun clearIdentificationTask(isClearContent: Boolean = true) {
        // 先通知 SDK 结束上一轮任务，短暂等待可降低连续点击时旧回调串到新任务的概率。
        identificationService()?.endTask()
        delay(200)
        if (isClearContent) {
            reset()
        }
    }

    /**
     * 识别文本和错误回调 listener，同样使用弱引用防止页面泄漏。
     */
    private class MyIdentificationListener(activity: IdentificationActivity) : IdentificationListener.Stub() {
        private val activityRef = WeakReference(activity)

        override fun onIdentificationAnswer(answer: String?, type: String, isFinish: Boolean) {
            activityRef.get()?.apply {
                lifecycleScope.launch {
                    contentHandle(answer, type, isFinish)
                }
            }
        }

        override fun onError(code: Int, message: String?) {
            activityRef.get()?.apply {
                lifecycleScope.launch {
                    errorHandle(code, message)
                }
            }
        }
    }

    /**
     * 处理 SDK 输出的文本流。
     *
     * Inputing/InputOver 追加到答案区，Thinking 只作为状态提示，任务完成后恢复按钮状态。
     */
    private fun contentHandle(answer: String?, type: String, isFinish: Boolean) {
        Log.d(TAG, "contentHandle->" + answer + " type:" + type + " isFinish:" + isFinish)
        if (isIdentificationEnded) {
            Log.d(TAG, "contentHandle ignored because identification ended")
            return
        }

        val content = answer.orEmpty()
        when (type) {
            IdentificationInputType.Inputing -> {
                // Inputing 是模型输出文本，直接追加到答案区。
                appendAnswerContent(content)
                loadStatus(STATUS_WORK, content)
            }

            IdentificationInputType.Thinking -> {
                // Thinking 只作为状态提示，不混入最终答案。
                loadStatus(STATUS_TOOL_LOAD, content)
            }

            IdentificationInputType.InputOver -> {
                appendAnswerContent(content)
            }
        }

        if (isFinish || type == IdentificationInputType.InputOver) {
            // 任务结束后恢复“开始识别”按钮，并停止加载动画。
            isTaskRunning = false
            updateButtons()
            loadStatus(STATUS_WORK_FINISH, "")
        }
    }

    /**
     * 追加模型输出内容，并让 ScrollView 滚动到底部。
     */
    private fun appendAnswerContent(content: String) {
        if (content.isBlank()) {
            return
        }
        Log.d(TAG, "appendAnswerContent-->" + content)
        // 拍照预览显示期间先缓存文字，等预览收起后再显示 ScrollView，避免画面跳动。
        mBinding.ivImage.visibility = View.GONE
        mBinding.tvAiContent.visibility = View.VISIBLE
        mBinding.scrollView.visibility = View.VISIBLE
        contentString.append(content)
        mBinding.tvAiContent.text = contentString.toString()
        updateIvLoadTopMargin()
        mBinding.scrollView.post {
            mBinding.scrollView.requestLayout()
            mBinding.scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    /**
     * 处理 SDK 错误回调。
     *
     * 错误表示本轮识别不可继续，统一走 endIdentification() 清理 SDK 任务和 listener。
     */
    private fun errorHandle(code: Int, message: String?) {
        logErrorStack("errorHandle-->$code,---->message=$message")
        if (isIdentificationEnded) {
            Log.d(TAG, "errorHandle ignored because identification ended")
            return
        }
        val errorMessage = message.orEmpty().ifBlank { "识别失败($code)" }
        // SDK 回调 onError 表示本轮任务已经不可继续，统一走结束流程，确保 task 和 listener 被清理。
        endIdentification(STATUS_ERROR, errorMessage)
    }

    /**
     * 重置本页 UI 状态。
     *
     * 这里只处理界面，不直接停止 SDK 任务；任务停止由调用方在进入 reset 前完成。
     */
    private fun reset() {
        // 重置所有本页 UI 状态，不调用 SDK；SDK 任务的停止由调用方控制。
        contentString.clear()
        mBinding.tvAiContent.text = ""
        cancelCaptureJobs()
        mBinding.ivImage.clearAnimation()
        mBinding.ivLoad.clearAnimation()
        mBinding.ivImage.setImageResource(R.drawable.camera_frame_animation)
        mBinding.ivImage.visibility = View.GONE
        mBinding.scrollView.visibility = View.VISIBLE
        mBinding.tvAiContent.visibility = View.GONE
        updateIvLoadTopMargin()
    }

    /**
     * 根据答案区域是否可见，调整加载状态行的位置。
     */
    private fun updateIvLoadTopMargin() {
        val ivLoad = mBinding.ivLoad
        // 获取iv_load的布局参数
        val layoutParams = ivLoad.layoutParams as? ConstraintLayout.LayoutParams ?: return
        // 根据识别内容是否显示，给状态行留出间距。
        layoutParams.topMargin = if (mBinding.tvAiContent.isVisible) {
            dp(12)
        } else {
            0
        }
        // 应用修改后的布局参数
        ivLoad.layoutParams = layoutParams
    }

    /**
     * 统一维护页面状态。
     *
     * 所有影响按钮选中态、加载图标、提示文案和加载动画的修改都收敛在这里。
     */
    private fun loadStatus(status: Int, content: String) {
        // 所有状态文案和按钮选中态集中在这里，避免回调里分散改 UI。
        when (status) {
            STATUS_IDLE -> {
                mBinding.ivLoad.clearAnimation()
                currentSelectedButtonId = R.id.btStartIdent
                updateButtons()
                mBinding.ivLoad.setImageResource(R.mipmap.icon_id_load)
                mBinding.tvLoadTips.text = "等待开始识别"
            }

            STATUS_START_LOAD -> {
                currentSelectedButtonId = R.id.btEndIdent
                updateButtons()
                mBinding.ivLoad.setImageResource(R.mipmap.icon_id_load)
                mBinding.tvLoadTips.text = "已收到指令，正在为您查询"
                startRotateAnimation()
            }

            STATUS_CAPTURE -> {
                currentSelectedButtonId = R.id.btEndIdent
                updateButtons()
                mBinding.ivLoad.setImageResource(R.mipmap.icon_id_load)
                mBinding.tvLoadTips.text = "正在拍照识别"
                startRotateAnimation()
            }

            STATUS_WORK -> {
                currentSelectedButtonId = R.id.btEndIdent
                updateButtons()
                mBinding.ivLoad.setImageResource(R.mipmap.icon_id_load)
                mBinding.tvLoadTips.text = "正在输出"
                startRotateAnimation()
            }

            STATUS_TOOL_LOAD -> {
                currentSelectedButtonId = R.id.btEndIdent
                updateButtons()
                mBinding.ivLoad.setImageResource(R.mipmap.icon_id_load)
                mBinding.tvLoadTips.text = content.ifBlank { "正在思考" }
                startRotateAnimation()
            }

            STATUS_WORK_FINISH -> {
                currentSelectedButtonId = R.id.btStartIdent
                updateButtons()
                mBinding.ivLoad.clearAnimation()
                mBinding.ivLoad.setImageResource(R.mipmap.icon_load_complete)
                mBinding.tvLoadTips.text = "输出完成"
            }

            STATUS_STOP -> {
                currentSelectedButtonId = R.id.btStartIdent
                updateButtons()
                mBinding.ivLoad.clearAnimation()
                mBinding.ivLoad.setImageResource(R.mipmap.icon_load_complete)
                mBinding.tvLoadTips.text = "已结束识别"
            }

            STATUS_ERROR -> {
                currentSelectedButtonId = R.id.btStartIdent
                updateButtons()
                mBinding.ivLoad.clearAnimation()
                mBinding.ivLoad.setImageResource(R.mipmap.icon_id_load)
                mBinding.tvLoadTips.text = content
            }
        }
    }

    private fun startRotateAnimation() {
        rotateAnimation?.let {
            if (mBinding.ivLoad.animation == null) {
                mBinding.ivLoad.startAnimation(it)
            }
        }
    }

    /**
     * 展示拍照动画，并启动动画结束和图片超时两个兜底任务。
     */
    private fun startCaptureAnimation() {
        cancelCaptureJobs()
        mBinding.ivImage.clearAnimation()
        mBinding.ivImage.setImageResource(R.drawable.camera_frame_animation)
        mBinding.ivImage.visibility = View.VISIBLE
        mBinding.scrollView.visibility = View.GONE
        val animationDrawable = mBinding.ivImage.drawable as? AnimationDrawable
        animationDrawable?.start()
        // AnimationDrawable 没有原生结束回调；按帧总时长调度，结束后立刻切回内容区域。
        scheduleCaptureAnimationEnd(animationDrawable)
        // 若图片回调异常缺失，用超时兜底结束本轮识别。
        startCaptureTimeout()
    }

    /**
     * 图片回调超时未返回时，按相机异常结束本轮识别。
     */
    private fun startCaptureTimeout() {
        captureTimeoutJob = lifecycleScope.launch {
            delay(CAPTURE_TIMEOUT_MS)
            if (mBinding.ivImage.isVisible && isTaskRunning && !isIdentificationEnded) {
                // 超时说明相机图片回调未按期返回，按相机异常处理并结束本轮识别。
                handleCameraImageError("拍照超时")
            }
        }
    }

    /**
     * 计算 AnimationDrawable 的总帧时长，模拟“动画播放结束”回调。
     */
    private fun scheduleCaptureAnimationEnd(animationDrawable: AnimationDrawable?) {
        val totalDuration = animationDrawable?.let { drawable ->
            var duration = 0L
            for (index in 0 until drawable.numberOfFrames) {
                duration += drawable.getDuration(index).toLong()
            }
            duration
        } ?: CAPTURE_ANIMATION_DURATION_MS

        captureAnimationEndJob = lifecycleScope.launch {
            delay(totalDuration)
            if (mBinding.ivImage.isVisible && !isIdentificationEnded) {
                finishCapturePreview(false)
            }
        }
    }

    private fun cancelCaptureTimeout() {
        captureTimeoutJob?.cancel()
        captureTimeoutJob = null
    }

    private fun cancelCaptureAnimationEnd() {
        captureAnimationEndJob?.cancel()
        captureAnimationEndJob = null
    }

    private fun cancelCaptureJobs() {
        cancelCaptureTimeout()
        cancelCaptureAnimationEnd()
    }

    /**
     * 结束拍照预览。
     *
     * isEndingTask 为 true 表示整轮识别正在结束，此时不再把状态切回“查询中”。
     */
    private fun finishCapturePreview(isEndingTask: Boolean) {
        if (isIdentificationEnded && !isEndingTask) {
            return
        }
        cancelCaptureAnimationEnd()
        mBinding.ivImage.clearAnimation()
        mBinding.ivImage.visibility = View.GONE
        mBinding.ivImage.setImageResource(R.drawable.camera_frame_animation)
        mBinding.scrollView.visibility = View.VISIBLE
        // 图片阶段结束后，如果答案流还没开始，就回到查询态继续等服务端响应。
        if (!isEndingTask && isTaskRunning && !isIdentificationEnded) {
            loadStatus(STATUS_START_LOAD, "")
        }
    }

    /**
     * 结束识别并清理 SDK listener。
     *
     * 用户主动结束、相机异常、SDK 错误都会走这里，保证资源释放路径一致。
     */
    private fun endIdentification(status: Int = STATUS_STOP, statusContent: String = "") {
        cancelCaptureJobs()
        finishCapturePreview(true)
        // 结束识别时同时清理 SDK listener，避免页面结束后继续收到图片/文本回调。
        identificationService()?.apply {
            endTask()
            endIdentification()
        }
        isTaskRunning = false
        isIdentificationEnded = true
        updateButtons()
        loadStatus(status, statusContent)
    }

    override fun onResume() {
        super.onResume()
        identificationService()?.enterIdentificationMode(false)
    }

    override fun onPause() {
        super.onPause()
        identificationService()?.exitIdentificationMode()
    }

    /**
     * 根据任务状态和当前选中按钮，同步两个按钮的 enabled/selected 状态。
     */
    private fun updateButtons() {
        mBinding.btStartIdent.isEnabled = !isTaskRunning
        mBinding.btEndIdent.isEnabled = isTaskRunning && !isIdentificationEnded
        mBinding.btStartIdent.isSelected = currentSelectedButtonId == R.id.btStartIdent
        mBinding.btEndIdent.isSelected = currentSelectedButtonId == R.id.btEndIdent

        val targetButton = when {
            mBinding.btStartIdent.isEnabled -> mBinding.btStartIdent
            mBinding.btEndIdent.isEnabled -> mBinding.btEndIdent
            else -> null
        } ?: return

        if (currentFocusedButtonId == targetButton.id && targetButton.isFocused) {
            return
        }

        selectBtn(targetButton)
        currentFocusedButtonId = targetButton.id
        when (targetButton.id) {
            R.id.btStartIdent -> L.d(TAG, "-----开始按钮获取了焦点")
            R.id.btEndIdent -> L.d(TAG, "-----结束按钮获取了焦点")
        }
    }

    /**
     * 触摸板滑动时切换当前选中的按钮。
     */
    private fun selectIdentificationButton(buttonId: Int) {
        currentSelectedButtonId = buttonId
        currentFocusedButtonId = View.NO_ID
        updateButtons()
    }

    fun unAllSelectState(viewGroup: ViewGroup) {
        for (i in 0 until viewGroup.childCount) {
            if (viewGroup.getChildAt(i) is ViewGroup) {
                unAllSelectState(viewGroup.getChildAt(i) as ViewGroup)
            } else if (viewGroup.getChildAt(i) is AppCompatButton) {
                unSelectBtn(viewGroup.getChildAt(i) as AppCompatButton)
            }
        }
    }

    private fun unSelectBtn(textView: AppCompatButton) {
        textView.clearFocus()
        textView.isSelected = false
    }

    private fun selectBtn(textView: AppCompatButton) {
        textView.isSelected = true
        textView.isClickable = true
        textView.isFocusable = true
        textView.isFocusableInTouchMode = true
        textView.requestFocus()
    }

    /**
     * 触摸板/指环点击兜底。
     *
     * AR 眼镜上焦点有时不会把 ENTER 分发给按钮本身，所以这里根据当前选中按钮直接执行业务。
     */
    private fun clickSelectedIdentificationButton() {
        if (ignoreNextTouchPadClick) {
            ignoreNextTouchPadClick = false
            Log.d(TAG, "ignore touchpad click handled by button")
            return
        }
        when (currentSelectedButtonId) {
            R.id.btStartIdent -> {
                if (mBinding.btStartIdent.isEnabled) {
                    startIdentification()
                }
            }

            R.id.btEndIdent -> {
                if (mBinding.btEndIdent.isEnabled) {
                    endIdentification()
                }
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    /**
     * 打印完整堆栈信息。
     *
     * throwable 不为空时打印真实异常堆栈；为空时创建 Throwable 捕获当前调用栈，便于定位回调来源。
     */
    private fun logErrorStack(message: String, throwable: Throwable? = null) {
        val stackTrace = Log.getStackTraceString(throwable ?: Throwable(message))
        Log.e(TAG, "$message\n$stackTrace")
    }

    private fun identificationService(): IIdentificationService? {
        return GlassSdk.getGlassIdentificationService()
    }

    /**
     * 页面销毁时做完整清理，防止识别任务和 listener 脱离 Activity 生命周期。
     */
    override fun onDestroy() {
        cancelCaptureJobs()
        // Activity 销毁时做完整清理，防止 SDK 任务和本页生命周期脱钩。
        identificationService()?.apply {
            removeIdentificationListener()
            removeImageListener()
            endIdentification()
        }
        mBinding.ivImage.setImageBitmap(null)
        super.onDestroy()
    }

    private companion object {
        const val IDENTIFICATION_ID = "identification"
        const val CAPTURE_ANIMATION_DURATION_MS = 1000L
        const val CAPTURE_TIMEOUT_MS = 5_000L
    }
}
