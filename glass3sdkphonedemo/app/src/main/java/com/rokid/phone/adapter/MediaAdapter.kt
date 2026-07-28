package com.rokid.phone.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.rokid.phone.R
import java.io.File

class MediaAdapter(
    private val mediaList: List<File>,
    private val onItemClick: (File) -> Unit   // 新增点击回调
) : RecyclerView.Adapter<MediaAdapter.MediaViewHolder>() {
    inner class MediaViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val thumbnail: ImageView = itemView.findViewById(R.id.thumbnail)
        val videoTag: ImageView = itemView.findViewById(R.id.video_tag)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false)
        return MediaViewHolder(view)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        val file = mediaList[position]
        val context = holder.itemView.context

        if (isVideo(file)) {
            holder.videoTag.visibility = View.VISIBLE
        } else {
            holder.videoTag.visibility = View.GONE
        }
        // 图片或视频缩略图
        Glide.with(context).load(file).thumbnail(0.1f).into(holder.thumbnail)
        // 点击事件
        holder.itemView.setOnClickListener {
            onItemClick(file)
        }
    }

    private fun isVideo(file: File): Boolean {
        val name = file.name.lowercase()
        return name.endsWith(".mp4") || name.endsWith(".avi") || name.endsWith(".mov")
    }

    override fun getItemCount(): Int = mediaList.size
}