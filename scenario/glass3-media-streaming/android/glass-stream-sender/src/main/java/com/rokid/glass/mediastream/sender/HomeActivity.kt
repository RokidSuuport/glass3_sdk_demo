package com.rokid.glass.mediastream.sender

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.rokid.glass.mediastream.sender.capture.MediaCaptureActivity
import com.rokid.glass.mediastream.sender.databinding.ActivityHomeBinding
import com.rokid.glass.mediastream.sender.streaming.StreamingActivity

/**
 * Glass3 端的正式功能入口。
 *
 * “音视频传输”演示一键推流接口；“原始媒体采集”演示不经过 WebRTC 的 NV21/PCM 取流接口。
 */
class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.streamingEntry.setOnClickListener {
            startActivity(Intent(this, StreamingActivity::class.java))
        }
        binding.captureEntry.setOnClickListener {
            startActivity(Intent(this, MediaCaptureActivity::class.java))
        }
        binding.streamingEntry.requestFocus()
    }
}
