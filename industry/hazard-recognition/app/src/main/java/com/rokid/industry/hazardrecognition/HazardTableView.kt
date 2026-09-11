package com.rokid.industry.hazardrecognition

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 用例：每次成功识别调用 render(answer)，显示“字段名在左、内容在右”的纵向表格。
 * 10 秒到期由 Activity 调用 render(null) 恢复等待态；本控件不管理计时和历史记录。
 */
class HazardTableView(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private val rows = LinearLayout(context).apply { orientation = VERTICAL }
    private val scroll = ScrollView(context).apply { isFocusable = false; addView(rows) }
    init {
        orientation = VERTICAL
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        render(null)
    }

    fun render(answer: InspectionResult?) {
        rows.removeAllViews() // 整表替换；不要对旧结果继续追加行。
        val hazards = answer?.hazards.orEmpty()
        // 用例：同一批发现多项隐患时，各字段使用一致的 1、2、3 编号，便于对应阅读。
        fun values(value: (Hazard) -> String): String = hazards.mapIndexed { index, hazard ->
            if (hazards.size > 1) "${index + 1}. ${value(hazard)}" else value(hazard)
        }.joinToString("\n\n")
        val content = when {
            hazards.isNotEmpty() -> values { "${it.target}：${it.content}" }
            answer?.risk == Risk.CLEAR -> "未见明显消防隐患"
            answer?.risk == Risk.UNKNOWN -> answer.summary
            else -> "等待识别结果"
        }
        rows.addView(row(listOf("隐患内容", content)))
        rows.addView(row(listOf("法规依据", if (hazards.isEmpty()) "—"
            else values { RegulationCatalog.reference(it.category) })))
        rows.addView(row(listOf("整改建议", if (hazards.isEmpty()) "—" else values { it.advice })))
        scroll.post { scroll.scrollTo(0, 0) }
    }

    // 用例：眼镜左右滑动传 -1 / 1，每次翻动约 2/3 屏，保留部分上下文。
    fun page(direction: Int) = scroll.smoothScrollBy(0, direction * scroll.height * 2 / 3)

    private fun row(values: List<String>): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.TOP
        isBaselineAligned = false
        val weights = floatArrayOf(0.26f, 0.74f) // 字段名占 26%，内容占 74%；小屏可从这里调整比例。
        values.forEachIndexed { index, value ->
            addView(TextView(context).apply {
                text = value
                textSize = 12f
                setTextColor(Color.WHITE)
                if (index == 0) setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(6), dp(8), dp(6), dp(8))
                setLineSpacing(dp(2).toFloat(), 1f)
                background = GradientDrawable().apply {
                    setColor(if (index == 0) Color.rgb(28, 28, 28) else Color.BLACK)
                    setStroke(1, Color.DKGRAY)
                }
            }, LayoutParams(0, LayoutParams.MATCH_PARENT, weights[index]))
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
