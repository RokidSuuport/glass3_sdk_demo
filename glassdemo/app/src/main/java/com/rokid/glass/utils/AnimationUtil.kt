package com.rokid.glass.utils

import android.animation.Animator
import android.animation.ObjectAnimator
import android.view.View
import android.widget.ImageView

/**
 * Author: zhangshengwei
 * Date: 2025/7/18
 */
object AnimationUtil {


    fun fadeOutImageView(imageView: ImageView) {
        // 创建淡出动画：alpha从1.0f（不透明）变为0.0f（透明）
        val fadeOut = ObjectAnimator.ofFloat(
            imageView,
            "alpha",
            1.0f, 0.0f
        ).apply {
            duration = 300 // 动画时长，单位：毫秒（500ms即0.5秒）
            // 动画结束后可执行额外操作（如隐藏视图）
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) {}
                override fun onAnimationEnd(animation: Animator) {
                    imageView.visibility = View.INVISIBLE // 动画结束后隐藏
                    // 可选：重置alpha为1.0f，避免下次显示时透明
                    imageView.alpha = 0.0f
                }

                override fun onAnimationCancel(animation: Animator) {}
                override fun onAnimationRepeat(animation: Animator) {}
            })
        }
        fadeOut.start() // 启动动画
    }


    fun fadeInImageView(imageView: ImageView) {
        // 创建淡出动画：alpha从1.0f（不透明）变为0.0f（透明）
        imageView.visibility = View.VISIBLE
        val fadeOut = ObjectAnimator.ofFloat(imageView, "alpha", 0.0f, 1.0f).apply {
            duration = 1000 // 动画时长，单位：毫秒（500ms即0.5秒）
            // 动画结束后可执行额外操作（如隐藏视图）
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) {
                    imageView.visibility = View.VISIBLE
                    // 可选：重置alpha为1.0f，避免下次显示时透明
                    imageView.alpha = 0.0f
                }

                override fun onAnimationEnd(animation: Animator) {
                    imageView.visibility = View.VISIBLE
                    // 可选：重置alpha为1.0f，避免下次显示时透明
                    imageView.alpha = 1.0f
                }

                override fun onAnimationCancel(animation: Animator) {}
                override fun onAnimationRepeat(animation: Animator) {}
            })
        }
        fadeOut.start() // 启动动画
    }
}