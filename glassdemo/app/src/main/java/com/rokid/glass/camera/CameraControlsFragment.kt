package com.rokid.glass.camera

import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.rokid.glass.base.GlassKeyEvent
import com.rokid.security.glass3.open.sdk.uitls.log.L
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Full-screen camera preview with an overlay panel controlled by the glasses temple gestures. */
abstract class CameraControlsFragment : Fragment(), CameraShareGestureHandler {
    protected lateinit var content: LinearLayout
    protected lateinit var previewHost: FrameLayout
    private lateinit var panel: ScrollView
    private lateinit var status: TextView
    private lateinit var panelStatus: TextView
    private lateinit var open: Button
    private lateinit var returnToPreview: Button
    private lateinit var previewControls: LinearLayout
    private lateinit var queryOverlay: LinearLayout
    private lateinit var queryScroll: ScrollView
    private lateinit var queryResult: TextView
    private lateinit var queryTitle: TextView
    private val buttons = mutableListOf<Button>()
    private val previewButtons = mutableListOf<Button>()
    private val runningSettingsButtons = mutableListOf<Button>()
    private var selected = 0
    private var previewSelected = 0
    private var previewActionsEnabled = true
    private var previewLoading = false
    private var queryGeneration = 0L
    private val worker = Executors.newSingleThreadExecutor()
    protected val controlDispatcher = worker.asCoroutineDispatcher()
    @Volatile private var epoch = 0
    @Volatile private var active = false
    @Volatile private var viewEpoch = 0
    @Volatile private var viewAvailable = false
    protected open val previewRequiresStart = false
    protected open val previewStartsAutomatically = false
    protected open val previewSettingsText get() = label("设置", "Settings")
    protected fun label(zh: String, en: String) = if (resources.configuration.locales[0].language == "zh") zh else en

