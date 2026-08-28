package com.rokid.phone.ui.classicbt.model

data class BluetoothDeviceInfo(
    var name: String,
    var address: String,
    var type: Int
) {
    var isSelected: Boolean = false
}
