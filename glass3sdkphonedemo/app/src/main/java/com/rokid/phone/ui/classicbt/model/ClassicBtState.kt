package com.rokid.phone.ui.classicbt.model

import com.rokid.phone.base.viewmodel.interfaces.UiEvent
import com.rokid.phone.base.viewmodel.interfaces.UiIntent
import com.rokid.phone.base.viewmodel.interfaces.UiState

/** 经典蓝牙页面的状态、用户意图和一次性事件。 */
data class TestState(
    val log: String = "",
    val connect: Boolean = false,
) : UiState



sealed class TestIntent : UiIntent {
    object startScanBt : TestIntent()
    object stopScanBt : TestIntent()
    object disconnect : TestIntent()
    data class sendMessageBt (val msg: String) : TestIntent()
    data class connectBt(val device: BluetoothDeviceInfo) : TestIntent()

}


sealed class TestEvent : UiEvent {
    data class ShowToast(val message: String) : TestEvent()
    data class UpdateConnectState(val isConnect: Boolean) : TestEvent()
    data class ShowBlueToothList(val list: List<BluetoothDeviceInfo>): TestEvent()
    data class FinishBlueToothList(val list: List<BluetoothDeviceInfo>): TestEvent()
}
