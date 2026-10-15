package com.rokid.glass

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.LocaleList
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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

/** Render both pages on glasses dimensions; no camera, microphone or transmission operations. */
@RunWith(AndroidJUnit4::class)
class MediaLayoutInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun separatePagesRemainReadableInBothLanguages() {
        val failures = mutableListOf<String>()
        onMain {
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "media-settings-layout").apply { mkdirs() }
            for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH)) for (scale in listOf(1f, 1.3f)) {
                val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
                    setLocales(LocaleList(locale)); fontScale = scale; densityDpi = 240
                }
                val context = ContextThemeWrapper(instrumentation.targetContext.createConfigurationContext(config), R.style.Theme_Glessedemo)
                for ((name, layout) in listOf("capture" to R.layout.activity_test_media, "photo-settings" to R.layout.fragment_media_settings, "video-settings" to R.layout.fragment_media_settings)) {
                    val root = LayoutInflater.from(context).inflate(layout, null) as ViewGroup
                    if (name != "capture") {
                        val photo = name == "photo-settings"
                        root.findViewById<TextView>(R.id.tvMediaSettingsTitle).setText(if (photo) R.string.media_photo_settings else R.string.media_video_settings)
                        root.findViewById<TextView>(R.id.tvMediaSettingsHint).setText(if (photo) R.string.media_photo_settings_hint else R.string.media_video_settings_hint)
                        root.findViewById<View>(R.id.mediaPhotoOptionsRow).visibility = if (photo) View.VISIBLE else View.GONE
                        root.findViewById<View>(R.id.mediaVideoOptionsRow).visibility = if (photo) View.GONE else View.VISIBLE
                        root.findViewById<View>(R.id.btnMediaFollowShared).visibility = if (photo) View.GONE else View.VISIBLE
                        root.findViewById<TextView>(R.id.btnMediaSize).text = context.getString(R.string.media_resolution, if (photo) "4032×3024" else "1920×1080")
                        root.findViewById<TextView>(R.id.btnMediaOptionsZoom).text = context.getString(R.string.media_options_zoom, "1.575")
                        root.findViewById<TextView>(R.id.btnSetZoom).text = context.getString(R.string.media_zoom_apply)
                    }
                    root.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, 480, 640)
                    val buttons = descendants(root).filterIsInstance<Button>().filter { visibleWithin(it, root) }
                    if (name == "capture") check(failures, "Main page must contain six actions") { buttons.size == 6 }
                    buttons.forEach { button ->
                        verifyText(button, "$locale/$scale/$name: ${button.text}", failures)
                        check(failures, "Button extends beyond its parent") { contains(bounds(button.parent as View, root), bounds(button, root)) }
                    }
                    val scroll = if (root is ScrollView) root else root.findViewById<ScrollView>(R.id.controlsScrollView)
                    saveScreenshot(root, File(directory, "$locale-$scale-$name-top.png"))
                    scroll.scrollTo(0, scroll.getChildAt(0).height)
                    saveScreenshot(root, File(directory, "$locale-$scale-$name-bottom.png"))
                    val last = bounds(buttons.last(), root)
                    check(failures, "Last control must be reachable by scrolling") { contains(bounds(scroll, root), last) }
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
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
        check(failures, "$label: missing trailing text") {
            layout.getLineEnd(layout.lineCount - 1) == text.text.length
        }
        for (line in 0 until layout.lineCount) {
            check(failures, "$label: line $line is ellipsized") { layout.getEllipsisCount(line) == 0 }
            check(failures, "$label: line $line extends outside the button width") {
                layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.width + 1f
            }
        }
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }

    private fun visibleWithin(view: View, root: View): Boolean {
        var current = view
        while (true) {
            if (current.visibility != View.VISIBLE) return false
            if (current === root) return true
            current = current.parent as? View ?: return false
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
