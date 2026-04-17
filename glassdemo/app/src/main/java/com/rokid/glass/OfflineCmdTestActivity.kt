package com.rokid.glass

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import com.rokid.glass.base.BaseGlassActivity
import com.rokid.glass.base.GlassKeyEvent
import com.rokid.glesse.R
import com.rokid.glesse.databinding.ActivityOfflineCmdOpenSdkTestBinding
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.sdk.base.data.offlineCmd.bean.VoiceAction
import com.rokid.security.glass3.sdk.base.data.offlineCmd.listener.IVoiceCallback
import java.util.Date
import java.util.Locale

/**
 * 离线语音指令测试,推荐3-5个词，不要有叠音
 */
class OfflineCmdTestActivity : BaseGlassActivity() {

    private lateinit var binding: ActivityOfflineCmdOpenSdkTestBinding
    private val logBuilder = StringBuilder()
    private var selectBtnStatus = 0

    private val zhWords by lazy {
        listOf(
            VoiceAction("测试中文", "ce shi zhong wen", callback("测试中文")),
            VoiceAction("打开相机", "da kai xiang ji", callback("打开相机")),
            VoiceAction("关闭相机", "guan bi xiang ji", callback("关闭相机"))
        )
    }

    private val enWords by lazy {
        listOf(
            VoiceAction("how old are you", "how old are you", callback("how old are you")),
            VoiceAction("test english", "test english", callback("test english")),
            VoiceAction("are you ok", "are you ok", callback("are you ok")),
            VoiceAction(
                "I am eighteen years old this year",
                "I am eighteen years old this year",
                callback("I am eighteen years old this year")
            ),
            VoiceAction("open camera", "open camera", callback("open camera")),
            VoiceAction("close camera", "close camera", callback("close camera"))
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOfflineCmdOpenSdkTestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        GlassSdk.getGlassOfflineCmdService()?.init()
        refreshCurrentLanguage()
        appendLog("page init done")
        unAllSelectState(binding.llMain)
        selectBtn(binding.btnSwitchZh)
        initView()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            GlassSdk.getGlassOfflineCmdService()?.clearWords("ZH_CN")
            GlassSdk.getGlassOfflineCmdService()?.clearWords("EN_US")
        } catch (_: Throwable) {
        }
    }

    private fun callback(word: String): IVoiceCallback {
        return object : IVoiceCallback.Stub() {
            override fun onVoiceTriggered() {
                runOnUiThread {
                    appendLog("triggered: $word")
                }
            }
        }
    }

    private fun getLanguage(): String {
        return try {
            GlassSdk.getGlassOfflineCmdService()?.language ?: "ZH_CN"
        } catch (t: Throwable) {
            appendLog("getLanguage failed: ${t.message}")
            "ZH_CN"
        }
    }

    private fun setLanguage(language: String) {
        try {
            GlassSdk.getGlassOfflineCmdService()?.setLanguage(language)
            appendLog("setLanguage: $language")
            refreshCurrentLanguage()
        } catch (t: Throwable) {
            appendLog("setLanguage failed (old service?): ${t.message}")
        }
    }

    private fun setWords(language: String, words: List<VoiceAction>) {
        try {
            GlassSdk.getGlassOfflineCmdService()?.setWords(language, words)
            appendLog("setWords($language): ${words.map { it.text }}")
            refreshCurrentLanguage()
        } catch (t: Throwable) {
            appendLog("setWords failed (old service?): ${t.message}")
        }
    }

    private fun clearWords(language: String) {
        try {
            GlassSdk.getGlassOfflineCmdService()?.clearWords(language)
            appendLog("clearWords: $language")
        } catch (t: Throwable) {
            appendLog("clearWords failed (old service?): ${t.message}")
        }
    }

    @SuppressLint("SetTextI18n")
    private fun refreshCurrentLanguage() {
        val current = getLanguage()
        binding.tvCurrentLanguage.text = "Current: $current"
    }

