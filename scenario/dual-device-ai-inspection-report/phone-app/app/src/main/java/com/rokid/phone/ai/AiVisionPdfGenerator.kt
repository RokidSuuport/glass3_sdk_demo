package com.rokid.phone.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.rokid.phone.R
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * 根据识图对话生成可分页的巡检 PDF 报告。
 * Generate a paginated inspection PDF report from image-recognition conversations.
 */
object AiVisionPdfGenerator {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 36f
    private const val CONTENT_WIDTH = PAGE_WIDTH - MARGIN * 2
    private const val PAGE_BOTTOM = PAGE_HEIGHT - MARGIN

    data class Result(
        val file: File,
        val displayName: String,
    )

    fun generate(
        context: Context,
        records: List<AiVisionRecord>,
        generatedAt: Long = System.currentTimeMillis(),
    ): Result {
        require(records.isNotEmpty()) { "No recognition records" }
        val document = PdfDocument()
        var pageNumber = 1
        var page = document.startPage(
            PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create(),
        )
        var canvas = page.canvas
        var y = MARGIN

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(24, 34, 48)
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(38, 48, 60)
            textSize = 11f
        }
        val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(92, 104, 118)
            textSize = 9f
        }
        val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(38, 48, 60)
            textSize = 11f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val dividerPaint = Paint().apply {
            color = Color.rgb(218, 224, 230)
            strokeWidth = 1f
        }

        fun startNewPage() {
            document.finishPage(page)
            pageNumber++
            page = document.startPage(
                PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create(),
            )
            canvas = page.canvas
            y = MARGIN
        }

        fun ensureSpace(height: Float) {
            if (y + height > PAGE_BOTTOM) startNewPage()
        }

        fun drawParagraph(text: String, paint: Paint, spacingAfter: Float = 6f) {
            val lineHeight = paint.textSize * 1.45f
            text.ifBlank { " " }.lineSequence().forEach { sourceLine ->
                var remaining = sourceLine
                if (remaining.isEmpty()) {
                    ensureSpace(lineHeight)
                    y += lineHeight
                    return@forEach
                }
                while (remaining.isNotEmpty()) {
                    val count = max(1, paint.breakText(remaining, true, CONTENT_WIDTH, null))
                    ensureSpace(lineHeight)
                    canvas.drawText(remaining.substring(0, count), MARGIN, y + paint.textSize, paint)
                    y += lineHeight
                    remaining = remaining.substring(count)
                }
            }
            y += spacingAfter
        }

        fun drawImage(record: AiVisionRecord) {
            val bitmap = loadThumbnail(context, record, 250, 150) ?: return
            ensureSpace(bitmap.height + 10f)
            canvas.drawBitmap(bitmap, MARGIN, y, null)
            y += bitmap.height + 10f
            bitmap.recycle()
        }

        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val orderedRecords = records.sortedBy { it.timestamp }
        val reportTitle = orderedRecords.asReversed()
            .asSequence()
            .map(AiVisionAnswerPolicy::resolveReportTitle)
            .firstOrNull(String::isNotBlank)
            ?: context.getString(
                R.string.demo_ai_vision_report_title,
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(generatedAt)),
            )
        drawParagraph(reportTitle, titlePaint, 8f)
        canvas.drawText(
            context.getString(
                R.string.demo_ai_vision_report_generated_at,
                formatter.format(Date(generatedAt)),
            ),
            MARGIN,
            y + smallPaint.textSize,
            smallPaint,
        )
        y += smallPaint.textSize + 12f
        val reportSummary = orderedRecords.mapNotNull { record ->
            AiVisionAnswerPolicy.resolveReportSummary(record).takeIf { it.isNotBlank() }
        }.distinct().joinToString("\n")
        if (reportSummary.isNotBlank()) {
            drawParagraph(
                context.getString(R.string.demo_ai_vision_report_summary, reportSummary),
                bodyPaint,
                4f,
            )
        }
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, dividerPaint)
        y += 18f

        orderedRecords.forEach { record ->
            ensureSpace(70f)
            drawParagraph(
                context.getString(
                    R.string.demo_ai_vision_report_time,
                    formatter.format(Date(record.timestamp)),
                ),
                smallPaint,
                3f,
            )
            drawImage(record)
            drawParagraph(
                context.getString(
                    R.string.demo_ai_vision_report_question,
                    record.question.ifBlank {
                        context.getString(R.string.demo_ai_vision_unknown_question)
                    },
                ),
                bodyPaint,
                4f,
            )
            drawParagraph(
                context.getString(
                    R.string.demo_ai_vision_report_answer,
                    record.answer.ifBlank {
                        context.getString(R.string.demo_ai_vision_no_answer)
                    },
                ),
                bodyPaint,
                8f,
            )
            ensureSpace(10f)
            canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, dividerPaint)
            y += 16f
        }
        document.finishPage(page)

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date(generatedAt))
        val directory = File(context.cacheDir, "ai-vision-reports").apply { mkdirs() }
        val file = File(directory, "ai_vision_report_$stamp.pdf")
        FileOutputStream(file).use(document::writeTo)
        document.close()
        val displayName = context.getString(R.string.demo_ai_vision_report_file_name, stamp)
        return Result(file, displayName)
    }

    private fun loadThumbnail(
        context: Context,
        record: AiVisionRecord,
        maxWidth: Int,
        maxHeight: Int,
    ): Bitmap? {
        val uri = resolveAiVisionImageUri(record.imageUrl) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        }.getOrNull()
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth &&
            bounds.outHeight / (sample * 2) >= maxHeight
        ) {
            sample *= 2
        }
        val decoded = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(
                    it,
                    null,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }
        }.getOrNull() ?: return null
        val scale = minOf(
            maxWidth.toFloat() / decoded.width,
            maxHeight.toFloat() / decoded.height,
            1f,
        )
        if (scale >= 1f) return decoded
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }
}
