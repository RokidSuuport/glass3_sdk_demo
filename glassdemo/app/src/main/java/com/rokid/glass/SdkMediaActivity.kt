package com.rokid.glass

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.rokid.glass.base.BaseGlassActivity
import com.rokid.glass.base.GlassKeyEvent
import com.rokid.glass.camera.CameraMediaController
import com.rokid.glass.camera.CaptureSize
import com.rokid.glass.camera.MediaCaptureSettings
import com.rokid.glass.camera.MediaCaptureSettingsStore
import com.rokid.glass.camera.MediaSettingsFragment
import com.rokid.glass.camera.MediaSettingsTarget
import com.rokid.glass.utils.demoString
import com.rokid.glesse.R
import com.rokid.glesse.databinding.ActivityTestMediaBinding
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.uitls.log.L
import com.rokid.security.glass3.sdk.base.data.media.RecordConfig
import com.rokid.security.system.server.media.callback.PhotoFileCallback
import com.rokid.security.system.server.media.callback.VideoCallback
import com.rokid.security.system.server.media.listener.IMediaStateLister
import kotlinx.coroutines.launch
import java.io.File

/**
 * 拍照、录像各有独立设置，模式选择保留在设置内。
 * Photo and video have separate settings, with mode selection inside each settings page.
 */
