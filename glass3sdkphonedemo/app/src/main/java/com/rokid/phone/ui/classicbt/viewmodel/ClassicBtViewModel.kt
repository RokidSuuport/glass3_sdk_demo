package com.rokid.phone.ui.classicbt.viewmodel

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.rokid.phone.base.viewmodel.MviViewModel

import com.rokid.phone.ui.classicbt.model.BluetoothDeviceInfo
import com.rokid.phone.ui.classicbt.model.TestEvent
import com.rokid.phone.ui.classicbt.model.TestIntent
import com.rokid.phone.ui.classicbt.model.TestState
import com.rokid.phone.ui.classicbt.repository.ClassicBtRepository
import kotlinx.coroutines.launch

/** 协调经典蓝牙连接操作与界面状态。 */
class ClassicBtViewModel(
    private val repository: ClassicBtRepository
) : MviViewModel<TestState, TestIntent, TestEvent>() {

    private val TAG = "ClassicBtViewModel"
    private var isScanFinished = false

    override fun createInitialState(): TestState = TestState()

    override suspend fun handleIntent(intent: TestIntent) {
        when (intent) {
            is TestIntent.startScanBt -> startBt()
            is TestIntent.stopScanBt-> stopBt()
            is TestIntent.disconnect -> disconnect()
            is TestIntent.sendMessageBt -> sendMessageBt(intent.msg)
            is TestIntent.connectBt -> connectBt(intent.device)
            else -> {}
        }
    }


    init {
        // 观察Repository数据变化
        viewModelScope.launch {
            repository._events.collect { btEvent ->
                when (btEvent) {
                    is ClassicBtRepository.BluetoothEvent.ScanFinished -> {
                        Log.d(TAG,"ScanFinished "+btEvent.devices.size)
                        if (!isScanFinished){
                            sendEvent(TestEvent.FinishBlueToothList(btEvent.devices))
                        }
                        isScanFinished = true

                    }
                    is ClassicBtRepository.BluetoothEvent.ConnectResult -> {
                        Log.d(TAG,"ConnectResult "+btEvent.connect)
                        sendEvent(TestEvent.UpdateConnectState(btEvent.connect))
                    }

                    is ClassicBtRepository.BluetoothEvent.DeviceFound -> {
                        sendEvent(TestEvent.ShowBlueToothList(btEvent.devices))
                    }
                    else -> {}
                }
            }
        }
        repository.addListener()
    }


    private fun startBt(){
        Log.d(TAG,"----startBt ")
        isScanFinished = false
        repository.startScan(10000)
    }

    private fun stopBt(){
        repository.stopScan()
        isScanFinished = true

    }

    private fun disconnect(){
        repository.disconnect()
    }

    private fun  sendMessageBt(msg:String){
        repository.sendMessageBt(msg)
    }

    private fun connectBt(device: BluetoothDeviceInfo){
        repository.connectBt(device)
    }







}
