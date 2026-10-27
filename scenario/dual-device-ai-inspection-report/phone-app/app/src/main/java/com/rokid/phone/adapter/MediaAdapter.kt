package com.rokid.phone.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.rokid.phone.databinding.ItemMediaBinding
import java.io.File

class MediaAdapter(
    private val files: List<File>,
    private val onClick: (File) -> Unit,
) : RecyclerView.Adapter<MediaAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemMediaBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val file = files[position]
        holder.binding.tvType.text = file.extension.uppercase()
        Glide.with(holder.binding.ivMedia)
            .load(file)
            .centerCrop()
            .into(holder.binding.ivMedia)
        holder.binding.root.setOnClickListener { onClick(file) }
    }

    override fun getItemCount(): Int = files.size

    class Holder(val binding: ItemMediaBinding) : RecyclerView.ViewHolder(binding.root)
}
