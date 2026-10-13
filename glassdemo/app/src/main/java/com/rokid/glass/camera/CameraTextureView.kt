package com.rokid.glass.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import kotlin.math.roundToInt

/**
 * TextureView.onSizeChanged 会把 SurfaceTexture 默认缓冲改成控件尺寸。状态文字/按钮布局变化
 * 不能改变已经交给相机的缓冲契约，否则相机画面会被裁切/拉伸。旧 GL 渲染器持有独立纹理，
 * 不受这个行为影响；本类在布局变化后恢复相机缓冲，并让控件本身按实际画面宽高比测量。
 * 不先铺满任意宽高的控件再依赖 setTransform 补偿，预览及取得的位图都保持相同比例。
 */
internal class CameraTextureView(context: Context) : TextureView(context) {
    private data class BufferSize(val texture: SurfaceTexture, val width: Int, val height: Int)
    private val bufferLock = Any()
    private var cameraBuffer: BufferSize? = null
    private var imageWidth = 0
    private var imageHeight = 0

    /** Main-thread display geometry; independent of the producer's immutable buffer size. */
    fun setPreviewAspectRatio(width: Int, height: Int) {
        require(width > 0 && height > 0)
        if (imageWidth == width && imageHeight == height) return
        imageWidth = width
        imageHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (imageWidth <= 0 || imageHeight <= 0 || measuredWidth <= 0 || measuredHeight <= 0) return
        val scale = minOf(measuredWidth.toDouble() / imageWidth, measuredHeight.toDouble() / imageHeight)
        setMeasuredDimension((imageWidth * scale).roundToInt().coerceIn(1, measuredWidth),
            (imageHeight * scale).roundToInt().coerceIn(1, measuredHeight))
    }

    fun setCameraBufferSize(width: Int, height: Int, texture: SurfaceTexture = checkNotNull(surfaceTexture)) {
        require(width > 0 && height > 0)
        synchronized(bufferLock) {
            cameraBuffer = BufferSize(texture, width, height)
            texture.setDefaultBufferSize(width, height)
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        synchronized(bufferLock) {
            cameraBuffer?.let { buffer ->
                // Do not let a resize restore an old size after the control thread installs a new one.
                // A destroyed texture's contract must never be applied to its replacement.
                if (surfaceTexture === buffer.texture) buffer.texture.setDefaultBufferSize(buffer.width, buffer.height)
            }
        }
    }
}
