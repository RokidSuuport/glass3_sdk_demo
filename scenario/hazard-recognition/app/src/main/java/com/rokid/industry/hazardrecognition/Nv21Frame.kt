package com.rokid.industry.hazardrecognition

/**
 * 用例：在相机回调中先 bytes.copyOf() 再创建本对象；交给预览和识别后，不要再修改这份数组。
 * receivedAt 传 SystemClock.elapsedRealtime() 用于过期判断，wallTime 传当前日期时间用于展示。
 */
data class Nv21Frame(val bytes: ByteArray, val width: Int, val height: Int,
    val receivedAt: Long, val wallTime: Long) {
    // 用例：上传前传入 SystemClock.elapsedRealtime()，只接受收到后 2 秒内的画面。
    fun isFresh(now: Long) = now - receivedAt in 0..2000

    companion object {
        // 用例：4×2 的 NV21 应为 12 字节；宽高必须为偶数，数据大小必须是宽×高×1.5。
        fun validSize(bytes: Int, width: Int, height: Int): Boolean =
            width in 2..4096 && height in 2..4096 && width % 2 == 0 && height % 2 == 0 &&
                bytes.toLong() == width.toLong() * height * 3 / 2
    }
}
