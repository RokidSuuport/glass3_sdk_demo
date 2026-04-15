package com.rokid.glass.media

import android.util.Size

/**
 * 视频参数
 * @param frameSize 视频宽高
 * @param frameRate 视频帧率
 * @param frameInterval 帧间隔
 * @param bitRate 比特率
 */
data class VideoData(
    val frameSize: Size = Size(0, 0),
    val frameRate: Int = 30,
    val frameInterval: Int = 1,
    val bitRate: Int = frameSize.width * frameSize.height * frameRate / 16,
) {

    fun isAvailable(): Boolean =
        (frameSize.width > 0 && frameSize.height > 0
                && frameRate > 0 && frameInterval > 0 && bitRate > 0)
}