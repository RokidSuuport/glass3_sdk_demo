package com.rokid.industry.hazardrecognition

/**
 * 用例：在相机回调中先 bytes.copyOf() 再构造本对象；发布后不再修改 bytes。
 * receivedAt 传 SystemClock.elapsedRealtime() 用于过期判断，wallTime 传当前日期时间用于展示。
 */
data class Nv21Frame(val bytes: ByteArray, val width: Int, val height: Int,
    val receivedAt: Long, val wallTime: Long) {
    // 用例：上传前用同一种单调时钟检查，只接受收到后 2 秒内的画面。
    fun isFresh(now: Long) = now - receivedAt in 0..2000

    companion object {
        // 用例：4×2 的 NV21 应为 12 字节；宽高必须为偶数，数据大小必须是宽×高×1.5。
        fun validSize(bytes: Int, width: Int, height: Int): Boolean =
            width in 2..4096 && height in 2..4096 && width % 2 == 0 && height % 2 == 0 &&
                bytes.toLong() == width.toLong() * height * 3 / 2
    }
}
