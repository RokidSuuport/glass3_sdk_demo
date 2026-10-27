package com.rokid.phone.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.rokid.phone.R
import com.rokid.phone.adapter.AiVisionAdapter
import com.rokid.phone.base.BaseActivity
import com.rokid.phone.databinding.ActivityAiVisionBinding
import com.rokid.phone.utils.demoString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * AI 查验记录页：接收眼镜查验结果、持久化记录并导出巡检 PDF。
 * AI image-recognition page: receive glasses results, persist conversations, and export an inspection PDF.
 */
class AiVisionActivity : BaseActivity<ActivityAiVisionBinding>() {
    private sealed interface PendingAction {
        data object SaveOnly : PendingAction
        data object SaveAndOpen : PendingAction
    }

    private val recordMap = linkedMapOf<String, AiVisionRecord>()
    private val visibleRecords = mutableListOf<AiVisionRecord>()
    private lateinit var adapter: AiVisionAdapter
    private var currentReport: AiVisionPdfGenerator.Result? = null
    private var pendingAction: PendingAction = PendingAction.SaveOnly
    private var reportJob: Job? = null
    private var receiverRegistered = false
    private var selectedQuestionId: String? = null

    private val recordsUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadStoredRecords()
        }
    }

    private val createReportDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        val report = currentReport
        if (uri == null || report == null) return@registerForActivityResult
        lifecycleScope.launch {
            val copied = withContext(Dispatchers.IO) {
                copyReportToUri(report.file, uri)
            }
            if (!copied) {
                Toast.makeText(
                    this@AiVisionActivity,
                    demoString(R.string.demo_ai_vision_download_failed),
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            Toast.makeText(
                this@AiVisionActivity,
                demoString(R.string.demo_ai_vision_download_started),
                Toast.LENGTH_LONG,
            ).show()
            if (pendingAction == PendingAction.SaveAndOpen) {
                openReport(uri)
            }
        }
    }

    override fun initViewBinding(): ActivityAiVisionBinding =
        ActivityAiVisionBinding.inflate(layoutInflater)

    override fun onInit(savedInstanceState: Bundle?) {
        adapter = AiVisionAdapter(visibleRecords) { questionId ->
            selectRecord(questionId)
        }
        binding.recyclerRecords.layoutManager = LinearLayoutManager(this)
        binding.recyclerRecords.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.btnGenerateReport.setOnClickListener { generateReport(saveAndOpen = false) }
        binding.btnDownloadReport.setOnClickListener { generateReport(saveAndOpen = true) }
        loadStoredRecords()
    }

    override fun onResume() {
        super.onResume()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                recordsUpdatedReceiver,
                IntentFilter(AiVisionStore.ACTION_RECORDS_UPDATED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
    }

    override fun onPause() {
        if (receiverRegistered) {
            unregisterReceiver(recordsUpdatedReceiver)
            receiverRegistered = false
        }
        super.onPause()
    }

    override fun onDestroy() {
        reportJob?.cancel()
        super.onDestroy()
    }

    private fun loadStoredRecords() {
        recordMap.clear()
        val stored = AiVisionStore.load(this)
        val loaded = stored.filter {
            it.completed && it.questionId.isNotBlank() && it.imageUrl.isNotBlank()
        }
        loaded.forEach { record ->
            record.question = questionForDisplay(record.question)
            if (record.answer.isBlank() && record.completed) {
                record.answer = demoString(R.string.demo_ai_vision_no_answer)
            }
            updateGeneratedReportMetadata(record)
        }
        val deduplicated = AiVisionAnswerPolicy.deduplicateStoredRecords(loaded)
        deduplicated.forEach { recordMap[it.questionId] = it }
        if (compactSupersededCommercialResults() || stored.size != loaded.size) {
            AiVisionStore.save(this, recordMap.values.filter { it.completed })
        } else if (deduplicated.size != loaded.size) {
            AiVisionStore.save(this, recordMap.values.filter { it.completed })
        }
        publishRecords()
    }

    /**
     * 移除同批次已被巡检结论替代的商品价格记录。
     * Remove commercial price records that were replaced by inspection findings in the same capture batch.
     */
    private fun compactSupersededCommercialResults(): Boolean {
        val inspections = recordMap.values.filter(AiVisionAnswerPolicy::isStructuredInspection)
        if (inspections.isEmpty()) return false

        var changed = false
        val iterator = recordMap.entries.iterator()
        while (iterator.hasNext()) {
            val record = iterator.next().value
            if (inspections.any { AiVisionAnswerPolicy.supersedes(record, it) }) {
                iterator.remove()
                changed = true
            }
        }
        return changed
    }

    /**
     * 保存识别 Agent 生成的报告标题和摘要；旧记录缺少字段时从已有 AI 结果恢复。
     * Store the report title and summary generated by the recognition agent; recover them from existing AI output for legacy records.
     */
    private fun updateGeneratedReportMetadata(record: AiVisionRecord) {
        if (record.answer.isNotBlank() && !AiVisionAnswerPolicy.isReportReady(record)) {
            record.answer = AiVisionAnswerPolicy.formatSdkFallback(record.answer)
        }
        record.reportTitle = AiVisionAnswerPolicy.resolveReportTitle(record)
        record.reportSummary = AiVisionAnswerPolicy.resolveReportSummary(record)
    }

    private fun publishRecords() {
        visibleRecords.clear()
        visibleRecords.addAll(
            recordMap.values.filter { it.completed }.sortedByDescending { it.timestamp },
        )
        if (visibleRecords.none { it.questionId == selectedQuestionId }) {
            selectedQuestionId = null
        }
        adapter.submitRecords(visibleRecords, selectedQuestionId)
        binding.tvEmpty.visibility = if (visibleRecords.isEmpty()) View.VISIBLE else View.GONE
        updateReportButtons()
    }

    /**
     * 将眼镜端固定的巡检提示词显示为简洁业务名称。
     * Show the glasses inspection instructions as a concise business label.
     */
    private fun questionForDisplay(question: String): String {
        val isSdkInspectionPrompt =
            (question.contains("报告标题") && question.contains("整改建议")) ||
                (question.contains("Report title") && question.contains("Recommended actions"))
        return if (isSdkInspectionPrompt) {
            demoString(R.string.demo_ai_vision_industrial_question)
        } else {
            question
        }
    }

    /**
     * 单选一条记录作为报告数据源；再次点击取消选择。
     * Select one record as the report source; click it again to clear the selection.
     */
    private fun selectRecord(questionId: String) {
        selectedQuestionId = if (selectedQuestionId == questionId) null else questionId
        invalidateReport()
        publishRecords()
    }

    private fun selectedRecord(): AiVisionRecord? =
        visibleRecords.firstOrNull { it.questionId == selectedQuestionId }

    private fun updateReportButtons() {
        val selected = selectedRecord()
        binding.btnGenerateReport.isEnabled =
            selected != null &&
            AiVisionAnswerPolicy.isReportReady(selected) &&
            reportJob?.isActive != true
        binding.btnDownloadReport.isEnabled =
            currentReport != null && reportJob?.isActive != true
    }

    private fun invalidateReport() {
        reportJob?.cancel()
        reportJob = null
        currentReport = null
        if (::adapter.isInitialized) updateReportButtons()
    }

    private fun generateReport(saveAndOpen: Boolean) {
        val selected = selectedRecord()
        if (selected == null) {
            Toast.makeText(
                this,
                demoString(R.string.demo_ai_vision_select_record),
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        if (!AiVisionAnswerPolicy.isReportReady(selected)) {
            Toast.makeText(
                this,
                demoString(R.string.demo_ai_vision_report_empty),
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val records = listOf(selected)
        reportJob?.cancel()
        pendingAction = if (saveAndOpen) PendingAction.SaveAndOpen else PendingAction.SaveOnly
        reportJob = lifecycleScope.launch {
            binding.btnGenerateReport.isEnabled = false
            binding.btnDownloadReport.isEnabled = false
            Toast.makeText(
                this@AiVisionActivity,
                demoString(R.string.demo_ai_vision_generating),
                Toast.LENGTH_SHORT,
            ).show()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    AiVisionPdfGenerator.generate(this@AiVisionActivity, records)
                }
            }
            result.onSuccess { report ->
                currentReport = report
                Toast.makeText(
                    this@AiVisionActivity,
                    demoString(R.string.demo_ai_vision_report_ready, report.displayName),
                    Toast.LENGTH_LONG,
                ).show()
                binding.btnDownloadReport.isEnabled = true
                if (saveAndOpen) {
                    createReportDocument.launch(report.displayName)
                }
            }.onFailure { error ->
                Toast.makeText(
                    this@AiVisionActivity,
                    demoString(
                        R.string.demo_ai_vision_report_failed,
                        error.message.orEmpty(),
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
            binding.btnGenerateReport.isEnabled =
                selectedRecord()?.let(AiVisionAnswerPolicy::isReportReady) == true
            binding.btnDownloadReport.isEnabled = currentReport != null
        }
    }

    private fun copyReportToUri(source: File, target: Uri): Boolean {
        return runCatching {
            contentResolver.openOutputStream(target)?.use { output ->
                FileInputStream(source).use { input -> input.copyTo(output) }
            } ?: return false
            true
        }.getOrDefault(false)
    }

    private fun openReport(uri: Uri) {
        runCatching {
            startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
        }.onFailure {
            Toast.makeText(
                this,
                demoString(R.string.demo_ai_vision_download_started),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}
