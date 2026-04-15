package com.rokid.phone.data


import com.rokid.security.phone.sdk.base.utils.other.ktx.call
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Created by wjm on 2025/8/7
 */
object GlobalData {
    /**
     * p2p连接状态
     */
    val p2pConnectState = MutableStateFlow(false)

    /**
     * 蓝牙连接状态
     */
    val btConnectState = MutableStateFlow(false)

    /**
     * sdk 初始化状态
     */
    val sdkInitState = MutableStateFlow(false)

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

    fun reset() {
        setP2pConnectState(false)
        setBtConnectState(false)
        setSdkInitState(false)
    }
}