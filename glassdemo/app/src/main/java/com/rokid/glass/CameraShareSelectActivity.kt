package com.rokid.glass

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Button
import com.rokid.glass.base.BaseGlassActivity
import com.rokid.glass.base.GlassKeyEvent
import com.rokid.glass.media.TAG
import com.rokid.glesse.R

/**
 * Camera Share Demo 选择页面
 */
class CameraShareSelectActivity : BaseGlassActivity() {

    private lateinit var modeButtons: List<Button>
    private val modeValues = listOf("surface", "nv21", "mix")
    private var selectedModeIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_share_select)

        val surfaceButton = findViewById<Button>(R.id.btn_surface_mode)
        val nv21Button = findViewById<Button>(R.id.btn_nv21_mode)
        val mixButton = findViewById<Button>(R.id.btn_mix_mode)
        modeButtons = listOf(surfaceButton, nv21Button, mixButton)

        surfaceButton.setOnClickListener {
            Log.d(TAG,"----surface_mode")
            openMode("surface")
        }

        nv21Button.setOnClickListener {
            Log.d(TAG,"----nv21_mode")
            openMode("nv21")
        }

        mixButton.setOnClickListener {
            Log.d(TAG,"----mix_mode")
            openMode("mix")
        }

        updateModeSelection()
    }

    override fun onResume() {
        super.onResume()
        if (::modeButtons.isInitialized) updateModeSelection()
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        when (keyEvent) {
            GlassKeyEvent.KEYCODE_FRONT -> {
                moveSelection(1)
                return true
            }
            GlassKeyEvent.KEYCODE_BEHIND -> {
                moveSelection(-1)
                return true
            }
            GlassKeyEvent.KEYCODE_CLICK -> {
                openMode(modeValues[selectedModeIndex])
                return true
            }
        }
        return super.onGlassKeyEvent(keyEvent)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.repeatCount == 0) GlassKeyEvent(GlassKeyEvent.KEYCODE_FRONT)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (event.repeatCount == 0) GlassKeyEvent(GlassKeyEvent.KEYCODE_BEHIND)
                    return true
                }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                    if (event.repeatCount == 0) GlassKeyEvent(GlassKeyEvent.KEYCODE_CLICK)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun moveSelection(offset: Int) {
        selectedModeIndex = (selectedModeIndex + offset + modeButtons.size) % modeButtons.size
        Log.d(TAG, "mode changed: index=$selectedModeIndex, mode=${modeValues[selectedModeIndex]}")
        updateModeSelection()
    }

    private fun updateModeSelection() {
        modeButtons.forEachIndexed { index, button -> button.isSelected = index == selectedModeIndex }
    }

    private fun openMode(mode: String) {
        startActivity(Intent(this, CameraShareDemoActivity::class.java).apply {
            putExtra("mode", mode)
        })
    }
}
