package com.rokid.industry.hazardrecognition

import org.junit.Assert.assertEquals
import org.junit.Test

/** 用例：模拟 SDK 返回不同方向的尺寸，不需要连接眼镜。 */
class PreviewSizeSelectorTest {
    // 回归：只有竖向尺寸时沿用 SDK 默认配置，避免横向小窗内出现细长的竖向预览。
    @Test fun portraitOnlySizesUseSdkDefaults() {
        assertEquals(0 to 0, PreviewSizeSelector.select(listOf(
            Triple(1080, 1920, true), Triple(480, 640, true), Triple(720, 1280, true),
        )))
    }

    // 即使竖向尺寸的像素更多，也优先使用适合本例横向小窗的尺寸。
    @Test fun landscapeIsPreferredOverLargerPortraitSize() {
        assertEquals(640 to 480, PreviewSizeSelector.select(listOf(
            Triple(720, 1280, true), Triple(640, 480, false),
        )))
    }

    @Test fun landscapeSizesAlsoChooseLargestWithinBudget() {
        assertEquals(1280 to 720, PreviewSizeSelector.select(listOf(
            Triple(640, 480, false), Triple(1920, 1080, false), Triple(1280, 720, false),
        )))
    }

    // 不能把奇数宽高或超出本例数据量限制的尺寸传入配置。
    @Test fun invalidSizesAreSkipped() {
        assertEquals(640 to 480, PreviewSizeSelector.select(listOf(
            Triple(1279, 720, false), Triple(0, 720, true), Triple(1024, 1024, false),
            Triple(640, 480, false),
        )))
    }

    // 查询不到合适尺寸时交给 SDK 选择默认值，不自行编造一个设备可能不支持的尺寸。
    @Test fun noUsableSizeUsesSdkDefaults() {
        assertEquals(0 to 0, PreviewSizeSelector.select(emptyList()))
        assertEquals(0 to 0, PreviewSizeSelector.select(listOf(Triple(1080, 1920, true))))
    }
}
