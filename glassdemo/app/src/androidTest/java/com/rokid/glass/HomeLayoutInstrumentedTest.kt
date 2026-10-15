package com.rokid.glass

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.LocaleList
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rokid.glass.adapter.HomeAdapter
import com.rokid.glass.annotation.MenuConfigType
import com.rokid.glass.component.MenuItem
import com.rokid.glesse.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/** Measures the production views without starting HomeActivity or connecting to the glasses SDK. */
@RunWith(AndroidJUnit4::class)
class HomeLayoutInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun homeFitsBothLanguagesAndKeepsItsGeometryWhenFocusMoves() {
        val fixtures = mutableListOf<Fixture>()
        onMain {
            for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH)) {
                for (fontScale in listOf(1f, 1.3f)) {
                    for (connected in listOf(false, true)) {
                        for (page in 0..2) {
                            fixtures += createFixture(locale, fontScale, connected, page)
                        }
                    }
                }
            }
        }
        try {
            for (focus in 0..2) {
                onMain {
                    fixtures.forEach { fixture ->
                        if (focus > 0) fixture.adapter.toNextItem()
                        measure(fixture.root)
                    }
                }
                // HomeAdapter's real focus transition lasts 300 ms; never block the UI thread.
                SystemClock.sleep(400)
                instrumentation.waitForIdleSync()
                onMain {
                    fixtures.forEach { fixture ->
                        saveScreenshot(fixture, focus)
                        verify(fixture, focus)
                    }
                }
            }
        } finally {
            onMain {
                fixtures.forEach { fixture ->
                    for (i in 0 until fixture.recycler.childCount) {
                        fixture.recycler.getChildAt(i).findViewById<View>(R.id.main_view)
                            .animate().cancel()
                    }
                    fixture.recycler.adapter = null
                }
            }
        }
    }

    private fun createFixture(locale: Locale, scale: Float, connected: Boolean, page: Int): Fixture {
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
            instrumentation.targetContext.createConfigurationContext(configuration),
            R.style.Theme_Glessedemo
        )
        val root = LayoutInflater.from(context).inflate(R.layout.activity_home, null) as ViewGroup
        root.findViewById<TextView>(R.id.tvBluetoothStatus).setText(
            if (connected) R.string.s_bt_connected else R.string.s_bt_idle
        )
        root.findViewById<TextView>(R.id.ringStatus).setText(
            if (connected) R.string.s_ring_connected else R.string.s_ring_idle
        )
        root.findViewById<TextView>(R.id.tvp2pStatus).setText(
            if (connected) R.string.s_p2p_connected else R.string.s_p2p_idle
        )
        val recycler = root.findViewById<RecyclerView>(R.id.work_home_recycler_launch)
        recycler.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        val adapter = HomeAdapter(context)
        val pageItems = menuItems(context).drop(page * 3).take(3)
        pageItems[0].isChecked = true
        adapter.setIconInfoList(pageItems)
        recycler.adapter = adapter
        measure(root)
        return Fixture("${locale.toLanguageTag()}-${scale}-${connected}-page${page + 1}", root, recycler, adapter)
    }

    private fun verify(fixture: Fixture, focus: Int) {
        val root = fixture.root
        val tag = "${fixture.name}, focus=$focus"
        val statuses = listOf(R.id.tvBluetoothStatus, R.id.ringStatus, R.id.tvp2pStatus)
            .map { root.findViewById<TextView>(it) }
        val statusBar = bounds(root.findViewById(R.id.llStatusBar), root)
        statuses.forEach { text ->
            assertTrue("$tag: status has no width", text.width > 0)
            assertInside("$tag: status", bounds(text, root), statusBar)
            assertTextFits("$tag: status ${text.text}", text)
        }
        statuses.zipWithNext().forEach { (left, right) ->
            assertTrue("$tag: statuses overlap", bounds(left, root).right <= bounds(right, root).left)
        }
        val title = root.findViewById<TextView>(R.id.tvEnterprise)
        val titleBounds = bounds(title, root)
        val menuBounds = bounds(fixture.recycler, root)
        assertTrue("$tag: title overlaps status bar", titleBounds.top >= statusBar.bottom)
        assertTrue("$tag: title overlaps menu", titleBounds.bottom <= menuBounds.top)
        assertInside("$tag: menu", menuBounds, RectF(0f, 0f, 480f, 640f))
        assertTextFits("$tag: title", title)
        assertEquals("$tag: all menu entries must be visible", fixture.adapter.itemCount, fixture.recycler.childCount)

        val geometry = mutableListOf(title.top, title.height, fixture.recycler.top, fixture.recycler.width, fixture.recycler.height)
        for (position in 0 until fixture.adapter.itemCount) {
            val holder = fixture.recycler.findViewHolderForAdapterPosition(position) as HomeAdapter.ViewHolder
            val item = holder.itemView as ViewGroup
            val card = holder.layoutItem
            val label = holder.iconNameTv
            val underline = holder.iconSelectBar
            assertFalse("$tag: lifted card is clipped by padding", item.clipToPadding)
            assertEquals("$tag: wrong focus lift", if (position == focus % fixture.adapter.itemCount) -18f else 0f, card.translationY, 0.1f)
            assertInside("$tag: lifted card $position", bounds(card, root), bounds(item, root))
            assertInside("$tag: card $position", bounds(card, root), menuBounds)
            assertInside("$tag: label $position", bounds(label, root), bounds(card, root))
            assertTextFits("$tag: ${label.text}", label)
            assertTrue("$tag: underline overlaps label", bounds(label, root).bottom <= bounds(underline, root).top)
            assertEquals("$tag: underline must retain its space", if (position == focus % fixture.adapter.itemCount) View.VISIBLE else View.INVISIBLE, underline.visibility)
            geometry += listOf(item.top, item.width, item.height, card.height, label.height)
        }
        if (fixture.geometry == null) fixture.geometry = geometry
        else assertEquals("$tag: selecting another card changed layout geometry", fixture.geometry, geometry)
    }

    private fun assertTextFits(message: String, text: TextView) {
        val layout = text.layout
        assertNotNull("$message: no text layout", layout)
        assertTrue("$message: text is vertically clipped", layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
        assertEquals("$message: missing trailing text", text.text.length, layout.getLineEnd(layout.lineCount - 1))
        for (line in 0 until layout.lineCount) {
            assertEquals("$message: ellipsized line $line", 0, layout.getEllipsisCount(line))
            assertTrue("$message: line $line extends beyond its right edge", layout.getLineRight(line) <= layout.width + 1f)
            assertTrue("$message: line $line extends beyond its left edge", layout.getLineLeft(line) >= -1f)
        }
    }

    private fun assertInside(message: String, child: RectF, parent: RectF) {
        assertTrue("$message: $child outside $parent", child.left >= parent.left - 1f && child.top >= parent.top - 1f && child.right <= parent.right + 1f && child.bottom <= parent.bottom + 1f)
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

    private fun measure(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 480, 640)
    }

    private fun saveScreenshot(fixture: Fixture, focus: Int) {
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "home-layout-regression").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
        fixture.root.draw(Canvas(bitmap))
        File(directory, "${fixture.name}-focus$focus.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }

    private fun menuItems(context: Context): List<MenuItem> {
        val entries = listOf(
            Triple(R.string.demo_send_messages, MenuConfigType.MenuInfoType.SEND_MSG, R.mipmap.msg_send_select),
            Triple(R.string.demo_receive_messages, MenuConfigType.MenuInfoType.RECEIVE_MSG, R.mipmap.msg_receive_default),
            Triple(R.string.demo_sdk_photo_video, MenuConfigType.MenuInfoType.SDK_TAKE_PHOTO, R.mipmap.icon_take_photo),
            Triple(R.string.demo_device_tools, MenuConfigType.MenuInfoType.DEVICE_TOOLS, R.drawable.icon_device_tools),
            Triple(R.string.demo_face_detection, MenuConfigType.MenuInfoType.MENU_FACE_RECOG, R.mipmap.icon_face_recog),
            Triple(R.string.item_plate_recog, MenuConfigType.MenuInfoType.MENU_LPR_RECOG, R.mipmap.icon_car),
            Triple(R.string.demo_standalone_asr_tts, MenuConfigType.MenuInfoType.ONLINE_ASR_TTS, R.mipmap.app_online_speech)
        )
        return entries.map { (text, type, icon) -> MenuItem(context.getString(text), type, icon, icon, R.drawable.home_bg, R.drawable.home_bg_focus, false) }
    }

    private data class Fixture(
        val name: String,
        val root: ViewGroup,
        val recycler: RecyclerView,
        val adapter: HomeAdapter,
        var geometry: List<Int>? = null
    )
}
