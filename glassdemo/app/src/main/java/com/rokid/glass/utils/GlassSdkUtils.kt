package com.rokid.glass.utils

import android.bluetooth.BluetoothDevice
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.util.Log
import com.blankj.utilcode.util.Utils
import com.rokid.glass.data.GlobalData
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.client.IServiceConnectionCallback
import com.rokid.security.glass3.open.sdk.uitls.log.L
import com.rokid.security.glass3.sdk.base.data.ring.bean.BluetoothDeviceBean
import com.rokid.security.sdk.base.common.outside.CompanionAppInfo
import com.rokid.security.sdk.base.common.outside.UserInfo
import com.rokid.security.system.server.IClientCallback
import com.rokid.security.system.server.bluetooth.IBTService
import com.rokid.security.system.server.bluetooth.listener.IClassicBTListener
import com.rokid.security.system.server.common.ICommonInfoServer
import com.rokid.security.system.server.common.listener.ICommonInfoListener
import com.rokid.security.system.server.device.IDeviceService
import com.rokid.security.system.server.message.IMessageServer
import com.rokid.security.system.server.message.listener.IMessageListener
import com.rokid.security.system.server.ring.IBluetoothRingService
import com.rokid.security.system.server.ring.listener.IBluetoothRingConnectListener
import com.rokid.security.system.server.wifip2p.IWifiP2PGoService
import com.rokid.security.system.server.wifip2p.listener.IWifiP2PGoListener

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Created by wjm on 2025/9/3
 */
object GlassSdkUtils {
    private const val TAG = "GlassSdkUtils"
    var mBTService: IBTService? = null
    var mP2PGoService: IWifiP2PGoService? = null
    var mMessageService: IMessageServer? = null
    var mGlassBluetoothRingService: IBluetoothRingService? = null
    var mGlassCommonService: ICommonInfoServer? = null
    var mGlassDeviceService: IDeviceService? = null


    private var mClientMessageCallback = object : IClientCallback.Stub() {
        override fun onReady() {
            mBTService = GlassSdk.getClassicBluetoothService()
            mP2PGoService = GlassSdk.getP2PGoService()
            mMessageService = GlassSdk.getGlassMessageService()
            mGlassBluetoothRingService = GlassSdk.getGlassBluetoothRingService()
            mGlassCommonService = GlassSdk.getGlassCommonService()
            mGlassDeviceService = GlassSdk.getGlassDeviceService()

            GlobalData.setSdkInitState(true)
            val isBtConnect = mBTService?.isConnect ?: false
            GlobalData.setBtConnectState(isBtConnect)
            mP2PGoService?.isConnect(object : com.rokid.security.system.server.wifip2p.callback.IResultCallback.Stub() {
                override fun onResult(result: Boolean) {
                    GlobalData.setP2pConnectState(result)
                }
            })
            val isRingConnect = mGlassBluetoothRingService?.isRingConnect ?: false
            GlobalData.setRingConnectState(isRingConnect)
            Log.d(TAG, "isRingConnect->$isRingConnect")
            mBTService?.setClassicBTListener(mIClassicBTListener)
            mP2PGoService?.setWifiP2PClientListener(mIWifiP2PGoListener)
            mMessageService?.setMessageListener(mMessageListener)
            mGlassBluetoothRingService?.setBluetoothRingState(mIBluetoothRingConnectListener)

            mGlassCommonService?.setCommonInfoListener(object : ICommonInfoListener.Stub() {
                override fun onUserInfo(info: UserInfo?) {
                    Log.d(TAG, "onUserInfo->" + info?.userName)
                }

                /**
                 * 更新伴生端 App 信息
                 * @param info
                 */
                override fun onCompanionAppInfo(info: CompanionAppInfo?) {
                    Log.d(TAG, "onPhoneAppInfo->" + info?.envType)
                }

//                override fun onPhoneAppInfo(info: PhoneAppInfo?) {
//                    Log.d(TAG, "onPhoneAppInfo->" + info?.envType)
//                }

                override fun onConfig(p0: String?) {
                }
            })
        }
    }


    private val mIClassicBTListeners = mutableSetOf<IClassicBTListener>()
    private val mIClassicBTListener = object : IClassicBTListener.Stub() {

        override fun onClientConnected(device: BluetoothDevice) {
            GlobalData.setBtConnectState(true)
            mIClassicBTListeners.forEach { it.onClientConnected(device) }
        }

        override fun onClientDisconnected(device: BluetoothDevice) {
            GlobalData.setBtConnectState(false)
            mIClassicBTListeners.forEach { it.onClientDisconnected(device) }
        }

        override fun onConnectionRejected(device: BluetoothDevice) {
            mIClassicBTListeners.forEach { it.onConnectionRejected(device) }
        }
    }

    fun addClassicBTListener(listener: IClassicBTListener) {
        if (!mIClassicBTListeners.contains(listener)) {
            mIClassicBTListeners.add(listener)
        }
    }

    fun removeClassicBTListener(listener: IClassicBTListener) {
        mIClassicBTListeners.remove(listener)
    }

