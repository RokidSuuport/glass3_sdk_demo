package com.rokid.glass.camera

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.rokid.glass.SdkMediaActivity
import com.rokid.glass.base.GlassKeyEvent
import com.rokid.glass.base.GlassKeyInput
import com.rokid.glesse.R
import com.rokid.glesse.databinding.FragmentMediaSettingsBinding
import com.rokid.security.glass3.open.sdk.camera.CameraShareHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置保留在宿主 Activity 内，避免打开设置时停止录像。
 * Keep settings in the host Activity so opening them does not stop recording.
 */
class MediaSettingsFragment : Fragment(R.layout.fragment_media_settings) {
    private var binding: FragmentMediaSettingsBinding? = null
    private val host get() = requireActivity() as SdkMediaActivity
    // 旧版共用设置页被系统恢复时，兼容没有 target 参数的 Fragment。
    // Older saved fragments had no scope argument; restore them as photo settings.
    internal val target get() = arguments?.getString("target")?.let(MediaSettingsTarget::valueOf)
        ?: MediaSettingsTarget.PHOTO
    private val photo get() = target == MediaSettingsTarget.PHOTO
    private val settings get() = host.captureSettings
    private val api get() = settings.api(target)
    private val zoom get() = settings.zoom(target)

    companion object {
        internal fun newInstance(target: MediaSettingsTarget) = MediaSettingsFragment().apply {
            arguments = Bundle().apply { putString("target", target.name) }
        }
    }
    private var buttons = emptyList<AppCompatButton>()
    private var selected = 0
    private var choiceDialog: AlertDialog? = null
    private var dialogKeys: ((Int) -> Boolean)? = null
    private var loadingSizes = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentMediaSettingsBinding.bind(view)
        binding = b
        bindAction(b.btnMediaSettingsBack) { host.closeMediaSettings() }
        bindAction(b.btnMediaApiMode) {
            val nextApi = if (api == CameraMediaController.Api.OPTIONS_V2) CameraMediaController.Api.LEGACY else CameraMediaController.Api.OPTIONS_V2
            host.updateMediaSettings(settings.withApi(target, nextApi))
        }
        bindAction(b.btnMediaSize) { chooseSize(photo) }
        bindAction(b.btnMediaOptionsZoom) { chooseZoom() }
        bindAction(b.btnSetZoom) { host.applyMediaZoom() }
        bindAction(b.btnMediaFollowShared) { host.followMediaZoom() }
        bindAction(b.btnMediaPhotoQuality) {
            host.updateMediaSettings(settings.copy(quality = when (settings.quality) { 90 -> 100; 100 -> 80; else -> 90 }))
        }
        bindAction(b.btnMediaReuseResolution) { host.updateMediaSettings(settings.copy(reusePhotoResolution = !settings.reusePhotoResolution)) }
        bindAction(b.btnMediaRecordWatermark) { host.updateMediaSettings(settings.copy(recordWatermark = !settings.recordWatermark)) }
        bindAction(b.btnMediaResetDefaults) { host.updateMediaSettings(settings.reset(target)) }
        render()
    }

    private fun bindAction(button: AppCompatButton, action: () -> Unit) {
        button.setOnFocusChangeListener { _, focused ->
            val index = buttons.indexOf(button)
            if (focused && index >= 0 && selected != index) select(index)
        }
        button.setOnClickListener {
            val index = buttons.indexOf(button)
            if (index >= 0) {
                select(index)
                action()
            }
        }
    }

    internal fun render() {
        val b = binding ?: return
        val value = settings
        val options = api == CameraMediaController.Api.OPTIONS_V2
        b.tvMediaSettingsTitle.setText(if (photo) R.string.media_photo_settings else R.string.media_video_settings)
        b.btnMediaApiMode.setText(if (options) R.string.media_api_options else R.string.media_api_legacy)
        b.tvMediaSettingsHint.setText(when {
            photo && options -> R.string.media_photo_settings_hint
            photo -> R.string.media_photo_legacy_hint
            options -> R.string.media_video_settings_hint
            else -> R.string.media_video_legacy_hint
        })
        b.btnMediaSize.text = getString(R.string.media_resolution, (if (photo) value.photo else value.video).toString())
        b.btnMediaOptionsZoom.text = getString(R.string.media_options_zoom, zoom.toString())
        b.btnSetZoom.setText(if (options) R.string.media_zoom_apply else R.string.media_legacy_zoom_apply)
        b.btnMediaPhotoQuality.text = getString(R.string.media_photo_quality, value.quality)
        b.btnMediaReuseResolution.setText(if (value.reusePhotoResolution) R.string.media_reuse_on else R.string.media_reuse_off)
        b.btnMediaRecordWatermark.setText(if (value.recordWatermark) R.string.media_watermark_on else R.string.media_watermark_off)
        b.mediaPhotoOptionsRow.visibility = if (photo && options) View.VISIBLE else View.GONE
        b.mediaVideoOptionsRow.visibility = if (!photo) View.VISIBLE else View.GONE
        b.btnMediaRecordWatermark.visibility = if (options) View.VISIBLE else View.GONE
        b.btnMediaFollowShared.visibility = if (!photo && options) View.VISIBLE else View.GONE
        val oldSelection = buttons.getOrNull(selected)
        // 按当前页的可见功能导航，避免镜腿选中另一类设置或被隐藏的控件。
        // Navigation follows this scope's visible controls, including their parent rows.
        buttons = buildList {
            addAll(listOf(b.btnMediaSettingsBack, b.btnMediaApiMode, b.btnMediaSize, b.btnMediaOptionsZoom))
            if (photo && options) addAll(listOf(b.btnMediaPhotoQuality, b.btnMediaReuseResolution))
            if (!photo) {
                if (options) add(b.btnMediaRecordWatermark)
                add(b.btnSetZoom)
                if (options) add(b.btnMediaFollowShared)
            }
            add(b.btnMediaResetDefaults)
        }
        selected = buttons.indexOf(oldSelection).coerceAtLeast(0)
        select(selected)
    }

    internal fun showStatus(message: String) {
        binding?.tvMediaSettingsStatus?.apply { text = message; visibility = View.VISIBLE }
    }

    internal fun restoreSelectionFocus() {
        if (choiceDialog?.isShowing != true && buttons.isNotEmpty()) select(selected)
    }

    private fun chooseSize(photo: Boolean) {
        fun showSizes(sizes: List<CaptureSize>) {
            val current = if (photo) settings.photo else settings.video
            choose(
                if (photo) R.string.media_photo_size_title else R.string.media_video_size_title,
                sizes.map(CaptureSize::toString),
                sizes.indexOf(current).coerceAtLeast(0),
            ) { index ->
                host.updateMediaSettings(if (photo) settings.copy(photo = sizes[index]) else settings.copy(video = sizes[index]))
            }
        }
        if (photo && api == CameraMediaController.Api.LEGACY) {
            showSizes(MediaCaptureSettings.legacyPhotoSizes.values.distinct())
            return
        }
        if (loadingSizes) return
        loadingSizes = true
        showStatus(getString(R.string.media_sizes_loading))
        viewLifecycleOwner.lifecycleScope.launch {
            // 预览尺寸查询是同步 Binder 调用；旧拍照接口仍只能使用 PhotoResolution 枚举。
            // The preview-size query is synchronous Binder work; legacy photos still use PhotoResolution presets.
            val sizes = withContext(Dispatchers.IO) {
                CameraShareHelper().getSupportedPreviewSizes().mapNotNull { (width, height) ->
                    if (width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
                        CaptureSize(width, height) else null
                }.distinct()
            }
            loadingSizes = false
            if (sizes.isEmpty()) showStatus(getString(R.string.media_sizes_unavailable))
            else {
                showStatus(getString(R.string.media_sizes_found, sizes.size))
                showSizes(sizes)
            }
        }
    }

    private fun chooseZoom() {
        val values = if (api == CameraMediaController.Api.LEGACY) listOf(1f, 2f, 3f, 4f)
            else listOf(1f, 1.25f, 1.575f, 2f, 4f)
        choose(R.string.demo_set_zoom, values.map { "${it}×" }, values.indexOf(zoom).coerceAtLeast(0)) { index ->
            host.updateMediaSettings(settings.withZoom(target, values[index]))
        }
    }

    private fun choose(title: Int, items: List<String>, initial: Int, apply: (Int) -> Unit) {
        val context = requireContext()
        val adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_single_choice, items) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                return super.getView(position, convertView, parent).apply {
                    setBackgroundColor(Color.BLACK)
                    (this as CheckedTextView).apply {
                        setTextColor(Color.WHITE)
                        checkMarkTintList = ColorStateList.valueOf(Color.WHITE)
                        minHeight = (48 * resources.displayMetrics.density).toInt()
                    }
                }
            }
        }
        val dialogTitle = TextView(context).apply {
            setText(title)
            setTextColor(Color.WHITE)
            textSize = 17f
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding / 2)
        }
        val dialog = AlertDialog.Builder(context).setCustomTitle(dialogTitle)
            .setSingleChoiceItems(adapter, initial) { choice, index ->
                choice.dismiss()
                apply(index)
            }
            .setNegativeButton(android.R.string.cancel, null).create()
        choiceDialog = dialog
        var index = initial
        fun selectChoice(next: Int) {
            index = next
            val cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            cancel.isSelected = index == items.size
            if (index < items.size) {
                dialog.listView.requestFocusFromTouch()
                dialog.listView.setItemChecked(index, true)
                dialog.listView.setSelection(index)
            } else {
                dialog.listView.clearChoices()
                dialog.listView.requestLayout()
                cancel.requestFocusFromTouch()
            }
        }
        bindDialogKeys(dialog) { event ->
            when (event) {
                GlassKeyEvent.KEYCODE_FRONT -> selectChoice((index + 1) % (items.size + 1))
                GlassKeyEvent.KEYCODE_BEHIND -> selectChoice((index - 1 + items.size + 1) % (items.size + 1))
                GlassKeyEvent.KEYCODE_CLICK -> {
                    if (index < items.size) {
                        val chosen = index
                        dialog.dismiss()
                        apply(chosen)
                    } else dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                }
                GlassKeyEvent.KEYCODE_BACK, GlassKeyEvent.KEYCODE_DOUBLE_CLICK -> dialog.dismiss()
                else -> return@bindDialogKeys false
            }
            true
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        dialog.listView.setBackgroundColor(Color.BLACK)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).apply {
            setTextColor(Color.WHITE)
            setOnFocusChangeListener { _, focused -> if (focused) selectChoice(items.size) }
        }
        selectChoice(initial)
    }

    private fun bindDialogKeys(dialog: AlertDialog, handler: (Int) -> Boolean) {
        dialogKeys = handler
        val input = GlassKeyInput(handler)
        dialog.setOnKeyListener { _, _, event -> input.dispatch(event) }
        dialog.setOnDismissListener { if (choiceDialog === dialog) { choiceDialog = null; dialogKeys = null } }
    }

    internal fun onGlassKeyEvent(event: Int): Boolean {
        // 选项弹窗独占输入，不允许触发后面的设置按钮。
        // The choice dialog owns input; never activate the settings button behind it.
        if (choiceDialog?.isShowing == true) return dialogKeys?.invoke(event) ?: false
        when (event) {
            GlassKeyEvent.KEYCODE_FRONT -> select((selected + 1) % buttons.size)
            GlassKeyEvent.KEYCODE_BEHIND -> select((selected - 1 + buttons.size) % buttons.size)
            GlassKeyEvent.KEYCODE_CLICK -> buttons[selected].performClick()
            GlassKeyEvent.KEYCODE_BACK, GlassKeyEvent.KEYCODE_DOUBLE_CLICK -> host.closeMediaSettings()
            else -> return false
        }
        return true
    }

    private fun select(index: Int) {
        selected = index
        buttons.forEachIndexed { position, button ->
            val focused = position == index
            button.isSelected = focused
            button.setBackgroundResource(if (focused) R.drawable.round_select_bg else R.drawable.round_unselect_bg)
            button.setTextColor(ContextCompat.getColor(requireContext(), if (focused) R.color.green else R.color.green_70))
        }
        buttons.getOrNull(index)?.apply {
            requestFocusFromTouch()
            post {
                if (isShown && isSelected) requestRectangleOnScreen(Rect(0, 0, width, height), true)
            }
        }
    }

    override fun onDestroyView() {
        choiceDialog?.dismiss(); choiceDialog = null; dialogKeys = null
        loadingSizes = false; buttons = emptyList(); binding = null
        super.onDestroyView()
    }
}
