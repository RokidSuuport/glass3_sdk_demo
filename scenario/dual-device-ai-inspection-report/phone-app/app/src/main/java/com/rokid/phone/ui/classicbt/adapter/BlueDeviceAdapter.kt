package com.rokid.phone.ui.classicbt.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.rokid.phone.databinding.ItemLinkerDeviceBinding
import com.rokid.phone.ui.classicbt.model.BluetoothDeviceInfo

class BlueDeviceAdapter(
    private val onClick: (BluetoothDeviceInfo) -> Unit,
) : RecyclerView.Adapter<BlueDeviceAdapter.ViewHolder>() {
    private var items: List<BluetoothDeviceInfo> = emptyList()

    fun submit(devices: List<BluetoothDeviceInfo>) {
        items = devices
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLinkerDeviceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = items[position]
        holder.binding.tvDeviceName.text = device.name
        holder.binding.root.setOnClickListener { onClick(device) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(val binding: ItemLinkerDeviceBinding) : RecyclerView.ViewHolder(binding.root)
}
