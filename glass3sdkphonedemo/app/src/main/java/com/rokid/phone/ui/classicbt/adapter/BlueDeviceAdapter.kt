package com.rokid.phone.ui.classicbt.adapter

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.blankj.utilcode.util.Utils
import com.rokid.phone.R

import com.rokid.phone.ui.classicbt.model.BluetoothDeviceInfo


interface OnBlueItemClickListener {

    fun onItemClick(position: Int,device: BluetoothDeviceInfo)

    fun onSelect(device: BluetoothDeviceInfo)

}
class BlueDeviceAdapter(private var list: List<BluetoothDeviceInfo>) :
    RecyclerView.Adapter<BlueDeviceAdapter.ViewHolder>() {

    var onItemClickListener: OnBlueItemClickListener? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_linker_device, parent, false)
        return ViewHolder(view)
    }

    @SuppressLint("MissingPermission")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        // 获取已连接设备列表（保持原有逻辑不变）
        val bluetoothManager = Utils.getApp().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val profileIds = listOf(
            BluetoothProfile.HEADSET,
            BluetoothProfile.A2DP,
            BluetoothProfile.HID_DEVICE,
            BluetoothProfile.SAP
        )

        val connectedDevices = mutableListOf<BluetoothDevice>()
        for (profileId in profileIds) {
            try {
                val method = bluetoothManager.javaClass.getMethod(
                    "getConnectedDevices",
                    Int::class.javaPrimitiveType
                )
                @Suppress("UNCHECKED_CAST")
                val devices = method.invoke(bluetoothManager, profileId) as? List<BluetoothDevice> ?: emptyList()
                connectedDevices.addAll(devices)
            } catch (e: Exception) {
                Log.w("BluetoothUtils", "Failed to get devices for profile $profileId: ${e.message}")
            }
        }

        val device = list[position]
        holder.tvDeviceName.text = "${device.name}"
        val isConnected = connectedDevices.find { it.address == device.address } != null

        holder.cbDevice.isChecked = list[position].isSelected
        // 处理Item点击事件
        holder.itemView.setOnClickListener {

            onItemClickListener?.onItemClick(position,list[position])
        }

        // 处理CheckBox点击事件
        holder.cbDevice.setOnClickListener {
            selectItem(position)
        }
    }

    private fun selectItem(position: Int) {
        if (position != RecyclerView.NO_POSITION ) {

            list.withIndex()
                .filter { it.value.isSelected }
                .forEach { (index, item) ->
                   if (index != position) {
                       list[index].isSelected = false
                       notifyItemChanged(index)
                   }
                }
            list[position].isSelected = true
            notifyItemChanged(position)


            // 回调选中事件
            onItemClickListener?.onSelect(list[position])
        }
    }

    override fun getItemCount(): Int = list.size

    fun setNewData(list: List<BluetoothDeviceInfo>) {
        Log.i("TAG", "setNewData: ${list.size}")
        this.list = list
        notifyDataSetChanged()
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvDeviceName: TextView = itemView.findViewById(R.id.tvDeviceName)
        val cbDevice: CheckBox = itemView.findViewById(R.id.cbDevice)
    }
}