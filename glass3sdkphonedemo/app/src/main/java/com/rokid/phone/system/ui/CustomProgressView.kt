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
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8E8E8") // 背景灰色
        style = Paint.Style.FILL
    }
    // 进度条画笔
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5CEBAA") // 进度条颜色
        style = Paint.Style.FILL
    }
    // 文本画笔（绘制进度数值、标记等）
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 40f
        textAlign = Paint.Align.CENTER
    }

    // 当前进度，范围 0-100
    var progress = 0f
        set(value) {
            field = value.coerceIn(0f, 100f)
            invalidate() // 触发重绘
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. 绘制进度条背景
        val bgRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(bgRect, height / 2f, height / 2f, bgPaint)

        // 2. 绘制进度条
        val progressRect = RectF(0f, 0f, width * (progress / 100), height.toFloat())
        canvas.drawRoundRect(progressRect, height / 2f, height / 2f, progressPaint)

        // 3. 绘制指示标记（如进度数值、小圆圈等，根据需求调整）
        val indicatorX = width * (progress / 100)
        // 画个小圆圈示例
        canvas.drawCircle(indicatorX, height / 2f, height / 2f, progressPaint)
        // 绘制进度文本，比如“90”
        val textY = height / 2f + (textPaint.descent() + textPaint.ascent()) / -2
//        canvas.drawText("${progress.toInt()}", indicatorX, textY, textPaint)
    }
}
