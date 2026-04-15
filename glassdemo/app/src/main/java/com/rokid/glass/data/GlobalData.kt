package com.rokid.glass.data


import com.rokid.glass.utils.call
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Created by wjm on 2025/9/3
 */
object GlobalData {

    val h264ConnectState = MutableStateFlow(false)
    val p2pConnectState = MutableStateFlow(false)
    val btConnectState = MutableStateFlow(false)
    val sdkInitState = MutableStateFlow(false)
    val ringConnectState = MutableStateFlow(false)

    fun setH264ConnectState(state: Boolean) {
        h264ConnectState.call(state)
    }


    fun setP2pConnectState(state: Boolean) {
        p2pConnectState.call(state)
    }

    fun setBtConnectState(state: Boolean) {
        btConnectState.call(state)
    }

    fun isGlassConnect(): Boolean {
        return p2pConnectState.value && btConnectState.value
    }

    fun setSdkInitState(state: Boolean) {
        sdkInitState.call(state)
    }

    fun setRingConnectState(state: Boolean) {
        ringConnectState.call(state)
    }

    fun reset() {
        setP2pConnectState(false)
        setBtConnectState(false)
        setSdkInitState(false)
        setRingConnectState(false)
    }
}