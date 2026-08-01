package com.rokid.glass.speech.privateservice

import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.fragment.app.Fragment
import com.rokid.glesse.R

class PrivateSpeechInitFragment : Fragment(R.layout.fragment_private_speech_init), PrivateSpeechPage {
    private var buttons: List<Button> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        buttons = listOf(view.findViewById(R.id.btn_private_init), view.findViewById(R.id.btn_private_release))
    }

    override fun requestActionFocus(itemIndex: Int) {
        buttons.forEach { it.isSelected = false }
        buttons.getOrNull(itemIndex)?.apply {
            isSelected = true
            requestFocus()
        }
    }
}