    private fun appendLog(message: String) {
        if (logBuilder.length > 3000) {
            logBuilder.clear()
        }
        val now = DateFormat.format("HH:mm:ss", Date()).toString()
        val text = String.format(Locale.getDefault(), "[%s] %s\n", now, message)
        logBuilder.insert(0, text)
        binding.tvLogs.text = logBuilder.toString()
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        when (keyEvent) {
            GlassKeyEvent.KEYCODE_BEHIND -> {
                selectBtnStatus--
                if (selectBtnStatus < 0) {
                    selectBtnStatus = 5
                }
                selectBtnStatus %= 6
                when (selectBtnStatus) {
                    1 -> {
                        unAllSelectState(binding.llMain)
                        selectBtn(binding.btnSwitchEn)
                    }

                    2 -> {
                        unAllSelectState(binding.llMain)
                        selectBtn(binding.btnSetZhWords)
                    }

                    3 -> {
                        unAllSelectState(binding.llMain)
                        selectBtn(binding.btnSetEnWords)
                    }

                    4 -> {
                        unAllSelectState(binding.llMain)
                        selectBtn(binding.btnClearCurrent)
                    }

                    5 -> {
                        unAllSelectState(binding.llMain)
                        selectBtn(binding.btnClearAll)
                    }

                    0 -> {
                        unAllSelectState(binding.llMain)
                        selectBtn(binding.btnSwitchZh)
                    }
                }
            }

            GlassKeyEvent.KEYCODE_FRONT -> {
                selectBtnStatus++
                selectBtnStatus %= 6
                when (selectBtnStatus) {
                    1 -> {
                        unAllSelectState(binding.llMain)
                        selectBtn(binding.btnSwitchEn)
                    }

                    2 -> {
                        unAllSelectState(binding.llMain)
                        selectBtn(binding.btnSetZhWords)
                    }

                    3 -> {
                        unAllSelectState(binding.llMain)
                        selectBtn(binding.btnSetEnWords)
                    }

                    4 -> {
                        unAllSelectState(binding.llMain)
                        selectBtn(binding.btnClearCurrent)
                    }

                    5 -> {
                        unAllSelectState(binding.llMain)
                        selectBtn(binding.btnClearAll)
                    }

                    0 -> {
                        unAllSelectState(binding.llMain)
                        selectBtn(binding.btnSwitchZh)
                    }
                }
            }

            GlassKeyEvent.KEYCODE_CLICK -> {
                val selectedView = findSelectedView(binding.llMain)
                selectedView?.performClick()
            }
        }
        return super.onGlassKeyEvent(keyEvent)
    }

    private fun findSelectedView(viewGroup: ViewGroup): View? {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child.isSelected) {
                return child
            }
            if (child is ViewGroup) {
                val found = findSelectedView(child)
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }

    fun unAllSelectState(viewGroup: ViewGroup) {
        for (i in 0 until viewGroup.childCount) {
            if (viewGroup.getChildAt(i) is ViewGroup) {
                unAllSelectState(viewGroup.getChildAt(i) as ViewGroup)
            } else if (viewGroup.getChildAt(i) is AppCompatButton) {
                unSelectBtn(viewGroup.getChildAt(i) as AppCompatButton)
            }
        }
    }

    private fun unSelectBtn(textView: AppCompatButton) {
        textView.clearFocus()
        textView.isSelected = false
        textView.isClickable = false
        textView.isFocusable = false
        textView.setTextColor(ContextCompat.getColor(this, R.color.green_70))
        textView.setBackgroundResource(R.drawable.round_unselect_bg)
    }

    private fun selectBtn(textView: AppCompatButton) {
        textView.setTextColor(ContextCompat.getColor(this, R.color.green))
        textView.setBackgroundResource(R.drawable.round_select_bg)
        textView.isSelected = true
        textView.isClickable = true
        textView.isFocusable = true
        textView.requestFocus()
    }

    private fun initView() {
        binding.btnSwitchZh.setOnClickListener {
            setLanguage("ZH_CN")
            unAllSelectState(binding.llMain)
            selectBtn(it as AppCompatButton)
        }
        binding.btnSwitchEn.setOnClickListener {
            setLanguage("EN_US")
            unAllSelectState(binding.llMain)
            selectBtn(it as AppCompatButton)
        }
        binding.btnSetZhWords.setOnClickListener {
            setWords("ZH_CN", zhWords)
            unAllSelectState(binding.llMain)
            selectBtn(it as AppCompatButton)
        }
        binding.btnSetEnWords.setOnClickListener {
            setWords("EN_US", enWords)
            unAllSelectState(binding.llMain)
            selectBtn(it as AppCompatButton)
        }
        binding.btnClearCurrent.setOnClickListener {
            val current = getLanguage()
            clearWords(current)
            unAllSelectState(binding.llMain)
            selectBtn(it as AppCompatButton)
        }
        binding.btnClearAll.setOnClickListener {
            clearWords("ZH_CN")
            clearWords("EN_US")
            unAllSelectState(binding.llMain)
            selectBtn(it as AppCompatButton)
        }
    }

}


