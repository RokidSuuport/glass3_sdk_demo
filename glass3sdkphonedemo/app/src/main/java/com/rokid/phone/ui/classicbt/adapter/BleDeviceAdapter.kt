package com.rokid.phone.ui.classicbt.adapter

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.blankj.utilcode.util.Utils
import com.rokid.phone.R


interface OnBleItemClickListener {

    fun onItemClick(position: Int,device: BluetoothDevice)

}

class BleDeviceAdapter(private var list: List<ScanResult>) :
    RecyclerView.Adapter<BleDeviceAdapter.ViewHolder>() {

    var onItemClickListener: OnBleItemClickListener? = null
    val bluetoothManager = Utils.getApp().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_ble_device, parent, false)
        return ViewHolder(view)
    }


    @SuppressLint("MissingPermission")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val connectedDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
        val device = list[position].device
        holder.tvDeviceName.text = "${device.name} ----- ${device.address}"
        holder.tvDeviceAddress.text = device.address
        val conDevice = connectedDevices.find { it.address ==  device.address}
        val isConneted = conDevice != null
        holder.tvDeviceDetails.text = if(isConneted) "已连接" else ""
        holder.itemView.setOnClickListener {
            onItemClickListener?.onItemClick( position,device)
        }

    }

    override fun getItemCount(): Int {
        return list.size
    }

    fun setNewData(list: List<ScanResult>){
        Log.i("TAG", "setNewData: ${list.size}")
        this.list = list
        notifyDataSetChanged()
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        val tvDeviceName: TextView = itemView.findViewById(R.id.tvDeviceName)

        val tvDeviceAddress: TextView = itemView.findViewById(R.id.tvDeviceAddress)

        val tvDeviceDetails: TextView = itemView.findViewById(R.id.tvDeviceDetails)

    }

}