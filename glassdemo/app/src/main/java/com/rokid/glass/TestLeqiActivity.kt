package com.rokid.glass

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.rokid.glass.base.BaseGlassActivity
import com.rokid.glesse.databinding.ActivityTestLeqiBinding
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.callback.LeqiInterceptor
import com.rokid.security.glass3.open.sdk.uitls.log.L
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TestLeqiActivity : BaseGlassActivity() {

    private val TAG = "TestLeqiActivity::"
    private lateinit var binding: ActivityTestLeqiBinding
    private var logCount = 0
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private var logJob: Job? = null

    private val leqiInterceptor = object : LeqiInterceptor {
        override fun onLeqiInstruction(extra: String?): Boolean {
            val time = dateFormat.format(Date())
            appendLog("[$time] Leqi 指令到达! extra=$extra")
            L.d(TAG, "Leqi 指令拦截: extra=$extra")
            return true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityTestLeqiBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        GlassSdk.registerLeqiInterceptor(this, leqiInterceptor)
        binding.tvInterceptorStatus.text = "Interceptor: 已注册"
        appendLog("LeqiInterceptor 已注册")
        updateStatus()

        logJob = lifecycleScope.launch {
            while (isActive) {
                delay(2000)
                updateStatus()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        GlassSdk.unregisterLeqiInterceptor()
        binding.tvInterceptorStatus.text = "Interceptor: 未注册"
        appendLog("LeqiInterceptor 已注销")
        logJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        logJob?.cancel()
    }

    private fun updateStatus() {
        binding.tvForegroundStatus.text = "Foreground: 是"
    }

    private fun appendLog(msg: String) {
        logCount++
        val text = binding.tvLog.text.toString()
        val newText = if (text == "(无)") "$logCount. $msg" else "$text\n$logCount. $msg"
        val lines = newText.split("\n")
        val trimmed = if (lines.size > 50) lines.takeLast(50).joinToString("\n") else newText
        binding.tvLog.text = trimmed
    }
}
