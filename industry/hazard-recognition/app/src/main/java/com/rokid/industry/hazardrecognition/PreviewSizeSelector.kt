package com.rokid.industry.hazardrecognition

/** 用例：为左上角的横向预览选择尺寸，最大使用 1280×720。 */
internal object PreviewSizeSelector {
    fun select(sizes: List<Triple<Int, Int, Boolean>>): Pair<Int, Int> {
        // SDK 返回的是显示方向下的宽高，第三项表示是否为竖向画面。
        // 本例使用横向小窗，因此只选择宽大于高的尺寸；不要直接交换竖向尺寸的宽高。
        // NV21 要求宽高为偶数；限制尺寸，减少持续预览和上传时的数据量。
        val selected = sizes.filter { (width, height, _) ->
            width >= 2 && height >= 2 && width % 2 == 0 && height % 2 == 0 &&
                width > height && width <= 1280 && height <= 720
        }.maxByOrNull { it.first * it.second }

        // 没有合适尺寸时使用 SDK 默认值：CameraShareConfig 的宽、高均为 0。
        // 实际收到的帧可能采用其他尺寸，后续预览和编码始终读取帧回调中的宽高。
        return selected?.let { it.first to it.second } ?: (0 to 0)
    }
}
