package com.rokid.phone.system.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class CustomProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 进度条背景画笔
    // Paint for the progress bar background.
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // 背景灰色
        // Gray background.
        color = Color.parseColor("#E8E8E8")
        style = Paint.Style.FILL
    }
    // 进度条画笔
    // Paint for the progress bar.
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // 进度条颜色
        // Progress bar color.
        color = Color.parseColor("#5CEBAA")
        style = Paint.Style.FILL
    }
    // 文本画笔（绘制进度数值、标记等）
    // Text paint for progress values, markers, and similar labels.
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 40f
        textAlign = Paint.Align.CENTER
    }

    // 当前进度，范围 0-100
    // Current progress, from 0 to 100.
    var progress = 0f
        set(value) {
            field = value.coerceIn(0f, 100f)
            // 触发重绘
            // Request a redraw.
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. 绘制进度条背景
        // 1. Draw the progress bar background.
        val bgRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(bgRect, height / 2f, height / 2f, bgPaint)

        // 2. 绘制进度条
        // 2. Draw the progress bar.
        val progressRect = RectF(0f, 0f, width * (progress / 100), height.toFloat())
        canvas.drawRoundRect(progressRect, height / 2f, height / 2f, progressPaint)

        // 3. 绘制指示标记（如进度数值、小圆圈等，根据需求调整）
        // 3. Draw an indicator, such as a progress value or small circle, as needed.
        val indicatorX = width * (progress / 100)
        // 画个小圆圈示例
        // Example: draw a small circle.
        canvas.drawCircle(indicatorX, height / 2f, height / 2f, progressPaint)
        // 绘制进度文本，比如“90”
        // Draw progress text, such as "90".
        val textY = height / 2f + (textPaint.descent() + textPaint.ascent()) / -2
    }
}
