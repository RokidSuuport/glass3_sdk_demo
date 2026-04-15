package com.rokid.phone.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.rokid.phone.R

class WorkHomeAdapter(
    private val context: Context,
    private val itemWidth: Int
) : RecyclerView.Adapter<WorkHomeAdapter.ViewHolder>() {

    private val data = List(12) { "Item $it" }  // 假数据

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_work_home, parent, false)
        view.layoutParams.width = itemWidth  // 设置宽度，保证每页 3 个
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.textView.text = data[position]
    }

    override fun getItemCount(): Int = data.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textView: TextView = itemView.findViewById(R.id.itemText)
    }
}