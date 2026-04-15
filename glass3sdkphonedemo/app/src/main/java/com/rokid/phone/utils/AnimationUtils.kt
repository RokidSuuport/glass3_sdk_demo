package com.rokid.phone.utils

import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.Animation.AnimationListener
import android.view.animation.AnimationSet
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.view.animation.ScaleAnimation
import android.view.animation.TranslateAnimation

/**
 * Author: zhangshengwei
 * Date: 2025/8/16
 */
object AnimationUtils {

    /**
     * 创建旋转动画
     * @param fromDegrees 起始角度
     * @param toDegrees 结束角度
     * @param pivotX 旋转中心X轴 (相对自身比例 0f-1f)
     * @param pivotY 旋转中心Y轴 (相对自身比例 0f-1f)
     * @param duration 动画时长(ms)
     * @param repeatCount 重复次数 (-1表示无限)
     * @param repeatMode 重复模式 (RESTART/REVERSE)
     * @param interpolator 插值器
     */
    fun createRotationAnimation(
        fromDegrees: Float,
        toDegrees: Float,
        pivotX: Float = 0.5f,
        pivotY: Float = 0.5f,
        duration: Long = 500,
        repeatCount: Int = 0,
        repeatMode: Int = Animation.RESTART,
        interpolator: Interpolator = LinearInterpolator()
    ): RotateAnimation {
        return RotateAnimation(
            fromDegrees, toDegrees,
            Animation.RELATIVE_TO_SELF, pivotX,
            Animation.RELATIVE_TO_SELF, pivotY
        ).apply {
            this.duration = duration
            this.repeatCount = repeatCount
            this.repeatMode = repeatMode
            this.interpolator = interpolator
            fillAfter = true
        }
    }

    /**
     * 创建缩放动画
     * @param fromX 初始X轴缩放比例
     * @param toX 目标X轴缩放比例
     * @param fromY 初始Y轴缩放比例
     * @param toY 目标Y轴缩放比例
     * @param pivotX 缩放中心X轴
     * @param pivotY 缩放中心Y轴
     */
    fun createScaleAnimation(
        fromX: Float,
        toX: Float,
        fromY: Float,
        toY: Float,
        pivotX: Float = 0.5f,
        pivotY: Float = 0.5f,
        duration: Long = 500,
        repeatCount: Int = 0,
        repeatMode: Int = Animation.RESTART,
        interpolator: Interpolator = LinearInterpolator()
    ): ScaleAnimation {
        return ScaleAnimation(
            fromX, toX, fromY, toY,
            Animation.RELATIVE_TO_SELF, pivotX,
            Animation.RELATIVE_TO_SELF, pivotY
        ).apply {
            this.duration = duration
            this.repeatCount = repeatCount
            this.repeatMode = repeatMode
            this.interpolator = interpolator
            fillAfter = true
        }
    }

    /**
     * 创建平移动画
     * @param fromXDelta X轴起始位置(相对自身)
     * @param toXDelta X轴目标位置(相对自身)
     * @param fromYDelta Y轴起始位置(相对自身)
     * @param toYDelta Y轴目标位置(相对自身)
     */
    fun createTranslateAnimation(
        fromXDelta: Float,
        toXDelta: Float,
        fromYDelta: Float,
        toYDelta: Float,
        duration: Long = 500,
        repeatCount: Int = 0,
        repeatMode: Int = Animation.RESTART,
        interpolator: Interpolator = LinearInterpolator()
    ): TranslateAnimation {
        return TranslateAnimation(
            Animation.RELATIVE_TO_SELF, fromXDelta,
            Animation.RELATIVE_TO_SELF, toXDelta,
            Animation.RELATIVE_TO_SELF, fromYDelta,
            Animation.RELATIVE_TO_SELF, toYDelta
        ).apply {
            this.duration = duration
            this.repeatCount = repeatCount
            this.repeatMode = repeatMode
            this.interpolator = interpolator
            fillAfter = true
        }
    }

    /**
     * 创建透明度动画
     * @param fromAlpha 初始透明度(0f-1f)
     * @param toAlpha 目标透明度(0f-1f)
     */
    fun createAlphaAnimation(
        fromAlpha: Float,
        toAlpha: Float,
        duration: Long = 500,
        repeatCount: Int = 0,
        repeatMode: Int = Animation.RESTART,
        interpolator: Interpolator = LinearInterpolator()
    ): AlphaAnimation {
        return AlphaAnimation(fromAlpha, toAlpha).apply {
            this.duration = duration
            this.repeatCount = repeatCount
            this.repeatMode = repeatMode
            this.interpolator = interpolator
            fillAfter = true
        }
    }

    /**
     * 创建组合动画
     * @param animations 动画列表
     * @param shareInterpolator 是否共享插值器
     */
    fun createAnimationSet(
        animations: List<Animation>,
        shareInterpolator: Boolean = true,
        interpolator: Interpolator = LinearInterpolator()
    ): AnimationSet {
        return AnimationSet(shareInterpolator).apply {
            this.interpolator = interpolator
            animations.forEach { addAnimation(it) }
        }
    }

    /**
     * 启动动画
     * @param view 目标视图
     * @param animation 动画实例
     * @param listener 动画监听器
     */
    fun startAnimation(
        view: View,
        animation: Animation,
        listener: AnimationListener? = null
    ) {
        // 先取消可能存在的动画
        view.clearAnimation()
        // 设置监听器
        listener?.let { animation.setAnimationListener(it) }
        // 启动动画
        view.startAnimation(animation)
    }

    /**
     * 停止视图上的所有动画
     */
    fun stopAnimation(view: View) {
        view.clearAnimation()
    }

    // -------------------------- 常用动画快捷方法 --------------------------

    /**
     * 快速创建无限旋转动画
     */
    fun createInfiniteRotation(duration: Long = 2000): RotateAnimation {
        return createRotationAnimation(
            fromDegrees = 0f,
            toDegrees = 360f,
            duration = duration,
            repeatCount = Animation.INFINITE
        )
    }


    /**
     * 创建旋转动画（单次播放专用）
     * 简化配置，默认不重复，专注于单次动画场景
     */
    fun createSingleRotationAnimation(
        fromDegrees: Float,
        toDegrees: Float,
        pivotX: Float = 0.5f,
        pivotY: Float = 0.5f,
        duration: Long = 500,
        interpolator: Interpolator = LinearInterpolator()
    ): RotateAnimation {
        return createRotationAnimation(
            fromDegrees = fromDegrees,
            toDegrees = toDegrees,
            pivotX = pivotX,
            pivotY = pivotY,
            duration = duration,
            repeatCount = 0, // 强制单次播放
            repeatMode = Animation.RESTART,
            interpolator = interpolator
        )
    }



    /**
     * 快速创建淡入动画
     */
    fun createFadeInAnimation(duration: Long = 500): AlphaAnimation {
        return createAlphaAnimation(
            fromAlpha = 0f,
            toAlpha = 1f,
            duration = duration
        )
    }

    /**
     * 快速创建淡出动画
     */
    fun createFadeOutAnimation(duration: Long = 500): AlphaAnimation {
        return createAlphaAnimation(
            fromAlpha = 1f,
            toAlpha = 0f,
            duration = duration
        )
    }

    /**
     * 快速创建缩放进入动画
     */
    fun createScaleInAnimation(duration: Long = 500): ScaleAnimation {
        return createScaleAnimation(
            fromX = 0f,
            toX = 1f,
            fromY = 0f,
            toY = 1f,
            duration = duration
        )
    }
}