    final override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        buttons.clear(); previewButtons.clear(); runningSettingsButtons.clear(); selected = 0; previewSelected = 0
        previewActionsEnabled = !previewRequiresStart
        previewLoading = previewStartsAutomatically
        viewEpoch++; viewAvailable = true
        val context = requireContext()
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        previewHost = FrameLayout(context)
        root.addView(previewHost, FrameLayout.LayoutParams(-1, -1))
        status = TextView(context).apply { setTextColor(Color.WHITE); textSize = 13f; maxLines = 8; setBackgroundColor(0x99000000.toInt()); setPadding(8, 8, 8, 8) }
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        previewControls = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "camera_preview_actions"
            setBackgroundColor(0xCC000000.toInt())
        }
        open = Button(context).apply {
            tag = "camera_preview_configure"
            text = previewSettingsText
            isAllCaps = false
            setOnClickListener { setPanelVisible(true) }
        }
        previewButtons.add(open)
        root.addView(previewControls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        status.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPreviewBetweenControls() }
        previewControls.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPreviewBetweenControls() }
        content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        panel = ScrollView(context).apply { tag = "camera_settings_panel"; setBackgroundColor(Color.BLACK); addView(content) }
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
        panelStatus = TextView(context).apply { setTextColor(Color.WHITE); textSize = 12f; maxLines = 7; visibility = View.GONE; setPadding(4, 4, 4, 4) }
        content.addView(panelStatus, LinearLayout.LayoutParams(-1, -2))
        queryOverlay = LinearLayout(context).apply {
            tag = "camera_query_overlay"
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = true
            isFocusableInTouchMode = true
            // The focused overlay also handles a native Back key before Activity back navigation.
            setOnKeyListener { _, code, event ->
                if (code == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) dismissQueryResult()
                    true
                } else false
            }
        }
        queryTitle = TextView(context).apply { setTextColor(Color.WHITE); textSize = 17f }
        queryOverlay.addView(queryTitle, LinearLayout.LayoutParams(-1, -2))
        queryResult = TextView(context).apply {
            tag = "camera_query_result"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, dp(10), 0, dp(10))
        }
        queryScroll = ScrollView(context).apply { addView(queryResult) }
        queryOverlay.addView(queryScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        queryOverlay.addView(Button(context).apply {
            tag = "camera_query_close"
            text = label("返回（滑动查看，单击返回）", "Back (swipe to read, tap to return)")
            textSize = 13f
            isAllCaps = false
            setOnClickListener { dismissQueryResult() }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(queryOverlay, FrameLayout.LayoutParams(-1, -1))
        buildControls()
        returnToPreview = action(if (previewRequiresStart) label("返回预览", "Return to preview") else label("隐藏配置", "Hide settings")) { setPanelVisible(false) }
        returnToPreview.tag = "camera_preview_return"
        finishSettings(returnToPreview)
        updateReturnToPreview()
        rebuildPreviewControls()
        setPanelVisible(!previewStartsAutomatically)
        return root
    }

    protected abstract fun buildControls()
    protected open fun finishSettings(returnButton: Button) {}

    protected fun note(text: String) { content.addView(TextView(requireContext()).apply { this.text = text; setTextColor(Color.WHITE); textSize = 12f; setPadding(4, 4, 4, 4) }) }
    protected fun action(text: String, block: () -> Unit): Button = Button(requireContext()).also { button ->
        button.text = text; button.isAllCaps = false
        button.textSize = 13f; button.minHeight = dp(32); button.minimumHeight = dp(32)
        button.setPadding(dp(4), dp(3), dp(4), dp(3))
        button.setOnClickListener { selected = buttons.indexOf(button); highlight(); runCatching(block).onFailure { showStatus("${it.javaClass.simpleName}: ${it.message.orEmpty().take(120)}") } }
        buttons.add(button); content.addView(button, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(2), 0, dp(2)) }); highlight()
    }
    protected fun runningAction(text: String, block: () -> Unit): Button = action(text, block).also {
        it.isEnabled = previewActionsEnabled
        runningSettingsButtons.add(it)
    }
    /** Keep pairs equal within a row while allowing translated labels to grow naturally. */
    protected fun settingRow(vararg views: View): LinearLayout {
        val index = views.minOf { content.indexOfChild(it) }
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }
        views.forEach { child ->
            content.removeView(child)
            row.addView(child, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
        }
        content.addView(row, index, LinearLayout.LayoutParams(-1, -2))
        return row
    }
    /** Controls for an already running preview; kept separate from settings and their navigation. */
    protected fun previewAction(text: String, block: () -> Unit): Button = Button(requireContext()).also { button ->
        button.text = text
        button.isAllCaps = false
        button.visibility = if (previewActionsEnabled) View.VISIBLE else View.GONE
        button.isEnabled = previewActionsEnabled
        button.setOnClickListener {
            previewSelected = previewButtons.indexOf(button)
            highlight()
            runCatching(block).onFailure { showStatus("${it.javaClass.simpleName}: ${it.message.orEmpty().take(120)}") }
        }
        previewButtons.add(button)
    }
    protected fun setPreviewActionsEnabled(enabled: Boolean) { ui {
        previewActionsEnabled = enabled
        runningSettingsButtons.forEach { it.isEnabled = enabled }
        previewButtons.drop(1).forEach { button ->
            button.visibility = if (enabled) View.VISIBLE else View.GONE
            button.isEnabled = enabled
        }
        updateReturnToPreview()
        rebuildPreviewControls()
        if (previewRequiresStart && !enabled && !previewLoading) setPanelVisible(true)
        highlight()
    } }
    /** A pending automatic start can show the preview while runtime actions remain unavailable. */
    protected fun setPreviewLoading(loading: Boolean) { ui {
        previewLoading = loading
        updateReturnToPreview()
    } }
    private fun updateReturnToPreview() {
        if (::returnToPreview.isInitialized) {
            returnToPreview.visibility = if (previewRequiresStart && !previewActionsEnabled && !previewLoading)
                View.GONE else View.VISIBLE
        }
    }
    /** Start on the UI thread. The returned updater is safe for worker results and stale replies. */
    protected fun beginQueryResult(title: String): (String) -> Unit {
        val queryToken = ++queryGeneration
        val lifecycleToken = epoch
        val viewToken = viewEpoch
        queryTitle.text = title
        queryResult.text = label("查询中…", "Querying…")
        queryScroll.scrollTo(0, 0)
        queryOverlay.visibility = View.VISIBLE
        queryOverlay.requestFocus()
        return { result -> ui {
            if (active && lifecycleToken == epoch && viewToken == viewEpoch &&
                queryToken == queryGeneration && queryOverlay.visibility == View.VISIBLE) {
                queryResult.text = result
                queryScroll.scrollTo(0, 0)
            }
        } }
    }
    private fun dismissQueryResult() {
        queryGeneration++
        if (::queryOverlay.isInitialized) {
            queryOverlay.clearFocus()
            queryOverlay.visibility = View.GONE
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    /** Keep the full camera image (including corner watermarks) clear of runtime controls. */
    private fun fitPreviewBetweenControls() {
        val layout = previewHost.layoutParams as FrameLayout.LayoutParams
        val top = if (status.visibility == View.VISIBLE) status.height else 0
        val bottom = if (previewControls.visibility == View.VISIBLE) previewControls.height else 0
        if (layout.topMargin != top || layout.bottomMargin != bottom) {
            layout.topMargin = top
            layout.bottomMargin = bottom
            previewHost.layoutParams = layout
        }
    }
    private fun rebuildPreviewControls() {
        previewControls.removeAllViews()
        val visible = previewButtons.filter { it.visibility == View.VISIBLE }
        visible.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        if (previewButtons.size == 1) {
            previewControls.addView(open, LinearLayout.LayoutParams(-1, -2))
        } else {
            visible.chunked(2).forEach { pair ->
                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    isBaselineAligned = false
                }
                pair.forEach { button ->
                    button.textSize = 13f
                    button.minHeight = dp(32)
                    button.minimumHeight = dp(32)
                    button.setPadding(dp(4), dp(4), dp(4), dp(4))
                    row.addView(button, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) })
                }
                previewControls.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        }
        previewSelected = previewButtons.indexOfFirst { it.visibility == View.VISIBLE && it.isEnabled }.coerceAtLeast(0)
        highlight()
    }
    protected fun ui(block: () -> Unit) {
        val token = viewEpoch
        activity?.runOnUiThread { if (viewAvailable && token == viewEpoch) block() }
    }
    protected fun showStatus(text: String, showInSettings: Boolean = true) {
        L.i("CameraDemo", text.take(900))
        val token = epoch
        ui { if (active && token == epoch) {
            status.text = text.take(1000)
            panelStatus.text = text.take(1000)
            panelStatus.visibility = if (showInSettings && text.isNotBlank()) View.VISIBLE else View.GONE
        } }
    }
    protected fun setPanelVisible(visible: Boolean) { ui {
        if (!visible && previewRequiresStart && !previewActionsEnabled && !previewLoading) return@ui
        panel.visibility = if (visible) View.VISIBLE else View.GONE
        previewControls.visibility = if (visible) View.GONE else View.VISIBLE
        status.visibility = if (visible) View.GONE else View.VISIBLE
        fitPreviewBetweenControls()
        if (visible) { selected = 0; highlight() }
    } }
    protected fun io(block: () -> Unit) {
        if (!active) return
        val token = epoch
        try {
            worker.execute { if (active && token == epoch) runCatching(block).onFailure { showStatus("${it.javaClass.simpleName}: ${it.message.orEmpty().take(120)}") } }
        } catch (_: RejectedExecutionException) { /* Fragment has been destroyed. */ }
    }
    protected fun cleanup(block: () -> Unit) {
        val task = Runnable { runCatching(block).onFailure { L.w("CameraDemo", "cleanup failed: ${it.javaClass.simpleName}") } }
        try { worker.execute(task) } catch (_: RejectedExecutionException) { Thread(task, "camera-final-release").start() }
    }
    override fun onStart() { super.onStart(); active = true }
    override fun onStop() { active = false; epoch++; dismissQueryResult(); super.onStop() }
    override fun onDestroyView() { dismissQueryResult(); viewAvailable = false; viewEpoch++; super.onDestroyView() }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
    override fun handleGlassKeyEvent(keyEvent: Int): Boolean {
        if (::queryOverlay.isInitialized && queryOverlay.visibility == View.VISIBLE) {
            when (keyEvent) {
                GlassKeyEvent.KEYCODE_FRONT -> queryScroll.scrollBy(0, (queryScroll.height * 0.6f).toInt().coerceAtLeast(dp(48)))
                GlassKeyEvent.KEYCODE_BEHIND -> queryScroll.scrollBy(0, -(queryScroll.height * 0.6f).toInt().coerceAtLeast(dp(48)))
                GlassKeyEvent.KEYCODE_CLICK, GlassKeyEvent.KEYCODE_BACK -> dismissQueryResult()
                else -> return false
            }
            return true
        }
        if (panel.visibility != View.VISIBLE) {
            if (previewButtons.size == 1) {
                if (keyEvent == GlassKeyEvent.KEYCODE_CLICK) { setPanelVisible(true); return true }
                return false
            }
            val available = previewButtons.indices.filter { previewButtons[it].visibility == View.VISIBLE && previewButtons[it].isEnabled }
            if (available.isEmpty()) return false
            if (previewSelected !in available) previewSelected = available.first()
            when (keyEvent) {
                GlassKeyEvent.KEYCODE_FRONT -> previewSelected = available[(available.indexOf(previewSelected) + 1) % available.size]
                GlassKeyEvent.KEYCODE_BEHIND -> previewSelected = available[(available.indexOf(previewSelected) - 1 + available.size) % available.size]
                GlassKeyEvent.KEYCODE_CLICK -> { previewButtons[previewSelected].performClick(); return true }
                else -> return false
            }
            highlight()
            return true
        }
        val available = buttons.indices.filter { buttons[it].isShown && buttons[it].isEnabled }
        if (available.isEmpty()) return false
        if (selected !in available) selected = available.first()
        when (keyEvent) {
            GlassKeyEvent.KEYCODE_FRONT -> selected = available[(available.indexOf(selected) + 1) % available.size]
            GlassKeyEvent.KEYCODE_BEHIND -> selected = available[(available.indexOf(selected) - 1 + available.size) % available.size]
            GlassKeyEvent.KEYCODE_CLICK -> { buttons[selected].performClick(); return true }
            else -> return false
        }
        highlight()
        buttons[selected].let { it.requestRectangleOnScreen(Rect(0, 0, it.width, it.height), true) }
        return true
    }
    private fun highlight() {
        fun paint(list: List<Button>, selection: Int) { list.forEachIndexed { index, button ->
            val focused = index == selection && button.isEnabled
            button.setTextColor(if (focused) Color.GREEN else Color.WHITE)
            button.setBackgroundColor(if (focused) 0xff194119.toInt() else 0xff252525.toInt())
        } }
        paint(buttons, selected)
        paint(previewButtons, previewSelected)
    }
}
