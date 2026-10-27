package com.rokid.phone.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.rokid.phone.R
import com.rokid.phone.ai.AiVisionRecord
import com.rokid.phone.ai.resolveAiVisionImageUri
import com.rokid.phone.databinding.ItemAiVisionRecordBinding
import com.rokid.phone.utils.demoString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 展示识图问题、AI 结果和时间。
 * Display recognition questions, AI results, and timestamps.
 */
class AiVisionAdapter(
    records: List<AiVisionRecord> = emptyList(),
    private val onRecordClick: (String) -> Unit = {},
) : RecyclerView.Adapter<AiVisionAdapter.RecordViewHolder>() {
    private val items = records.toMutableList()
    private var selectedQuestionId: String? = null

    fun submitRecords(records: List<AiVisionRecord>, selectedId: String?) {
        val oldSize = items.size
        items.clear()
        items.addAll(records)
        selectedQuestionId = selectedId
        if (oldSize > 0) notifyItemRangeRemoved(0, oldSize)
        if (items.isNotEmpty()) notifyItemRangeInserted(0, items.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordViewHolder {
        val binding = ItemAiVisionRecordBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return RecordViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RecordViewHolder, position: Int) {
        val record = items[position]
        holder.bind(record, record.questionId == selectedQuestionId) {
            onRecordClick(record.questionId)
        }
    }

    override fun getItemCount(): Int = items.size

    class RecordViewHolder(
        private val binding: ItemAiVisionRecordBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        fun bind(record: AiVisionRecord, selected: Boolean, onClick: () -> Unit) {
            val context = binding.root.context
            binding.root.isSelected = selected
            binding.root.setBackgroundResource(
                if (selected) {
                    R.drawable.bg_ai_vision_record_selected
                } else {
                    R.drawable.rounded_corner_bg
                },
            )
            binding.root.setOnClickListener { onClick() }
            binding.tvTime.text = dateFormat.format(Date(record.timestamp))
            binding.tvQuestion.text = context.getString(
                R.string.demo_ai_vision_question,
                record.question.ifBlank {
                    demoString(R.string.demo_ai_vision_unknown_question)
                },
            )
            binding.tvAnswer.text = record.answer.ifBlank {
                demoString(R.string.demo_ai_vision_waiting)
            }

            val imageUri = resolveAiVisionImageUri(record.imageUrl)
            if (imageUri == null) {
                binding.ivImage.visibility = View.GONE
                Glide.with(binding.ivImage).clear(binding.ivImage)
            } else {
                binding.ivImage.visibility = View.VISIBLE
                Glide.with(binding.ivImage)
                    .load(imageUri)
                    .centerCrop()
                    .into(binding.ivImage)
            }
        }
    }
}
