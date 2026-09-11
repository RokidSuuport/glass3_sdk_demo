package com.rokid.industry.hazardrecognition

/** 用例：选择最多 1280×720 像素的预览尺寸，横屏和竖屏都可以使用。 */
internal object PreviewSizeSelector {
    fun select(sizes: List<Triple<Int, Int, Boolean>>): Pair<Int, Int> {
        // SDK 的第三项只说明画面方向，不参与筛选，也不用于交换宽高。
        // NV21 要求宽高为偶数；限制长短边，减少持续预览和上传时的数据量。
        val selected = sizes.filter { (width, height, _) ->
            width >= 2 && height >= 2 && width % 2 == 0 && height % 2 == 0 &&
                maxOf(width, height) <= 1280 && minOf(width, height) <= 720
        }.maxByOrNull { it.first * it.second }

        // 没有合适尺寸时使用 SDK 默认值：CameraShareConfig 的宽、高均为 0。
        // 实际收到的帧可能采用其他尺寸，后续预览和编码始终读取帧回调中的宽高。
        return selected?.let { it.first to it.second } ?: (0 to 0)
    }
}
