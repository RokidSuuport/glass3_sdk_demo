package com.rokid.glass.speech.privateservice

import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.fragment.app.Fragment
import com.rokid.glesse.R

class PrivateSpeechAsrFragment : Fragment(R.layout.fragment_private_speech_asr), PrivateSpeechPage {
    private var buttons: List<Button> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        buttons = listOf(
            view.findViewById(R.id.btn_private_asr_connect),
            view.findViewById(R.id.btn_private_asr_start),
            view.findViewById(R.id.btn_private_asr_stop),
            view.findViewById(R.id.btn_private_asr_close),
        )
    }

    override fun requestActionFocus(itemIndex: Int) {
        buttons.forEach { it.isSelected = false }
        buttons.getOrNull(itemIndex)?.apply {
            isSelected = true
            requestFocus()
        }
    }
}
