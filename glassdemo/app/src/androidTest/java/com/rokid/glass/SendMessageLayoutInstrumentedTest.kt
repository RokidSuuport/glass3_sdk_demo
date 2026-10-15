package com.rokid.glass

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.LocaleList
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rokid.glesse.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * 只测量实际控件，不触发语音、网络、相机或消息操作。
 * Measure real controls without invoking speech, network, camera or message operations.
 */
@RunWith(AndroidJUnit4::class)
class SendMessageLayoutInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val buttonIds = listOf(
        R.id.btnTtsQueueMode, R.id.btnOnlineAsr, R.id.btnOnlineTts, R.id.btnStopOnlineTts,
        R.id.btnOfflineTts, R.id.btnStopOfflineTts, R.id.wfSendTextBtn,
        R.id.wfSendFileBtn, R.id.btSendTextBtn, R.id.btSendFileBtn,
        R.id.btSendAudioStream, R.id.btStopSendAudioStream,
        R.id.btSendVideo, R.id.btStopSendVideo
    )

    @Test
    fun messageButtonsRemainReadableAndReachable() {
        val failures = mutableListOf<String>()
        val diagnostics = mutableListOf<String>()
        onMain {
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "send-message-layout-regression")
                .apply { mkdirs() }
            for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH)) {
                for (scale in listOf(1f, 1.3f)) {
                    val root = inflateLayout(locale, scale)
                    val controls: ScrollView? = root.findViewById(R.id.controlsScrollView)
                    var idleGeometry: List<Int>? = null
                    for ((state, asrText) in listOf(
                        "idle" to R.string.demo_online_asr_2,
                        "listening" to R.string.demo_stop_asr,
                        "retry-stop" to R.string.demo_retry_stopping_asr,
                        "idle-restored" to R.string.demo_online_asr_2
                    )) {
                        val name = "${locale.toLanguageTag()}-$scale-$state"
                        controls?.scrollTo(0, 0)
                        root.findViewById<TextView>(R.id.btnOnlineAsr).setText(asrText)
                        val flush = state == "listening" || state == "retry-stop"
                        root.findViewById<TextView>(R.id.btnTtsQueueMode).setText(
                            if (flush) R.string.message_tts_mode_flush else R.string.message_tts_mode_add
                        )
                        measure(root)
                        saveScreenshot(root, File(directory, "$name-top.png"))
                        verify(root, name, failures, diagnostics)
                        val geometry = buttonIds.flatMap { id ->
                            val button = root.findViewById<TextView>(id)
                            val bounds = bounds(button, root)
                            listOf(bounds.left.toInt(), bounds.top.toInt(), button.width, button.height)
                        } + root.findViewById<View>(R.id.llMain).height
                        if (state == "idle") idleGeometry = geometry
                        if (state == "idle-restored") {
                            check(failures, "$name: restoring ASR/mode labels did not restore the original control geometry") {
                                geometry == idleGeometry
                            }
                        }
                        if (controls != null) {
                            val viewport = bounds(controls, root).apply {
                                left += controls.paddingLeft
                                top += controls.paddingTop
                                right -= controls.paddingRight
                                bottom -= controls.paddingBottom
                            }
                            for (id in buttonIds.takeLast(1)) {
                                val button = root.findViewById<TextView>(id)
                                button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true)
                                check(failures, "$name: Final control is clipped after scrolling: ${button.text} ${bounds(button, root)} outside $viewport") {
                                    contains(viewport, bounds(button, root))
                                }
                            }
                            diagnostics += "$name: scrollY=${controls.scrollY}, viewport=$viewport"
                        }
                        saveScreenshot(root, File(directory, "$name-bottom.png"))
                    }
                }
            }
            File(directory, "diagnostics.txt").writeText((diagnostics + failures.map { "FAIL: $it" }).joinToString("\n"))
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun inflateLayout(locale: Locale, scale: Float): ViewGroup {
        val configuration = Configuration(instrumentation.targetContext.resources.configuration).apply {
            setLocales(LocaleList(locale))
            fontScale = scale
            densityDpi = 240
            orientation = Configuration.ORIENTATION_PORTRAIT
            screenWidthDp = 320
            screenHeightDp = 426
            smallestScreenWidthDp = 320
        }
        val context = ContextThemeWrapper(
            instrumentation.targetContext.createConfigurationContext(configuration), R.style.Theme_Glessedemo
        )
        val root = LayoutInflater.from(context).inflate(R.layout.activity_sendmessage, null) as ViewGroup
        root.findViewById<TextView>(R.id.tvLog).text = "Layout verification log\nSecond log line"
        return root
    }

    private fun measure(root: View) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 480, 640)
    }

    private fun verify(root: ViewGroup, name: String, failures: MutableList<String>, diagnostics: MutableList<String>) {
        val buttons = buttonIds.map { root.findViewById<TextView>(it) }
        val mainRows = root.findViewById<ViewGroup>(R.id.llMain)
        val controls: ScrollView? = root.findViewById(R.id.controlsScrollView)
        val content = requireNotNull(controls).getChildAt(0) as ViewGroup
        val log = root.findViewById<ScrollView>(R.id.scrollView)
        val rows = buttons.groupBy { it.parent }.values.toList()
        val speechGroup = root.findViewById<ViewGroup>(R.id.speechControlsGroup)
        val mode = root.findViewById<TextView>(R.id.btnTtsQueueMode)
        val modeRow = root.findViewById<ViewGroup>(R.id.ttsModeRow)
        val onlineRow = root.findViewById<ViewGroup>(R.id.onlineTtsRow)
        val offlineRow = root.findViewById<ViewGroup>(R.id.offlineTtsRow)
        val asr = root.findViewById<TextView>(R.id.btnOnlineAsr)
        check(failures, "$name: mode and ASR must share the first row") {
            content.getChildAt(0) === speechGroup && speechGroup.getChildAt(0) === modeRow &&
                modeRow.childCount == 2 && modeRow.getChildAt(0) === mode && modeRow.getChildAt(1) === asr
        }
        check(failures, "$name: speech controls must have no enclosing frame") {
            speechGroup.background == null && contains(bounds(speechGroup, root), bounds(modeRow, root)) &&
                speechGroup.childCount == 3 &&
                listOf(onlineRow, offlineRow).all { contains(bounds(speechGroup, root), bounds(it, root)) } &&
                onlineRow.childCount == 2 && offlineRow.childCount == 2
        }
        check(failures, "$name: each TTS play button must be immediately followed by its stop button") {
            onlineRow.getChildAt(0).id == R.id.btnOnlineTts && onlineRow.getChildAt(1).id == R.id.btnStopOnlineTts &&
                offlineRow.getChildAt(0).id == R.id.btnOfflineTts && offlineRow.getChildAt(1).id == R.id.btnStopOfflineTts
        }
        check(failures, "$name: expected mode, speech and message rows") {
            rows.map { it.size } == listOf(2, 2, 2, 4, 2, 2)
        }
        check(failures, "$name: controls need a scrollable viewport") { controls != null }
        if (controls != null) {
            check(failures, "$name: controls have no visible area") { controls.width > 0 && controls.height > 0 }
            check(failures, "$name: controls overlap the log viewport") {
                bounds(controls, root).bottom <= bounds(log, root).top
            }
            check(failures, "$name: controls extend beyond the screen") {
                contains(RectF(0f, 0f, 480f, 640f), bounds(controls, root))
            }
        }
        check(failures, "$name: log viewport must remain visible inside the screen") {
            log.height >= root.height / 2 &&
                contains(RectF(0f, 0f, 480f, 640f), bounds(log, root))
        }
        val horizontalGaps = mutableListOf<Int>()
        rows.forEachIndexed { index, rowButtons ->
            val tops = rowButtons.map { it.top }
            val bottoms = rowButtons.map { it.bottom }
            val widths = rowButtons.map { it.width }
            check(failures, "$name: row ${index + 1} tops differ: $tops") { tops.max() - tops.min() <= 1 }
            check(failures, "$name: row ${index + 1} bottoms differ: $bottoms") { bottoms.max() - bottoms.min() <= 1 }
            check(failures, "$name: row ${index + 1} widths differ: $widths") { widths.min() > 0 && widths.max() - widths.min() <= 1 }
            rowButtons.zipWithNext().forEach { (left, right) -> horizontalGaps += right.left - left.right }
        }
        check(failures, "$name: horizontal gaps are irregular: $horizontalGaps") {
            horizontalGaps.min() > 0 && horizontalGaps.max() - horizontalGaps.min() <= 1
        }
        val ordinaryRowBounds = rows.filter { it.first().parent.parent === mainRows }
            .map { bounds(it.first().parent as View, root) }
        val verticalGaps = ordinaryRowBounds.zipWithNext().map { (above, below) -> below.top - above.bottom }
        check(failures, "$name: message row vertical gaps are irregular: $verticalGaps") {
            verticalGaps.min() > 0 && verticalGaps.max() - verticalGaps.min() <= 1f
        }
        buttons.forEach { button ->
            val buttonBounds = bounds(button, root)
            val row = bounds(button.parent as View, root)
            val label = "$name: ${button.text}"
            diagnostics += "$label: button=$buttonBounds row=$row lines=${button.lineCount}"
            check(failures, "$label: button border extends beyond its row: $buttonBounds outside $row") {
                contains(row, buttonBounds)
            }
            check(failures, "$label: row extends beyond scrollable content") { contains(bounds(content, root), row) }
            verifyText(button, label, failures)
        }
        for (left in buttons.indices) for (right in left + 1 until buttons.size) {
            check(failures, "$name: ${buttons[left].text} overlaps ${buttons[right].text}") {
                !RectF.intersects(bounds(buttons[left], root), bounds(buttons[right], root))
            }
        }
    }

    private fun verifyText(text: TextView, label: String, failures: MutableList<String>) {
        val layout = text.layout
        if (layout == null || layout.lineCount == 0) {
            failures += "$label: text was not laid out"
            return
        }
        check(failures, "$label: multiline text is vertically clipped") {
            layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom
        }
        check(failures, "$label: missing trailing text") { layout.getLineEnd(layout.lineCount - 1) == text.text.length }
        for (line in 0 until layout.lineCount) {
            check(failures, "$label: line $line is ellipsized") { layout.getEllipsisCount(line) == 0 }
            check(failures, "$label: line $line extends beyond the available width") {
                layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.width + 1f
            }
        }
    }

    private fun bounds(view: View, root: View): RectF {
        val rect = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
        var current = view
        while (current !== root) {
            val parent = current.parent as View
            rect.offset(current.left + current.translationX - parent.scrollX, current.top + current.translationY - parent.scrollY)
            current = parent
        }
        return rect
    }

    private fun contains(parent: RectF, child: RectF): Boolean =
        child.left >= parent.left - 1f && child.top >= parent.top - 1f &&
            child.right <= parent.right + 1f && child.bottom <= parent.bottom + 1f

    private fun check(failures: MutableList<String>, message: String, condition: () -> Boolean) {
        if (!condition()) failures += message
    }

    private fun saveScreenshot(root: View, file: File) {
        val bitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
}
