package com.rokid.phone.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.rokid.phone.DeviceLinkerManager
import com.rokid.phone.MyApplication
import com.rokid.phone.R
import com.rokid.phone.base.BaseActivity
import com.rokid.phone.data.GlobalData
import com.rokid.phone.databinding.ActivityDiscoverP2pDevice2Binding
import com.rokid.phone.ui.classicbt.ui.ClassicBtActivity
import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.security.phone.sdk.api.wifip2p.listener.IWifiP2PClientListener
import com.rokid.phone.utils.FrameAnimationUtil
import com.rokid.phone.utils.SystemStateUtils
import com.rokid.security.phone.sdk.base.utils.other.ktx.collect
import com.rokid.utils.ToastUtils

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class BtWifiConnectActivity : BaseActivity<ActivityDiscoverP2pDevice2Binding>() {
    private val TAG = "BtWifiConnectActivity"
    private var curStatus = PARI_PRE_STATUS
    private var mDevice: WifiP2pDevice? = null
    private val mWifiP2PClientService by lazy {
        PSecuritySDK.getWifiP2PClientService()
    }
    private val mClassicBlueToothClientService by lazy {
        PSecuritySDK.getClassicBlueToothClientService()
    }
    private var outTimeJob: Job? = null
    private var actionJob: Job? = null
    private var connectBtJob: Job? = null
    private var completeJob: Job? = null
    private var mFrameAnimationUtil: FrameAnimationUtil? = null
    private var isFindP2PDevice = false
    private var isConnetBt = false
    private var isConnetP2p = false

    override fun onInit(savedInstanceState: Bundle?) {
        initView()
    }

    companion object {
        var PARI_PRE_STATUS = "PARI_PRE_STATUS"
        var PARI_ING_STATUS = "PARI_ING_STATUS"
        var PARI_SUCCESS_STATUS = "PARI_SUCCESS_STATUS"
        var PARI_FAILED_STATUS = "PARI_FAILED_STATUS"
        var frameTime = 150
        const val CONNET_BT_KEY = "isConnetBt"
        const val CONNET_P2P_KEY = "isConnetP2p"

        fun start(activity: Activity, isConnetBt: Boolean, isConnetP2p: Boolean) {
            val intent = Intent(activity, BtWifiConnectActivity::class.java)
            intent.putExtra(CONNET_BT_KEY, isConnetBt)
            intent.putExtra(CONNET_P2P_KEY, isConnetP2p)
            activity.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            mFrameAnimationUtil = FrameAnimationUtil(binding.ivDiscoverGif)
            // 动态创建帧列表（资源ID + 持续时间）
            mFrameAnimationUtil?.initFromFrames(MyApplication.frameList, lifecycleScope)
        }
    }

    private fun initView() {
        isConnetBt = intent.getBooleanExtra(CONNET_BT_KEY, false)
        isConnetP2p = intent.getBooleanExtra(CONNET_P2P_KEY, false)
        Log.i(TAG, "------->onCreate: 是否连接蓝牙 = $isConnetBt, 是否连接P2P = $isConnetP2p")
        GlobalData.setP2pConnectState(false)
        PSecuritySDK.getWifiP2PClientService()?.isConnect {
            if (it) {
                PSecuritySDK.getWifiP2PClientService()?.stopPeerDiscovery { }
                PSecuritySDK.getWifiP2PClientService()?.disconnect()
//                GlobalData.setP2pConnectState(true)
            }
        }

        binding.ivBack.setOnClickListener {
            finish()
        }
        binding.btnPairDevice.setOnClickListener {
            if (curStatus == PARI_SUCCESS_STATUS) {
                finish()
                return@setOnClickListener
            }
            if (isConnetP2p) {
                startDiscoverTask()
            }
        }
        binding.tvSkip.setOnClickListener {
            finish()
        }
        actionJob?.cancel()
        actionJob = lifecycleScope.launch {
            if (isConnetBt) {
                if (!GlobalData.btConnectState.value) {
                    Log.i(TAG, "蓝牙未连接,跳转到蓝牙界面 ")
                    if (DeviceLinkerManager.mConnectingBluetoothDevice == null) {
                        jumpToBtActivity()
                    } else {
                        val device = BluetoothAdapter.getDefaultAdapter()
                            .getRemoteDevice(DeviceLinkerManager.mConnectingBluetoothDevice?.address)
                        if (device != null) {
                            connectBtJob?.cancel()
                            connectBtJob = lifecycleScope.launch {
                                delay(1000 * 10)
                                if (!GlobalData.btConnectState.value) {
                                    jumpToBtActivity()
                                }
                            }
                            mClassicBlueToothClientService?.connectToServer(device) { result ->
                                Log.i(TAG, "btConnectState connectToServer: $result")

                            }
                        }
                    }
                }
                GlobalData.btConnectState.collect(lifecycleScope) { result ->
                    Log.i(TAG, "btConnectState onChanged: $result")
                    if (result) {
                        DeviceLinkerManager.mConnectingBluetoothDevice?.let {
                            DeviceLinkerManager.saveBlueToothDeviceInfo(it)
                        }
                        curStatus = PARI_SUCCESS_STATUS
                        if (isConnetP2p) {
                            if (!GlobalData.p2pConnectState.value) {
                                ToastUtils.makeText(this@BtWifiConnectActivity, "蓝牙已连接,正在进行P2P配对")
                                startDiscoverTask()
                            } else {
                                completePair()
                            }
                        } else {
                            showStatus()
                        }
                    }
                }

            } else {
                if (isConnetP2p) {
                    if (!GlobalData.p2pConnectState.value) {
                        startDiscoverTask()
                    }
                }
            }
        }

        GlobalData.p2pConnectState.drop(1).collect(lifecycleScope) {
            if (it) {
                curStatus = PARI_SUCCESS_STATUS
                showStatus()
            } else {
                if (curStatus == PARI_ING_STATUS) {
                    curStatus = PARI_FAILED_STATUS
                }
                showStatus()
            }
        }
    }


    private fun jumpToBtActivity() {
        mClassicBlueToothClientService?.disconnect()
        lifecycleScope.launch {
            Toast.makeText(this@BtWifiConnectActivity, "蓝牙连接失败", Toast.LENGTH_SHORT).show()
        }
        val intent = Intent(this, ClassicBtActivity::class.java)
        startActivity(intent)
        finish()
    }


    private fun startDiscoverTask() {
        if (!SystemStateUtils.isWifiEnabled(this)) {
            Toast.makeText(this@BtWifiConnectActivity, "wifi未开启", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        curStatus = PARI_ING_STATUS
        showStatus()
        //自动连接p2p
        mWifiP2PClientService?.addWifiP2PClientListener(mIWifiP2PClientListener)
        mWifiP2PClientService?.sendConnectP2pRequest {}
        outTimeTask(20 * 1000)
    }

    private fun outTimeTask(time: Long) {
        outTimeJob?.cancel()
        outTimeJob = lifecycleScope.launch {
            delay(time)
            mWifiP2PClientService?.stopPeerDiscovery()
            mWifiP2PClientService?.disconnect()
            if (curStatus != PARI_SUCCESS_STATUS) {
                curStatus = PARI_FAILED_STATUS
                showStatus()
            }
        }
    }

    private fun wifiP2pConnect(device: WifiP2pDevice) {
        Log.i(TAG, "wifiConnect: ")
        mDevice = device
        mWifiP2PClientService?.connectDevice(device) { it1 ->
            mWifiP2PClientService?.removeWifiP2PClientListener(mIWifiP2PClientListener)
        }
    }


    override fun onResume() {
        super.onResume()
        if (!SystemStateUtils.isWifiEnabled(this)) {
            Toast.makeText(this@BtWifiConnectActivity, "wifi未开启", Toast.LENGTH_SHORT).show()
        }
    }

    private val mIWifiP2PClientListener = object : IWifiP2PClientListener {
        override fun onWifiP2pEnabled(enabled: Boolean) {
            Log.i(TAG, "onWifiP2pEnabled $enabled")
            lifecycleScope.launch {
                if (enabled && mDevice != null) {
                    DeviceLinkerManager.saveP2pDevice(mDevice!!)
                }
            }
        }

        override fun onSelfDeviceAvailable(device: WifiP2pDevice) {
            binding.tvDeviceName.text = device.deviceName
            Log.d(TAG, "获取当前WifiP2pDevice 设备信息")
        }

        override fun onPeersAvailable(devices: List<WifiP2pDevice>) {
            mDevice = devices.find { DeviceLinkerManager.mConnectingBluetoothDevice?.name == it.deviceName }
            val newDevices = devices.filter { it.deviceName.contains("Glass3_") }
            if (newDevices.isEmpty()) {
                return
            }
            Log.d(TAG, "发现设备列表: $newDevices")
            if (!isFindP2PDevice) {
                var needDevice =
                    newDevices.firstOrNull { device -> device.deviceName == DeviceLinkerManager.mConnectingBluetoothDevice?.name }
                if (needDevice != null) {
                    isFindP2PDevice = true
                    wifiP2pConnect(needDevice)
                }
            }
        }
    }

    private fun showStatus() {
        Log.i(TAG, "showStatus: $curStatus")
        when (curStatus) {
            PARI_PRE_STATUS -> {
                binding.tvTitleDiscoverDevice.text = "连接中..."
                binding.tvSubtitleDiscoverDevice.text = "请耐心等待"
                binding.tvTitleDiscoverDevice.visibility = View.VISIBLE
                binding.tvSubtitleDiscoverDevice.visibility = View.VISIBLE
                binding.tvPairComplete.visibility = View.GONE
                binding.btnPairDevice.visibility = View.GONE
                binding.ivDiscoverGif.visibility = View.VISIBLE
                binding.ivShow.visibility = View.GONE
                mFrameAnimationUtil?.start()
            }

            PARI_ING_STATUS -> {
                binding.tvTitleDiscoverDevice.text = "连接中..."
                binding.tvSubtitleDiscoverDevice.text = "请耐心等待"
                binding.tvTitleDiscoverDevice.visibility = View.VISIBLE
                binding.tvSubtitleDiscoverDevice.visibility = View.VISIBLE
                binding.ivDiscoverGif.visibility = View.VISIBLE
                binding.tvPairComplete.visibility = View.GONE
                binding.btnPairDevice.visibility = View.GONE
                binding.ivShow.visibility = View.GONE
                mFrameAnimationUtil?.start()
            }

            PARI_SUCCESS_STATUS -> {
                mFrameAnimationUtil?.stop()
                binding.tvTitleDiscoverDevice.text = "配对成功"
                binding.tvSubtitleDiscoverDevice.visibility = View.INVISIBLE
                binding.ivShow.setImageResource(R.mipmap.icon_pair_complete)
                binding.ivShow.visibility = View.VISIBLE
                binding.tvPairComplete.visibility = View.VISIBLE
                binding.ivDiscoverGif.visibility = View.GONE
                binding.btnPairDevice.visibility = View.VISIBLE
                completePair()
            }

            PARI_FAILED_STATUS -> {
                mFrameAnimationUtil?.stop()
                binding.tvTitleDiscoverDevice.text = "配对失败"
                binding.tvSubtitleDiscoverDevice.text = "设备蓝牙名称可从眼镜主页获取"
                binding.tvTitleDiscoverDevice.visibility = View.VISIBLE
                binding.tvSubtitleDiscoverDevice.visibility = View.VISIBLE
                binding.tvPairComplete.visibility = View.GONE
                binding.ivShow.setImageResource(R.mipmap.icon_rokid_big)
                binding.ivDiscoverGif.visibility = View.GONE
                binding.btnPairDevice.visibility = View.VISIBLE
                binding.btnPairDevice.text = "重试"
            }
        }
        binding.tvDeviceName.text = DeviceLinkerManager.mConnectingBluetoothDevice?.name
    }

    val countDown = 3

    @SuppressLint("SetTextI18n")
    private fun completePair() {
        Toast.makeText(this, "配对成功", Toast.LENGTH_SHORT).show()
        completeJob?.cancel()
        completeJob = lifecycleScope.launch {
            repeat(countDown) { second ->
                val remainingTime = countDown - second
                binding.tvTitleDiscoverDevice.text = "配对成功"
                binding.btnPairDevice.text = "进入首页($remainingTime)"
                delay(1000) // 延迟2秒
            }
            finish()
        }
    }

    override fun initViewBinding(): ActivityDiscoverP2pDevice2Binding {
        return ActivityDiscoverP2pDevice2Binding.inflate(layoutInflater)
    }

    override fun onDestroy() {
        super.onDestroy()
        connectBtJob?.cancel()
        outTimeJob?.cancel()
        actionJob?.cancel()
        completeJob?.cancel()
        mWifiP2PClientService?.removeWifiP2PClientListener(mIWifiP2PClientListener)
        mWifiP2PClientService?.stopPeerDiscovery()
    }

}
