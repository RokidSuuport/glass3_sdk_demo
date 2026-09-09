package com.rokid.glass.mediastream.verification

import android.app.Activity
import com.rokid.glass.mediastream.capture.GlassMediaCapture
import com.rokid.glass.mediastream.streaming.GlassMediaStreamer

/**
 * 独立 Gradle 工程的编译探针：只通过已发布坐标访问两项客户入口，不引用源工程模块。
 */
class ConsumerSmokeActivity : Activity() {
    private val streamer by lazy { GlassMediaStreamer.create(applicationContext) }
    private val capture by lazy { GlassMediaCapture.create(applicationContext) }

    override fun onDestroy() {
        streamer.release()
        capture.release()
        super.onDestroy()
    }
}
