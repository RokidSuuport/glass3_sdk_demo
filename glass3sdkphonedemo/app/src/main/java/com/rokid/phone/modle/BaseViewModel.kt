package com.rokid.phone.modle

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.ContentValues.TAG
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.rokid.phone.utils.Constants
import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.security.phone.sdk.api.bluetooth.classic.listener.IClassicBTClientListener
import com.rokid.security.phone.sdk.api.msg.listener.IMessageListener
import com.rokid.security.phone.sdk.api.wifip2p.listener.IWifiP2PClientListener
import com.rokid.security.phone.sdk.base.data.EngineParam
import com.rokid.security.phone.sdk.base.data.EnvType
import com.rokid.security.phone.sdk.base.data.RtcConfig
import com.rokid.security.phone.sdk.base.data.ServiceBusConfig
import com.rokid.security.phone.sdk.base.data.UserAuthInfo
import com.rokid.security.phone.sdk.base.data.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.time.Instant
import java.time.temporal.TemporalAmount
import java.util.Date
import kotlin.time.Duration

open class BaseViewModel : ViewModel() {
    private val mAbsSecurityEngineService by lazy {
        PSecuritySDK.getMobileEngineService()
    }

    private val TAG = "BaseViewModel::"
    private var deviceList = mutableListOf<BluetoothDevice>()
    private var isConnected = false;
    private lateinit var device: BluetoothDevice
    private val mAbsBlueToothClientService by lazy {
        PSecuritySDK.getClassicBlueToothClientService()
    }


//    fun generateSignedToken(tokenDTO: AppTokenDTO, secretKey: String, duration: Duration): String {
//        // 创建一个JWT构建器
//        val builder = JWT.create()
//            .withHeader(mapOf("alg" to "HS256", "typ" to "JWT"))
//
//        // 准备负载数据
//        val payloadMap = mapOf(
//            "uid" to tokenDTO.uid,
//            "deviceType" to tokenDTO.deviceType,
//            "deviceId" to tokenDTO.deviceId,
//            "appId" to tokenDTO.appId
//        )
//
//        // 添加负载声明
//        payloadMap.forEach { (key, value) ->
//            builder.withClaim(key, value)
//        }
//        val temporalAmount: TemporalAmount = duration
//        // 设置过期时间并签名
//        return builder
//            .withExpiresAt(Date.from(Instant.now().plus(temporalAmount)))
//            .sign(Algorithm.HMAC256(secretKey))
//    }

    private var mUserInfo: UserInfo? = null
//    fun initSDK() {
//        viewModelScope.launch(Dispatchers.IO) {
//            val userAuthInfo = UserAuthInfo("","")
//            val userInfo = UserInfo(
//                "吴江明-7", "123", "",
//                ""
//            )
////
//
//            val rtcConfig = RtcConfig("", "", "", "", "")
//
////
//            var serviceBusConfig = ServiceBusConfig("", "userInfo", arrayListOf())
//            val param = EngineParam(
//                Constants.ServerId, serviceBusConfig, userInfo, rtcConfig,
//                EnvType.PUBLIC, "",userAuthInfo
//            )
//            mUserInfo = userInfo
//            mAbsSecurityEngineService.initSDK(param) {
//                Log.i(TAG, "initSDK: $it")
//                if (it.isSuccess) {
//                }
//            }
//
//        }
//
//    }

    fun destroySDK() {
        mAbsSecurityEngineService.destroy()
        PSecuritySDK.getClassicBlueToothClientService()
            ?.removeClientListener(mIClassicBTClientListener)
        PSecuritySDK.getWifiP2PClientService()?.removeWifiP2PClientListener(mIWifiP2PClientListener)

    }


}


private val mIWifiP2PClientListener = object : IWifiP2PClientListener {
    override fun onWifiP2pEnabled(enabled: Boolean) {
        Log.i(TAG, "onWifiP2pEnabled:  $enabled")


    }
}
private val mIClassicBTClientListener = object : IClassicBTClientListener {
    override fun onDeviceFound(device: BluetoothDevice) {

    }

    override fun onScanFinished() {

    }

    override fun onConnect(success: Boolean) {
//            MyApplication.instance.isBtConnect = success
//            lifecycleScope.launch {
//                if(!success){
//                    connectStatus(false)
//                }else{
//                    binding.tvDeviceName.text = DeviceLinkerManager.getDeviceName()
//                }
//            }
    }

    override fun onConnectionRejected(reason: String, code: Int) {
//        TODO("Not yet implemented")
    }


}