package com.rokid.security.phone.sdk.template.feature.notification.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class SideIndexView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val indexList = ('A'..'Z').map { it.toString() }
    private val paint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        textSize = 14f * resources.displayMetrics.density
        color = Color.DKGRAY
    }

    private var onIndexTouchListener: ((String) -> Unit)? = null

    fun setOnIndexTouchListener(listener: (String) -> Unit) {
        onIndexTouchListener = listener
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val itemHeight = height / indexList.size.toFloat()
        indexList.forEachIndexed { index, letter ->
            val x = width / 2f
            val y = itemHeight * index + itemHeight / 2f - (paint.descent() + paint.ascent()) / 2
            canvas.drawText(letter, x, y, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val itemHeight = height / indexList.size.toFloat()
        val index = (event.y / itemHeight).toInt().coerceIn(0, indexList.size - 1)
        val letter = indexList[index]
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                onIndexTouchListener?.invoke(letter)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
