package com.rokid.phone.video

import java.nio.ByteBuffer

/**
 * 隔离远端视频流重启前后的数据。
 *
 * 排空阶段拒绝所有视频包；H.264 新流允许 SPS/PPS 等配置包进入全新的解码器，
 * 但在收到 IDR 关键帧前丢弃普通预测帧，避免旧 GOP 残留画面被渲染。
 */
class VideoFrameRecoveryGate {
    enum class Mode { NV21, H264 }

    private var accepting = false
    private var mode = Mode.NV21
    private var waitingForIdr = false

    @Synchronized
    fun beginDrain() {
        accepting = false
        waitingForIdr = false
    }

    @Synchronized
    fun resume(mode: Mode) {
        this.mode = mode
        accepting = true
        waitingForIdr = mode == Mode.H264
    }

    @Synchronized
    fun shouldAcceptNv21(): Boolean = accepting && mode == Mode.NV21

    @Synchronized
    fun shouldAcceptH264(source: ByteBuffer): Boolean {
        if (!accepting || mode != Mode.H264) return false
        if (!waitingForIdr) return true
        if (H264NalUnitInspector.containsIdr(source)) {
            waitingForIdr = false
            return true
        }
        return !H264NalUnitInspector.containsFrame(source)
    }
}
