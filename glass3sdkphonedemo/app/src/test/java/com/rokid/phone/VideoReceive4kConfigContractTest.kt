package com.rokid.phone

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoReceive4kConfigContractTest {
    @Test
    fun `video stream demo uses camera2 supported common resolution presets`() {
        val source = File(
            "src/main/java/com/rokid/phone/VideoReceiveActivity.kt"
        ).readText()
        val layout = File("src/main/res/layout/activity_video_receive.xml").readText()

        assertTrue(source.contains("private val defaultFps = 15"))
        assertTrue(source.contains("private val defaultBitrate = 20_000_000"))
        assertTrue(source.contains("val DEFAULT_RESOLUTION = ResolutionOption(2400, 1800)"))
        assertTrue(source.contains("ResolutionOption(2400, 1800)"))
        assertTrue(source.contains("ResolutionOption(1920, 1080)"))
        assertTrue(source.contains("ResolutionOption(1280, 720)"))
        assertTrue(source.contains("ResolutionOption(648, 648)"))
        assertTrue(source.contains("ResolutionOption(640, 480)"))
        assertTrue(!source.contains("ResolutionOption(2048, 1536)"))
        assertTrue(!source.contains("ResolutionOption(1600, 1200)"))
        assertTrue(!source.contains("ResolutionOption(1440, 1080)"))
        assertTrue(!source.contains("ResolutionOption(1024, 768)"))
        assertTrue(!source.contains("ResolutionOption(1920, 1440)"))
        assertTrue(!source.contains("ResolutionOption(2340, 1080)"))
        assertTrue(!source.contains("ResolutionOption(3840, 2160)"))
        assertTrue(source.contains("500_000..30_000_000"))
        assertTrue(layout.contains("android:hint=\"15\""))
        assertTrue(layout.contains("android:hint=\"20000000\""))
        assertTrue(layout.contains("android:hint=\"1800\""))
    }
}
