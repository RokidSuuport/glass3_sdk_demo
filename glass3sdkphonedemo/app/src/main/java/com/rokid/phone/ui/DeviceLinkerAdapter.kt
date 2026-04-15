package com.rokid.phone.ui

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.rokid.phone.R


// 列表项基类
sealed class ListItem {
      data class GroupHeader(val title: String) : ListItem()
    data class DeviceItem(val name: String, var isConnected: Boolean, var isConnecting: Boolean = false) : ListItem()
}

interface OnItemClickListener {
    fun onConnectClick(device: ListItem.DeviceItem, position: Int)
}

class DeviceLinkerAdapter(private var itemList: MutableList<ListItem> = mutableListOf()) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_GROUP_HEADER = 0
        private const val TYPE_DEVICE_ITEM = 1
    }

    private var selectedPosition = -1 // 记录当前选中位置
    var onItemClickListener: OnItemClickListener? = null

    override fun getItemViewType(position: Int): Int {
        return when (itemList[position]) {
            is ListItem.GroupHeader -> TYPE_GROUP_HEADER
            is ListItem.DeviceItem -> TYPE_DEVICE_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_GROUP_HEADER -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_group_header, parent, false)
                GroupHeaderViewHolder(view)
            }
            TYPE_DEVICE_ITEM -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_other_link_device, parent, false)
                DeviceViewHolder(view)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = itemList[position]) {
            is ListItem.GroupHeader -> {
                val groupHolder = holder as GroupHeaderViewHolder
                groupHolder.tvGroupTitle.text = item.title
            }
            is ListItem.DeviceItem -> {
                val deviceHolder = holder as DeviceViewHolder
                deviceHolder.tvDeviceName.text = item.name

                if (item.isConnecting) {
                    deviceHolder.tvDeviceStatus.visibility = View.GONE
                    deviceHolder.progressBar.visibility = View.VISIBLE
                } else {
                    deviceHolder.progressBar.visibility = View.GONE
                    deviceHolder.tvDeviceStatus.visibility = View.VISIBLE
                    if (item.isConnected) {
                        deviceHolder.tvDeviceStatus.text = "已连接"
                        deviceHolder.tvDeviceStatus.setTextColor(deviceHolder.itemView.context.getColor( R.color.green))
                        deviceHolder.tvDeviceName.setTextColor(deviceHolder.itemView.context.getColor( R.color.green))
                    } else {
                        deviceHolder.tvDeviceStatus.text = "未连接"
                        deviceHolder.tvDeviceStatus.setTextColor(deviceHolder.itemView.context.getColor(android.R.color.darker_gray))
                        deviceHolder.tvDeviceName.setTextColor(deviceHolder.itemView.context.getColor(android.R.color.black))
                    }
                }

                holder.itemView.setOnClickListener {
                    Log.d("DeviceLinkerAdapter","item-->"+item.name)
                    if (!item.isConnected && !item.isConnecting) {
                        onItemClickListener?.onConnectClick(item, position)
                    }
                }
            }
        }
    }

    /**
     * 选中指定位置的Item并取消之前的选中项
     */


    override fun getItemCount(): Int {
        return itemList.size
    }


    
    fun setNewData(list: List<ListItem>) {
        itemList.clear()
        itemList.addAll(list)
        selectedPosition = -1 // 重置选中状态
        notifyDataSetChanged()
    }

    class GroupHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvGroupTitle: TextView = itemView.findViewById(R.id.tvGroupTitle)
    }

    fun updateItemStatus(position: Int, isConnected: Boolean, isConnecting: Boolean) {
        if (position >= 0 && position < itemList.size) {
            val item = itemList[position]
            if (item is ListItem.DeviceItem) {
                item.isConnected = isConnected
                item.isConnecting = isConnecting
                notifyItemChanged(position)
            }
        }
    }
    
    class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvDeviceName: TextView = itemView.findViewById(R.id.tvDeviceName)
        val tvDeviceStatus: TextView = itemView.findViewById(R.id.tvDeviceDetails)
        val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)
    }
}