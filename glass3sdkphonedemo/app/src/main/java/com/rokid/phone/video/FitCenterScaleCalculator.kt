package com.rokid.phone.video

import kotlin.math.roundToInt

/** 计算完整显示图像且保持宽高比时，OpenGL 顶点在 X/Y 方向的缩放比例。 */
object FitCenterScaleCalculator {
    data class Scale(val x: Float, val y: Float)
    data class Size(val width: Int, val height: Int)

    fun calculate(
        frameWidth: Int,
        frameHeight: Int,
        surfaceWidth: Int,
        surfaceHeight: Int,
    ): Scale {
        if (frameWidth <= 0 || frameHeight <= 0 || surfaceWidth <= 0 || surfaceHeight <= 0) {
            return Scale(1f, 1f)
        }

        val frameAspect = frameWidth.toFloat() / frameHeight
        val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight
        return when {
            frameAspect < surfaceAspect -> Scale(frameAspect / surfaceAspect, 1f)
            frameAspect > surfaceAspect -> Scale(1f, surfaceAspect / frameAspect)
            else -> Scale(1f, 1f)
        }
    }

    /** 计算能够完整放入容器、保持原始宽高比的最大整数尺寸。 */
    fun calculateSize(
        frameWidth: Int,
        frameHeight: Int,
        containerWidth: Int,
        containerHeight: Int,
    ): Size {
        if (frameWidth <= 0 || frameHeight <= 0 || containerWidth <= 0 || containerHeight <= 0) {
            return Size(containerWidth.coerceAtLeast(0), containerHeight.coerceAtLeast(0))
        }

        val frameAspect = frameWidth.toDouble() / frameHeight
        val containerAspect = containerWidth.toDouble() / containerHeight
        return if (frameAspect < containerAspect) {
            Size((containerHeight * frameAspect).roundToInt().coerceAtLeast(1), containerHeight)
        } else {
            Size(containerWidth, (containerWidth / frameAspect).roundToInt().coerceAtLeast(1))
        }
    }
}
