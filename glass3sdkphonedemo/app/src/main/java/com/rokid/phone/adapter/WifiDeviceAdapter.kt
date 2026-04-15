package com.rokid.phone.adapter

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.graphics.Color
import android.net.wifi.p2p.WifiP2pDevice
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.viewholder.QuickViewHolder
import com.rokid.phone.R

class WifiDeviceAdapter(private val action: (WifiP2pDevice) -> Unit) :
    BaseQuickAdapter<WifiP2pDevice, QuickViewHolder>() {

    var currentIndex = -1

    override fun onBindViewHolder(
        holder: QuickViewHolder,
        position: Int,
        item: WifiP2pDevice?
    ) {
        val textView = holder.getView<TextView>(R.id.tv_name)
        item?.let {
            textView.text = it.deviceName
            holder.itemView.setOnClickListener { v ->
                action(it)
                if (currentIndex >= 0) {
                    notifyItemChanged(currentIndex)
                }
                currentIndex = position
                notifyItemChanged(currentIndex)
            }

        }
        if (position == currentIndex) {
            textView.setTextColor(Color.RED)
        } else {
            textView.setTextColor(Color.BLACK)
        }
    }

    override fun onCreateViewHolder(
        context: Context,
        parent: ViewGroup,
        viewType: Int
    ): QuickViewHolder {
        return QuickViewHolder(
            LayoutInflater.from(context).inflate(R.layout.item_bluetooth, parent, false)
        )
    }

}