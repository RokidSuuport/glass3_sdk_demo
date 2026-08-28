package com.rokid.phone.utils

import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FrameAnimationUtil internal constructor(
    private val imageView: ImageView,
    private val isOneShot: Boolean = false
) {

    private val TAG = "FrameAnimationUtil::"
    private var animationDrawable: AnimationDrawable? = null
    private val isRunning: Boolean
        get() = animationDrawable?.isRunning == true

    private var loadJob: Job? = null
    private val drawableCache = mutableMapOf<Int, Drawable?>()

    fun initFromFrames(
        frameList: List<Pair<Int, Int>>,
        lifecycleScope: LifecycleCoroutineScope
    ) {
        loadJob?.cancel()
        release()

        if (frameList.isEmpty()) {
            return
        }

        loadJob = lifecycleScope.launch(Dispatchers.IO) {
            val frames = mutableListOf<Pair<Drawable, Int>>()
            frameList.forEach { (drawableRes, duration) ->
                val drawable = drawableCache[drawableRes]
                    ?: ContextCompat.getDrawable(imageView.context, drawableRes)
                        ?.also { drawableCache[drawableRes] = it }

                drawable?.let {
                    frames.add(Pair(it, duration))
                } ?: run {
                    Log.e("FrameAnim", "Invalid drawable resource: $drawableRes")
                }
            }

            withContext(Dispatchers.Main) {
                if (frames.isNotEmpty()) {
                    animationDrawable = AnimationDrawable().apply {
                        this.isOneShot = isOneShot
                        frames.forEach { (drawable, duration) ->
                            addFrame(drawable, duration)
                        }
                    }
                    imageView.setImageDrawable(animationDrawable)
                    start()
                }
            }
        }
    }

    fun initFromDrawables(frameList: List<Pair<Drawable, Int>>) {
        release()

        animationDrawable = AnimationDrawable().apply {
            this.isOneShot = isOneShot
            frameList.forEach { (drawable, duration) ->
                addFrame(drawable, duration)
            }
        }
        imageView.setImageDrawable(animationDrawable)
    }

    fun start() {
        Log.d(TAG, "start->$isRunning")
        if (!isRunning) {
            imageView.post {
                // 关键修复：确保动画Drawable仍被ImageView引用
                if (imageView.drawable !== animationDrawable) {
                    imageView.setImageDrawable(animationDrawable)
                }
                animationDrawable?.start()
            }
        }
    }

    fun stop() {
        if (isRunning) {
            animationDrawable?.stop()
            // 关键修复：保留动画Drawable，仅重置到第一帧
            animationDrawable?.let {
                if (it.numberOfFrames > 0) {
                    it.selectDrawable(0) // 内部重置帧位置，不替换Drawable
                }
            }
        }
    }

    fun release() {
        stop()
        imageView.setImageDrawable(null)
        animationDrawable?.callback = null
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
//            animationDrawable?.unscheduleSelf(null)
//        }
        animationDrawable = null
        loadJob?.cancel()
        drawableCache.clear()
    }

    companion object {
        fun create(imageView: ImageView, isOneShot: Boolean = false): FrameAnimationUtil {
            return FrameAnimationUtil(imageView, isOneShot)
        }
    }
}