    private val mIWifiP2PGoListeners = mutableSetOf<IWifiP2PGoListener>()
    private val mIWifiP2PGoListener = object : IWifiP2PGoListener.Stub() {

        /**
         * wifip2p 是否可用(包含数据通道)
         */
        override fun onWifiP2pEnabled(enabled: Boolean) {
            GlobalData.setP2pConnectState(enabled)
            mIWifiP2PGoListeners.forEach { it.onWifiP2pEnabled(enabled) }
        }

        /**
         * 获取WifiP2p 信息
         */
        override fun onConnectionInfoAvailable(wifiP2pInfo: WifiP2pInfo?) {
            mIWifiP2PGoListeners.forEach { it.onConnectionInfoAvailable(wifiP2pInfo) }
        }

        /**
         * WifiP2p 通道已断开
         */
        override fun onChannelDisconnected() {
            mIWifiP2PGoListeners.forEach { it.onChannelDisconnected() }
            GlobalData.setP2pConnectState(false)
        }

        /**
         * 获取当前设备WifiP2pDevice信息
         */
        override fun onSelfDeviceAvailable(device: WifiP2pDevice?) {
            mIWifiP2PGoListeners.forEach { it.onSelfDeviceAvailable(device) }
        }

        /**
         * 发现的WifiP2p设备列表
         */
        override fun onPeersAvailable(devices: MutableList<WifiP2pDevice>?) {
            mIWifiP2PGoListeners.forEach { it.onPeersAvailable(devices) }
        }
    }

    fun addWifiP2PGoListener(listener: IWifiP2PGoListener) {
        if (!mIWifiP2PGoListeners.contains(listener)) {
            mIWifiP2PGoListeners.add(listener)
        }
    }

    fun removeWifiP2PGoListener(listener: IWifiP2PGoListener) {
        mIWifiP2PGoListeners.remove(listener)
    }

    private val mIMessageListeners = CopyOnWriteArraySet<IMessageListener>()
    private val mMessageListener = object : IMessageListener.Stub() {
        override fun onTextMessage(msg: String) {
            mIMessageListeners.forEach { it.onTextMessage(msg) }
        }

        override fun onAudioStream(buffer: ByteArray) {
            mIMessageListeners.forEach { it.onAudioStream(buffer) }
        }

        override fun onStreamDataReceived(tag: String, data: ByteArray) {
            mIMessageListeners.forEach { it.onStreamDataReceived(tag, data) }
        }
    }

    fun addMessageListener(listener: IMessageListener) {
        if (!mIMessageListeners.contains(listener)) {
            mIMessageListeners.add(listener)
        }
    }

    fun removeMessageListener(listener: IMessageListener) {
        mIMessageListeners.remove(listener)
    }


    private val mIBluetoothRingConnectListeners = mutableSetOf<IBluetoothRingConnectListener>()
    private val mIBluetoothRingConnectListener = object : IBluetoothRingConnectListener.Stub() {
        override fun onConnect(bean: BluetoothDeviceBean?, connect: Boolean) {
            GlobalData.setRingConnectState(connect)
            Log.d(TAG, "onConnect->$connect")
        }
    }

    fun addBluetoothRingConnectListener(listener: IBluetoothRingConnectListener) {
        if (!mIBluetoothRingConnectListeners.contains(listener)) {
            mIBluetoothRingConnectListeners.add(listener)
        }
    }

    fun removeBluetoothRingConnectListener(listener: IBluetoothRingConnectListener) {
        mIBluetoothRingConnectListeners.remove(listener)
    }


    fun initSdk() {
        // 如果SDK已经初始化了，则直接返回
        if (GlassSdk.isReady()) {
            Log.d(TAG, "sdk已经初始化了")
            return
        }
        GlassSdk.bindSecurityService(Utils.getApp(), object : IServiceConnectionCallback {
            override fun onServiceConnected() {
//                L.i(TAG, "----服务绑定成功：onServiceConnected")
                //眼镜端的clientId与手机端注册的clientId要相同,
                //这样手机端就知道把数据发给眼镜端那个应用程序了
                GlassSdk.registerClient("GlassSample", mClientMessageCallback)
            }

            override fun onServiceDisconnected() {
                L.w(TAG, "----服务意外断开：onServiceDisconnected（可能服务端异常退出）")
                GlobalData.setSdkInitState(false)
            }

            override fun onBindingDied() {
                L.e(TAG, "----服务绑定死亡：onBindingDied（服务端进程崩溃）")
                GlobalData.reset()
                mBTService = null
                mP2PGoService = null
                mMessageService = null
                mGlassBluetoothRingService = null
                mGlassCommonService = null
                mGlassDeviceService = null

                Scopes.mainScope.launch {
                    delay(4000)
                    Log.i(TAG, "onBindingDied:  重新绑定服务")
                    initSdk() // 自动重新绑定
                }
            }
        })
    }

    fun destroySdk() {
//        GlassSdk.unbindSecurityService()
        //release 内部会 unbindSecurityService()
        GlassSdk.release()
        mIWifiP2PGoListeners.clear()
        mIClassicBTListeners.clear()
        mIMessageListeners.clear()
        mIBluetoothRingConnectListeners.clear()
        GlobalData.reset()
    }
}