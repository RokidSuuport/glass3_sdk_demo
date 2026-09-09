package com.rokid.glass.mediastream.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureContractsTest {
    @Test
    fun `defaults match verified Glass formats`() {
        assertEquals(VideoCaptureOptions(1280, 720, 15, false, false), VideoCaptureOptions())
        assertEquals(AudioCaptureOptions(16_000, 1, 16), AudioCaptureOptions())
        assertEquals(12_000L, CaptureOptions().startupTimeoutMs)
    }

    @Test
    fun `video options reject invalid NV21 dimensions and frame rates`() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoCaptureOptions(width = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoCaptureOptions(height = -2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoCaptureOptions(width = 1279)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoCaptureOptions(height = 719)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoCaptureOptions(fps = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoCaptureOptions(fps = 61)
        }
    }

    @Test
    fun `audio options enforce PCM16 with a positive mono or stereo sample rate`() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioCaptureOptions(sampleRateHz = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioCaptureOptions(channelCount = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioCaptureOptions(channelCount = 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioCaptureOptions(bitsPerSample = 8)
        }
    }

    @Test
    fun `capture options reject non-positive startup timeouts`() {
        assertThrows(IllegalArgumentException::class.java) {
            CaptureOptions(startupTimeoutMs = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CaptureOptions(startupTimeoutMs = -1)
        }
    }

    @Test
    fun `every stable error maps to its approved customer copy`() {
        val approvedCopy = linkedMapOf(
            MediaErrorCode.PERMISSION_REQUIRED to
                ("缺少相机或录音权限" to "引导授权，不自动循环重试"),
            MediaErrorCode.SDK_NOT_READY to
                ("Glass SDK 未连接完成" to "重新绑定一次，失败后提示重试"),
            MediaErrorCode.SDK_DISCONNECTED to
                ("SDK 运行中断开" to "释放媒体并重新绑定一次"),
            MediaErrorCode.CAMERA_IN_USE to
                ("相机被其他功能占用" to "提示关闭扫码、录像或其他应用"),
            MediaErrorCode.CAMERA_START_TIMEOUT to
                ("相机启动超时" to "清理后允许手动重试"),
            MediaErrorCode.VIDEO_FRAME_TIMEOUT to
                ("启动后没有 NV21 帧" to "停止相机并提示检查服务/占用"),
            MediaErrorCode.AUDIO_START_FAILED to
                ("麦克风启动失败" to "清理音频并提示权限/占用"),
            MediaErrorCode.AUDIO_DATA_TIMEOUT to
                ("启动后没有 PCM 数据" to "停止音频并允许重试"),
            MediaErrorCode.SERVER_UNREACHABLE to
                ("无法连接 PC" to "检查地址、端口、防火墙和网络"),
            MediaErrorCode.RECEIVER_NOT_READY to
                ("浏览器接收端未就绪" to "保持等待或提示先开始接收"),
            MediaErrorCode.WEBRTC_NEGOTIATION_FAILED to
                ("WebRTC 协商失败" to "清理连接并允许重试"),
            MediaErrorCode.NETWORK_DISCONNECTED to
                ("传输中网络断开" to "有界自动重连并显示次数"),
        )

        assertEquals(approvedCopy.keys.toList(), MediaErrorCode.entries)
        approvedCopy.forEach { (code, copy) ->
            val failure = MediaFailureCatalog.forCode(code)
            assertEquals(code, failure.code)
            assertEquals(copy.first, failure.userMessage)
            assertEquals(copy.second, failure.suggestedAction)
            assertTrue(failure.userMessage.isNotBlank())
            assertTrue(failure.suggestedAction.isNotBlank())
            assertEquals("", failure.technicalMessage)
            assertNull(failure.cause)
        }
    }

    @Test
    fun `NV21 copies use actual frame dimensions and exclude unused pool capacity`() {
        val pooledData = ByteArray(32) { it.toByte() }
        val frame = Nv21Frame.create(
            data = pooledData,
            width = 4,
            height = 2,
            timestampNs = 99L,
            finalRelease = {},
        )

        val copy = frame.copyData()
        pooledData.fill(42)

        assertEquals(12, copy.size)
        assertArrayEquals(ByteArray(12) { it.toByte() }, copy)
        frame.close()
    }

    @Test
    fun `NV21 frames reject invalid dimensions and undersized data`() {
        assertThrows(IllegalArgumentException::class.java) {
            val data = ByteArray(12)
            Nv21Frame.create(data, 3, 2, 1L) {}
        }
        assertThrows(IllegalArgumentException::class.java) {
            val data = ByteArray(11)
            Nv21Frame.create(data, 4, 2, 1L) {}
        }
    }

    @Test
    fun `NV21 frames reject dimensions whose byte count overflows`() {
        val largestEvenInt = Int.MAX_VALUE - 1

        assertThrows(IllegalArgumentException::class.java) {
            Nv21Frame.create(
                data = ByteArray(6),
                width = largestEvenInt,
                height = largestEvenInt,
                timestampNs = 1L,
                finalRelease = {},
            )
        }
    }
}
