package com.rokid.phone.notification.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.kyleduo.switchbutton.SwitchButton
import com.rokid.phone.R

class ApplicationAdapter(
    private var items: List<AppListItem>,
    private val onSwitchChecked: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_APP = 1
    }

    inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvHeader: TextView = view.findViewById(R.id.tvHeader)
        fun bind(header: String) {
            tvHeader.text = header
        }
    }

    inner class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val appName: TextView = view.findViewById(R.id.appName)
        val appImage: ImageView = view.findViewById(R.id.appImage)
        val switchBox: SwitchButton = view.findViewById(R.id.switchBox)

        fun bind(appInfo: AppInfo) {
            appName.text = appInfo.name
            appImage.setImageDrawable(appInfo.icon)

            switchBox.setOnCheckedChangeListener(null)
            switchBox.isChecked = appInfo.isCheck
            switchBox.setOnCheckedChangeListener { _, isChecked ->
                appInfo.isCheck = isChecked
                onSwitchChecked()
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is AppListItem.Header -> TYPE_HEADER
            is AppListItem.App -> TYPE_APP
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.app_item_header, parent, false)
            HeaderViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.app_item, parent, false)
            AppViewHolder(view)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is AppListItem.Header -> (holder as HeaderViewHolder).bind(item.letter)
            is AppListItem.App -> {
                (holder as AppViewHolder).bind(item.info)

                val currentLetter = item.info.firstLetter
                val prevLetter = (items.getOrNull(position - 1) as? AppListItem.App)?.info?.firstLetter
                val nextLetter = (items.getOrNull(position + 1) as? AppListItem.App)?.info?.firstLetter

                val backgroundRes = when {
                    prevLetter != currentLetter && nextLetter != currentLetter -> R.drawable.app_list_bg // 只有一个 item
                    prevLetter != currentLetter -> R.drawable.app_list_top     // 分组起始
                    nextLetter != currentLetter -> R.drawable.app_list_bottom  // 分组结尾
                    else -> R.drawable.app_list_middle                         // 分组中间
                }
                holder.itemView.setBackgroundResource(backgroundRes)
            }
        }
    }

    fun getAllNotificationAppList(): List<String> {
        return items.filterIsInstance<AppListItem.App>()
            .filter { it.info.isCheck }
            .map { it.info.packageName }
    }

    fun setEnableAllNotification(allEnable: Boolean) {
        items.forEach {
            if (it is AppListItem.App) it.info.isCheck = allEnable
        }
        notifyDataSetChanged()
    }

    fun getIndexMap(): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        for ((index, item) in items.withIndex()) {
            if (item is AppListItem.Header) {
                map[item.letter] = index
            }
        }
        return map
    }

    fun updateData(newItems: List<AppListItem>) {
        this.items = newItems
        notifyDataSetChanged()
    }

    fun getPositionForSection(section: Char): Int {
        for (i in items.indices) {
            val item = items[i]
            if (item is AppListItem.Header && item.letter.firstOrNull()?.uppercaseChar() == section.uppercaseChar()) {
                return i
            }
        }
        return -1
    }
}
