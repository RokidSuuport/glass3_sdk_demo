package com.rokid.glass

import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.widget.AppCompatButton
import com.rokid.glass.base.BaseGlassActivity
import com.rokid.glass.base.GlassKeyEvent
import com.rokid.glesse.R
import com.rokid.glesse.databinding.ActivityTestLeqiBinding
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.callback.LeqiInterceptor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Removing A must preserve a subsequently registered B. */
class TestLeqiActivity : BaseGlassActivity() {
    companion object {
        private const val TAG = "LeqiAB"
    }

    // Let buttons/ScrollViews handle pointer input once. Glasses keys are handled below.
    override val translateTouchToGlassGesture = false

    private enum class Owner { A, B }
    private lateinit var binding: ActivityTestLeqiBinding
    private lateinit var buttons: List<AppCompatButton>
    private var selectedIndex = 0
    private val countA = AtomicInteger()
    private val countB = AtomicInteger()
    @Volatile private var expectedOwner: Owner? = null
    @Volatile private var pageResumed = false
    private val logLines = ArrayDeque<String>()
    private var logCount = 0
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    // Keep the exact same two objects for the entire Activity lifetime.
    private val interceptorA = createInterceptor(Owner.A, countA)
    private val interceptorB = createInterceptor(Owner.B, countB)

    private fun createInterceptor(owner: Owner, counter: AtomicInteger) = object : LeqiInterceptor {
        override fun onLeqiInstruction(extra: String?): Boolean {
            val count = counter.incrementAndGet()
            val expectedAtArrival = expectedOwner
            val foregroundAtArrival = pageResumed
            val receivedAt = System.currentTimeMillis()
            Log.i(TAG, "CALLBACK owner=$owner count=$count expected=$expectedAtArrival " +
                "pageResumed=$foregroundAtArrival receivedAt=$receivedAt extra=$extra")
            // Binder callbacks may already be in flight during removal.
            runOnUiThread {
                if (!isDestroyed) {
                    updateStatus()
                    appendLog(getString(R.string.leqi_ab_callback, owner.name, count,
                        ownerLabel(expectedAtArrival), foregroundAtArrival.toString(), extra ?: "<null>"), receivedAt)
                }
            }
            return true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTestLeqiBinding.inflate(layoutInflater)
        setContentView(binding.root)
        buttons = listOf(binding.btnRegisterA, binding.btnRegisterB, binding.btnUnregisterA, binding.btnUnregisterB)
        val operations = listOf<() -> Unit>(
            { register(Owner.A) }, { register(Owner.B) },
            { unregister(Owner.A, "button") }, { unregister(Owner.B, "button") }
        )
        buttons.forEachIndexed { index, button ->
            button.setOnClickListener {
                selectButton(index)
                operations[index]()
            }
        }
        selectButton(0)
        updateStatus()
        appendLog(getString(R.string.leqi_ab_log_hint))
    }

    override fun onResume() {
        super.onResume()
        pageResumed = true
        updateStatus()
        // Do not register A automatically or restore the previous registration.
        appendLog(getString(R.string.leqi_ab_resume))
    }

    override fun onPause() {
        pageResumed = false
        // Never globally clear: a different page may own a newer interceptor.
        unregister(Owner.A, "onPause")
        unregister(Owner.B, "onPause")
        super.onPause()
    }

    private fun interceptor(owner: Owner) = if (owner == Owner.A) interceptorA else interceptorB

    private fun register(owner: Owner) {
        performOperation("register", owner, "button") {
            GlassSdk.registerLeqiInterceptor(this, interceptor(owner))
            expectedOwner = owner
        }
    }

    private fun unregister(owner: Owner, source: String) {
        performOperation("unregister", owner, source) {
            // Always call the SDK, including unregister(A) while B is expected.
            GlassSdk.unregisterLeqiInterceptor(interceptor(owner))
            if (expectedOwner == owner) expectedOwner = null
        }
    }

    private fun performOperation(action: String, owner: Owner, source: String, operation: () -> Unit) {
        try {
            operation()
            val description = getString(if (action == "register") R.string.leqi_ab_register else R.string.leqi_ab_unregister, owner.name)
            Log.i(TAG, "OP $action $owner source=$source expected=$expectedOwner A=${countA.get()} B=${countB.get()}")
            appendLog(getString(R.string.leqi_ab_operation, "$action($owner)", description, source, ownerLabel(expectedOwner)))
        } catch (error: Exception) {
            Log.e(TAG, "OP $action $owner source=$source failed", error)
            appendLog(getString(R.string.leqi_ab_error, "$action $owner", error.message ?: error.javaClass.simpleName))
        }
        updateStatus()
    }

    private fun ownerLabel(owner: Owner?) = owner?.name ?: getString(R.string.leqi_ab_none)

    private fun updateStatus() {
        binding.tvInterceptorStatus.text = getString(R.string.leqi_ab_expected, ownerLabel(expectedOwner))
        binding.tvCallbackCounts.text = getString(R.string.leqi_ab_counts, countA.get(), countB.get())
        binding.tvForegroundStatus.setText(if (pageResumed) R.string.demo_foreground_yes else R.string.demo_foreground_no)
    }

    private fun appendLog(message: String, timestamp: Long = System.currentTimeMillis()) {
        logCount++
        logLines.addLast("$logCount. [${dateFormat.format(Date(timestamp))}] ${message.replace("\n", "\\n")}")
        while (logLines.size > 50) logLines.removeFirst()
        binding.tvLog.text = logLines.joinToString("\n")
        binding.logScroll.post { if (!isDestroyed) binding.logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun selectButton(index: Int) {
        selectedIndex = index
        buttons.forEachIndexed { buttonIndex, button ->
            button.isSelected = buttonIndex == index
            button.setBackgroundResource(if (button.isSelected) R.drawable.round_select_bg else R.drawable.round_unselect_bg)
        }
        buttons[index].post {
            val button = buttons[index]
            button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true)
        }
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        if (!::buttons.isInitialized || !pageResumed) return super.onGlassKeyEvent(keyEvent)
        when (keyEvent) {
            GlassKeyEvent.KEYCODE_FRONT -> selectButton((selectedIndex + 1) % buttons.size)
            GlassKeyEvent.KEYCODE_BEHIND -> selectButton((selectedIndex + buttons.size - 1) % buttons.size)
            GlassKeyEvent.KEYCODE_CLICK -> buttons[selectedIndex].performClick()
            else -> return super.onGlassKeyEvent(keyEvent)
        }
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val gesture = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> GlassKeyEvent.KEYCODE_FRONT
            KeyEvent.KEYCODE_DPAD_LEFT -> GlassKeyEvent.KEYCODE_BEHIND
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> GlassKeyEvent.KEYCODE_CLICK
            else -> return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            GlassKeyEvent(gesture)
        }
        return true
    }
}
