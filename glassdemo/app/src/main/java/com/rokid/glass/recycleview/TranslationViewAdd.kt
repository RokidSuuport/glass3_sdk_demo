package com.rokid.glass.recycleview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import com.rokid.glesse.R


class TranslationViewAdd @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class LineInfo(val text: String, val originalIndex: Int)
    data class TranslationItem(var text: String, var isFinished: Boolean = false, var displayedLength: Int = 0)

    private val lineInfos = mutableListOf<LineInfo>()
    private val translations = mutableListOf<TranslationItem>()
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var textSize = 18f
    private val interSentenceSpacing = 1.5f
    private var lineHeight = 0f
    private var maxLineWidth = 0f
    private var baselineOffset = 0f

    private val TAG = "TranslationView::"

    var allowLineBreak = false
        set(value) {
            field = value
            rebuildLines()
            invalidate()
        }

    private val flowInterval = 50L // 每50ms显示一个字符
    private val flowRunnable = object : Runnable {
        override fun run() {
            var needInvalidate = false
            translations.forEach { item ->
                if (item.displayedLength < item.text.length) {
                    item.displayedLength++
                    needInvalidate = true
                }
            }
            if (needInvalidate) {
                rebuildLines()
                invalidate()
                postDelayed(this, flowInterval)
            }
        }
    }

    init {
        paint.color = ContextCompat.getColor(context, R.color.green)
        paint.textSize = textSize
        lineHeight = paint.fontSpacing
        baselineOffset = -paint.fontMetrics.top

        // 启动滚动输出
        post(flowRunnable)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        maxLineWidth = w.toFloat() - 32f
        rebuildLines()
        invalidate()
    }

    private fun rebuildLines() {
        lineInfos.clear()
        translations.forEachIndexed { index, item ->
            val textToShow = item.text.substring(0, item.displayedLength.coerceAtMost(item.text.length))
            if (allowLineBreak) {
                var remainingText = textToShow
                while (remainingText.isNotEmpty()) {
                    val lineWidth = paint.breakText(remainingText, true, maxLineWidth, null)
                    val lineText = remainingText.substring(0, lineWidth)
                    lineInfos.add(LineInfo(lineText, index))
                    remainingText = remainingText.substring(lineWidth)
                }
            } else {
                lineInfos.add(LineInfo(textToShow, index))
            }
        }
    }

    // 接口保持不变
//    fun appendToCurrentTranslation(textToAdd: String) {
//        Log.d(TAG, "appendToCurrentTranslation textToAdd:$textToAdd")
//        if (translations.isEmpty() || translations.last().isFinished) {
//            startNewTranslation(textToAdd)
//        } else {
//            val currentItem = translations.last()
//            currentItem.text += textToAdd
//        }
//    }
    fun appendToCurrentTranslation(textToAdd: String) {
        Log.d(TAG, "appendToCurrentTranslation textToAdd:$textToAdd")
        if (translations.isEmpty() || translations.last().isFinished) {
            startNewTranslation(textToAdd)
        } else {
            val currentItem = translations.last()
            currentItem.text += textToAdd
        }

        // 如果 displayedLength 小于 text 长度，保证滚动输出继续
        translations.lastOrNull()?.let { item ->
            if (item.displayedLength < item.text.length) {
                // 触发 flowRunnable 刷新显示
                post(flowRunnable)
            }
        }
    }

    fun startNewTranslation(initialText: String = "") {
        Log.d(TAG, "startNewTranslation initialText:$initialText")
        if (translations.isNotEmpty() && !translations.last().isFinished) {
            translations.last().isFinished = true
        }
        translations.add(TranslationItem(initialText))
    }

    fun updateCurrentTranslation(newText: String) {
        Log.d(TAG, "updateCurrentTranslation newText:$newText")
        if (translations.isEmpty()) {
            startNewTranslation(newText)
            return
        }
        val lastItem = translations.last()
        if (lastItem.isFinished) {
            startNewTranslation(newText)
        } else {
            lastItem.text = newText
        }
    }

    fun finishCurrentTranslation() {
        if (translations.isEmpty()) return
        val lastItem = translations.last()
        if (!lastItem.isFinished) {
            lastItem.isFinished = true
        }
    }

    fun addTranslation(translation: String) {
        startNewTranslation(translation)
        finishCurrentTranslation()
    }

    fun clearAll() {
        translations.clear()
        lineInfos.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (lineInfos.isEmpty()) return

        var currentY = height.toFloat()
        var prevOriginalIndex = -1

        val maxAlpha = 255
        val minAlpha = 135

        for (i in lineInfos.indices.reversed()) {
            val lineInfo = lineInfos[i]
            val sentenceIndex = lineInfo.originalIndex

            if (prevOriginalIndex != -1 && prevOriginalIndex != sentenceIndex) {
                currentY -= lineHeight * interSentenceSpacing
                if (currentY < 0) break
            }

            currentY -= lineHeight
            if (currentY + lineHeight < 0) break

            val positionFactor = 1f - (currentY / height)
            val alpha = ((maxAlpha - (maxAlpha - minAlpha) * positionFactor)).toInt()
            paint.alpha = alpha

            val baselineY = currentY + baselineOffset - 5
            if (baselineY > 0) {
                canvas.drawText(lineInfo.text, 16f, baselineY, paint)
            }

            prevOriginalIndex = sentenceIndex
        }
    }
}