class SdkMediaActivity : BaseGlassActivity() {
    override val translateTouchToGlassGesture = false
    private lateinit var binding: ActivityTestMediaBinding
    private val settingsStore by lazy { MediaCaptureSettingsStore(this) }
    internal var captureSettings = MediaCaptureSettings()
        private set
    private val optionsController = CameraMediaController { log(it) }
    private lateinit var mediaButtons: List<AppCompatButton>
    private var selected = 0
    private val settingsPage get() = supportFragmentManager.findFragmentByTag("media-settings") as? MediaSettingsFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureSettings = settingsStore.load()
        binding = ActivityTestMediaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        GlassSdk.getGlassMediaService()?.addPhotoCallback(mPhotoFileCallback)
        GlassSdk.getGlassMediaService()?.setMediaStateLister(mICameraStateLister)
        mediaButtons = listOf(binding.btPhoto, binding.btnPhotoSettings, binding.btStartRecord,
            binding.btStopRecord, binding.btnVideoSettings, binding.btCameraShare)
        mediaButtons.forEachIndexed { index, button ->
            button.setOnFocusChangeListener { _, focused ->
                if (focused && selected != index) select(index)
            }
        }
        bindAction(binding.btPhoto) {
            val request = captureSettings
            val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val path = File(directory, "test_${System.currentTimeMillis()}.jpg").absolutePath
            optionsController.photo(request.photoApi, request.photo.width, request.photo.height, path,
                request.photoZoom, request.quality, request.reusePhotoResolution)
        }
        bindAction(binding.btStartRecord) {
            val request = captureSettings
            val path = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "video").absolutePath
            // 使用设置里的最终宽高；不再为每种分辨率复制一个开始按钮。
            // Use the saved output size with a single recording action.
            val config = RecordConfig(path, min = 1, enableAudio = true,
                width = request.video.width, height = request.video.height, fps = 20,
                // 保留原 720P 示例的 AR 叠加行为，其他尺寸沿用普通录像。
                // Preserve the original 720P AR overlay; other sizes use ordinary recording.
                isArRMixEnabled = request.video == CaptureSize(1280, 720))
            optionsController.startRecord(request.videoApi, config, request.videoZoom, request.recordWatermark, videoCallback)
        }
        bindAction(binding.btStopRecord) { optionsController.stopRecord() }
        bindAction(binding.btCameraShare) {
            startActivity(Intent(this, CameraShareSelectActivity::class.java))
        }
        bindAction(binding.btnPhotoSettings) { openMediaSettings(MediaSettingsTarget.PHOTO) }
        bindAction(binding.btnVideoSettings) { openMediaSettings(MediaSettingsTarget.VIDEO) }
        renderSummary()
        select(0)
        val restoredTarget = settingsPage?.target ?: savedInstanceState?.getString("settings-target")?.let {
            runCatching { MediaSettingsTarget.valueOf(it) }.getOrNull()
        }
        restoredTarget?.let(::openMediaSettings)
    }

    private fun bindAction(button: AppCompatButton, action: () -> Unit) {
        button.setOnClickListener {
            select(mediaButtons.indexOf(button))
            action()
        }
    }

    private fun openMediaSettings(target: MediaSettingsTarget) {
        binding.mediaMainPage.visibility = View.GONE
        binding.mediaSettingsHost.visibility = View.VISIBLE
        if (settingsPage == null) supportFragmentManager.beginTransaction()
            .replace(R.id.mediaSettingsHost, MediaSettingsFragment.newInstance(target), "media-settings").commitNow()
    }

    internal fun closeMediaSettings() {
        val target = settingsPage?.target
        settingsPage?.let { supportFragmentManager.beginTransaction().remove(it).commitNow() }
        binding.mediaSettingsHost.visibility = View.GONE
        binding.mediaMainPage.visibility = View.VISIBLE
        renderSummary()
        select(if (target == MediaSettingsTarget.PHOTO) 1 else 4)
    }

    internal fun updateMediaSettings(value: MediaCaptureSettings) {
        fun apply() {
            captureSettings = value
            settingsStore.save(value)
            renderSummary()
            settingsPage?.render()
        }
        // 切换 API 时沿用原有忙碌保护；修改下次参数不终止当前录像。
        // Keep busy protection for mode changes; editing future parameters does not stop recording.
        if (value.photoApi != captureSettings.photoApi || value.videoApi != captureSettings.videoApi)
            optionsController.changeApi { apply() } else apply()
    }

    internal fun applyMediaZoom() = optionsController.zoom(captureSettings.videoZoom,
        captureSettings.videoApi == CameraMediaController.Api.OPTIONS_V2)
    internal fun followMediaZoom() = optionsController.followShared()

    private fun renderSummary() {
        binding.tvPhotoSummary.text = getString(R.string.media_photo_summary,
            captureSettings.photo.toString(), captureSettings.photoZoom.toString())
        binding.tvVideoSummary.text = getString(R.string.media_video_summary,
            captureSettings.video.toString(), captureSettings.videoZoom.toString())
    }

    private fun select(index: Int) {
        selected = index
        mediaButtons.forEachIndexed { position, button ->
            val focused = position == index
            button.isSelected = focused
            button.setTextColor(ContextCompat.getColor(this, if (focused) R.color.green else R.color.green_70))
            button.setBackgroundResource(if (focused) R.drawable.round_select_bg else R.drawable.round_unselect_bg)
        }
        mediaButtons[index].apply {
            requestFocusFromTouch()
            post {
                if (isShown && isSelected) requestRectangleOnScreen(Rect(0, 0, width, height), true)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !::mediaButtons.isInitialized) return
        // 窗口就绪后退出触摸模式，避免系统吞掉第一次镜腿方向键。
        // Leave touch mode once the window is ready so the first temple key is not consumed.
        val page = settingsPage
        if (page != null) page.restoreSelectionFocus() else select(selected)
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        settingsPage?.let { return it.onGlassKeyEvent(keyEvent) }
        when (keyEvent) {
            GlassKeyEvent.KEYCODE_FRONT -> select((selected + 1) % mediaButtons.size)
            GlassKeyEvent.KEYCODE_BEHIND -> select((selected - 1 + mediaButtons.size) % mediaButtons.size)
            GlassKeyEvent.KEYCODE_CLICK -> mediaButtons[selected].performClick()
            GlassKeyEvent.KEYCODE_BACK, GlassKeyEvent.KEYCODE_DOUBLE_CLICK -> onBackPressed()
            else -> return super.onGlassKeyEvent(keyEvent)
        }
        return true
    }

    override fun onBackPressed() {
        if (settingsPage != null) closeMediaSettings() else super.onBackPressed()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("settings-target", settingsPage?.target?.name)
        super.onSaveInstanceState(outState)
    }
    override fun onStart() { super.onStart(); optionsController.resume() }
    override fun onStop() { optionsController.pause(); super.onStop() }

    private val mPhotoFileCallback = object : PhotoFileCallback.Stub() {
        override fun onTakePhoto(path: String) {
            optionsController.legacyPhotoCompleted(path)
            log(demoString(R.string.demo_ontakephoto_path_status_13, path))
        }

        override fun getCallbackId(): String {
            return "10002"
        }

        override fun onTakePhotoV2(path: String, width: Int, height: Int) {
            optionsController.legacyPhotoCompleted(path, width, height)
            log(demoString(R.string.demo_ontakephoto_width_height_path_status_14, width, height, path))
        }
    }

    private val mICameraStateLister = object : IMediaStateLister.Stub() {
        override fun onCameraResolutionChange(width: Int, height: Int) {
            log(demoString(R.string.demo_oncameraresolutionchange_width_height_status_15, width, height))
        }

        override fun onCameraError(code: Int, errorMsg: String) {
            log(demoString(R.string.demo_oncameraerror_code_errormsg_status_16, code, errorMsg))
        }
    }

    private val videoCallback = object : VideoCallback.Stub() {
        override fun onError() {
            L.w("CameraDemo", "Recording error")
            log(demoString(R.string.demo_onerror_status_21))
        }

        override fun onFinish() {
            L.i("CameraDemo", "Recording finished")
            log(demoString(R.string.demo_onfinish_status_22))
        }

        override fun onNewFile(startTime: Long, endTime: Long, path: String, isLast: Boolean) {
            log(demoString(R.string.demo_onnewfile_status_23, path))
        }

        override fun onErrorWithDetail(code: Int, errorMsg: String) {
            L.w("CameraDemo", "Recording error=$code")
            log(demoString(R.string.demo_onerror_code_errormsg_status_24, code, errorMsg))
        }

        override fun onStart() {
            L.i("CameraDemo", "Recording started")
            log(demoString(R.string.demo_onstart_status_25))
        }
    }

    private val logBuilder = StringBuilder()
    private fun log(msg: String) {
        lifecycleScope.launch {
            if (logBuilder.length > 5000) logBuilder.clear()
            logBuilder.insert(0, "$msg\n")
            binding.tvLog.text = logBuilder.toString()
            settingsPage?.showStatus(msg)
        }
        Log.d("SdkMediaActivity", msg)
    }

    override fun onDestroy() {
        GlassSdk.getGlassMediaService()?.removePhotoCallback(mPhotoFileCallback)
        GlassSdk.getGlassMediaService()?.removeMediaStateLister(mICameraStateLister)
        optionsController.close()
        super.onDestroy()
    }
}
