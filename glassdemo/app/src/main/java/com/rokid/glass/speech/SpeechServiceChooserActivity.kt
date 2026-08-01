package com.rokid.glass.speech

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import com.rokid.glass.base.GlassKeyEvent
import com.rokid.glass.speech.privateservice.PrivateSpeechActivity
import com.rokid.glass.speech.publicservice.PublicSpeechActivity
import com.rokid.glesse.R

class SpeechServiceChooserActivity : SpeechGestureActivity() {
    private lateinit var choices: List<Button>
    private val chooserState = SpeechChooserState(lastIndex = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_speech_service_chooser)

        val publicButton = findViewById<Button>(R.id.btn_public_speech)
        val privateButton = findViewById<Button>(R.id.btn_private_speech)
        choices = listOf(publicButton, privateButton)

        select(0)
    }

    override fun onResume() {
        super.onResume()
        chooserState.setForeground(true)
    }

    override fun onPause() {
        chooserState.setForeground(false)
        super.onPause()
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        when (keyEvent) {
            GlassKeyEvent.KEYCODE_FRONT -> {
                select(chooserState.selectedIndex + 1)
            }
            GlassKeyEvent.KEYCODE_BEHIND -> {
                select(chooserState.selectedIndex - 1)
            }
            GlassKeyEvent.KEYCODE_CLICK -> {
                if (chooserState.acceptGlassClick()) {
                    openSelected(chooserState.selectedIndex)
                }
            }
            else -> return super.onGlassKeyEvent(keyEvent)
        }
        return true
    }

    private fun select(index: Int) {
        syncSelection(index)
        choices[chooserState.selectedIndex].requestFocus()
    }

    private fun syncSelection(index: Int) {
        chooserState.select(index)
        choices.forEach { it.isSelected = false }
        choices[chooserState.selectedIndex].isSelected = true
    }

    private fun openSelected(index: Int) {
        val destination = if (index == 0) {
            PublicSpeechActivity::class.java
        } else {
            PrivateSpeechActivity::class.java
        }
        startActivity(Intent(this, destination))
    }
}
