package com.rokid.phone.ui.classicbt.model

/**
 * Created by wjm on 2025/7/10
 */
data class BluetoothDeviceInfo(
    var name: String,
    var address: String,
    var type: Int
) {
    var isSelected: Boolean = false
}