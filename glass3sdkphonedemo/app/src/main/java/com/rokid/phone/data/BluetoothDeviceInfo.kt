package com.rokid.phone.data

data class BluetoothDeviceInfo(
    var name: String,
    var address: String,
    var type: Int
){

    var isSelected: Boolean = false

}
