package com.rokid.phone.ui

import android.net.wifi.p2p.WifiP2pDevice
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.rokid.phone.DeviceLinkerManager
import com.rokid.phone.R
import com.rokid.phone.base.BaseActivity
import com.rokid.phone.data.GlobalData
import com.rokid.phone.databinding.ActivityDiscoverP2pDevice2Binding
import com.rokid.phone.utils.FrameAnimationUtil
import com.rokid.phone.utils.SystemStateUtils
import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.security.phone.sdk.api.wifip2p.listener.IWifiP2PClientListener
import com.rokid.security.phone.sdk.base.utils.other.ktx.collect
import com.rokid.security.phone.sdk.base.utils.other.mainScope

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 蓝牙和p2p连接界面
 * 连接成功后跳转到连接成功界面
 */
class WifiP2PSettingActivity : BaseActivity<ActivityDiscoverP2pDevice2Binding>() {
    private val TAG = "WifiP2PSettingActivity"

    private var curStatus = PARI_PRE_STATUS
    private var mDevice: WifiP2pDevice? = null
    private val mWifiP2PClientService by lazy {
        PSecuritySDK.getWifiP2PClientService()
    }
    private var mFrameAnimationUtil: FrameAnimationUtil? = null

    private var isFindDevice = false

    override fun onInit(savedInstanceState: Bundle?) {
        initView()
    }

    companion object {
        var PARI_PRE_STATUS = "PARI_PRE_STATUS"
        var PARI_ING_STATUS = "PARI_ING_STATUS"
        var PARI_SUCCESS_STATUS = "PARI_SUCCESS_STATUS"
        var PARI_FAILED_STATUS = "PARI_FAILED_STATUS"

        var frameTime = 14

        val frameList = listOf(
            R.mipmap.fx_matching_000 to frameTime,  // 第一帧，持续100ms
//            R.mipmap.fx_matching_003 to frameTime,
//            R.mipmap.fx_matching_006 to frameTime,
            R.mipmap.fx_matching_009 to frameTime,
//            R.mipmap.fx_matching_012 to frameTime,  // 第一帧，持续100ms
//            R.mipmap.fx_matching_015 to frameTime,
            R.mipmap.fx_matching_018 to frameTime,
//            R.mipmap.fx_matching_021 to frameTime,
//            R.mipmap.fx_matching_024 to frameTime,  // 第一帧，持续100ms
            R.mipmap.fx_matching_027 to frameTime,
//            R.mipmap.fx_matching_030 to frameTime,
//            R.mipmap.fx_matching_033 to frameTime,
            R.mipmap.fx_matching_036 to frameTime,  // 第一帧，持续100ms
//            R.mipmap.fx_matching_039 to frameTime,
//            R.mipmap.fx_matching_042 to frameTime,
            R.mipmap.fx_matching_045 to frameTime,
//            R.mipmap.fx_matching_048 to frameTime,  // 第一帧，持续100ms
//            R.mipmap.fx_matching_051 to frameTime,
            R.mipmap.fx_matching_054 to frameTime,
//            R.mipmap.fx_matching_057 to frameTime,
//            R.mipmap.fx_matching_060 to frameTime,  // 第一帧，持续100ms
            R.mipmap.fx_matching_063 to frameTime,
//            R.mipmap.fx_matching_066 to frameTime,
//            R.mipmap.fx_matching_069 to frameTime,
            R.mipmap.fx_matching_072 to frameTime,  // 第一帧，持续100ms
//            R.mipmap.fx_matching_075 to frameTime,
//            R.mipmap.fx_matching_078 to frameTime,
            R.mipmap.fx_matching_081 to frameTime,
//            R.mipmap.fx_matching_084 to frameTime,
//            R.mipmap.fx_matching_087 to frameTime,
            R.mipmap.fx_matching_090 to frameTime,  // 第一帧，持续100ms
//            R.mipmap.fx_matching_093 to frameTime,
//            R.mipmap.fx_matching_096 to frameTime,
            R.mipmap.fx_matching_099 to frameTime,
//            R.mipmap.fx_matching_102 to frameTime,
//            R.mipmap.fx_matching_105 to frameTime,
            R.mipmap.fx_matching_108 to frameTime,  // 第一帧，持续100ms
//            R.mipmap.fx_matching_111 to frameTime,
//            R.mipmap.fx_matching_114 to frameTime,
            R.mipmap.fx_matching_117 to frameTime,
//            R.mipmap.fx_matching_120 to frameTime
        )
    }

    private var initialize = false
    private fun initView() {
        lifecycleScope.launch {
            mFrameAnimationUtil = FrameAnimationUtil(binding.ivDiscoverGif)
            // 动态创建帧列表（资源ID + 持续时间）
            mFrameAnimationUtil?.initFromFrames(frameList, lifecycleScope)
            mWifiP2PClientService?.addWifiP2PClientListener(mIWifiP2PClientListener)
            CoroutineScope(Dispatchers.Main).launch {
                startDiscoverTask()
            }
            binding.ivBack.setOnClickListener {
                finish()
            }
            binding.btnPairDevice.setOnClickListener {
                if (curStatus == PARI_SUCCESS_STATUS) {
                    finish()
                    return@setOnClickListener
                }
                startDiscoverTask()
            }
            binding.tvSkip.setOnClickListener {
                finish()
            }
            GlobalData.p2pConnectState.collect(lifecycleScope) {
                curStatus = if (it) {
                    PARI_SUCCESS_STATUS
                } else {
                    PARI_FAILED_STATUS
                }
                showStatus()
            }
        }
    }

    private fun startDiscoverTask() {
        curStatus = PARI_ING_STATUS
        showStatus()
        if (mDevice == null) {
            if (!SystemStateUtils.isWifiEnabled(this)) {
                com.blankj.utilcode.util.ToastUtils.showShort("wifi未开启")
                return
            }
            if (!initialize) {
                isFindDevice = false
                mWifiP2PClientService?.disconnect()
                // 初始化P2p 通道
                mWifiP2PClientService?.initialize {
                    initialize = it.isSuccess
                    if(it.isSuccess){
                        // 开始发现设备
                        mWifiP2PClientService?.startDiscoverPeers()
                    }
                }
            }
        } else {
            wifiP2pConnect(mDevice!!)
        }
    }

    private fun outTimeTask() {
        mainScope.launch {
            delay(10 * 1000)
            mWifiP2PClientService?.disconnect()
            mWifiP2PClientService?.initialize()
            if (curStatus != PARI_SUCCESS_STATUS) {
                curStatus = PARI_FAILED_STATUS
                showStatus()
            }
        }
    }

    private fun wifiP2pConnect(device: WifiP2pDevice) {
        Log.i(TAG, "wifiConnect: ")
        mDevice = device
        mWifiP2PClientService?.connectDevice(device) {}
    }

    override fun onResume() {
        super.onResume()
        if (!SystemStateUtils.isWifiEnabled(this)) {
            com.blankj.utilcode.util.ToastUtils.showShort("wifi未开启")
        }
    }

    private var lastConnectTime = 0L
    private var overTime = 1000L

    private val mIWifiP2PClientListener = object : IWifiP2PClientListener {
        // wifip2p 是否可用(包含数据通道)
        override fun onWifiP2pEnabled(enabled: Boolean) {
            Log.i(TAG, "onWifiP2pEnabled ${enabled}")
            lifecycleScope.launch {
                if (enabled && mDevice != null) {
                    DeviceLinkerManager.saveP2pDevice(mDevice!!)
                }
            }
        }

        // 获取当前WifiP2pDevice 设备信息
        override fun onSelfDeviceAvailable(device: WifiP2pDevice) {
//            binding.tvDeviceName.text = device.deviceName
        }

        // 发现设备列表
        override fun onPeersAvailable(devices: List<WifiP2pDevice>) {
//            LoadingManager.hideLoading()
            val newDevices = devices.filter { it.deviceName.contains("Glass3_") }
            if (newDevices.isEmpty()) {
                return
            }
            var currentTime = System.currentTimeMillis()
            if (currentTime - lastConnectTime > overTime && !GlobalData.p2pConnectState.value) {
                var needDevice =
                    newDevices.firstOrNull { device -> device.deviceName == DeviceLinkerManager.getDeviceName() }
                if (needDevice != null) {
                    wifiP2pConnect(needDevice)
                    lastConnectTime = currentTime
                }
            }
        }
    }

    private fun showStatus() {
        Log.i(TAG, "showStatus: $curStatus")
        when (curStatus) {
            PARI_PRE_STATUS -> {
                binding.tvTitleDiscoverDevice.text = "发现设备"
                binding.tvSubtitleDiscoverDevice.text = "设备蓝牙名称可从眼镜主页获取"
                binding.tvTitleDiscoverDevice.visibility = View.VISIBLE
                binding.tvSubtitleDiscoverDevice.visibility = View.VISIBLE
                binding.tvPairComplete.visibility = View.GONE
                binding.btnPairDevice.text = "配对"
                binding.ivShow.setImageResource(R.mipmap.icon_rokid_big)
                binding.ivDiscoverGif.visibility = View.GONE
            }

            PARI_ING_STATUS -> {
                binding.tvTitleDiscoverDevice.text = "配对中..."
                binding.tvSubtitleDiscoverDevice.text = "请耐心等待"
                binding.btnPairDevice.text = "配对中..."
                binding.tvTitleDiscoverDevice.visibility = View.VISIBLE
                binding.tvSubtitleDiscoverDevice.visibility = View.VISIBLE
                binding.tvPairComplete.visibility = View.GONE
//                Glide.with(this).load(R.mipmap.icon_pair).into(binding.ivShow)
                binding.ivDiscoverGif.visibility = View.VISIBLE
                mFrameAnimationUtil?.start()
            }

            PARI_SUCCESS_STATUS -> {
                binding.tvTitleDiscoverDevice.text = "配对成功"
                binding.tvSubtitleDiscoverDevice.visibility = View.INVISIBLE
//                binding.ivShow.setImageResource(R.mipmap.icon_pair_complete)
                binding.tvPairComplete.visibility = View.VISIBLE
                binding.ivDiscoverGif.visibility = View.GONE
                completePair()
            }

            PARI_FAILED_STATUS -> {
                binding.tvTitleDiscoverDevice.text = "配对失败"
                binding.tvSubtitleDiscoverDevice.text = "设备蓝牙名称可从眼镜主页获取"
                binding.tvTitleDiscoverDevice.visibility = View.VISIBLE
                binding.tvSubtitleDiscoverDevice.visibility = View.VISIBLE
                binding.tvPairComplete.visibility = View.GONE
//                binding.ivShow.setImageResource(R.mipmap.icon_rokid_big)
                binding.ivDiscoverGif.visibility = View.GONE
                binding.btnPairDevice.text = "重试"
            }
        }

        binding.tvDeviceName.text = DeviceLinkerManager.getDeviceName()
    }

    var completeJob: Job? = null
    var count_ = 3
    private fun completePair() {

        completeJob?.cancel()
        completeJob = lifecycleScope.launch {

            repeat(count_) { second ->
                val remainingTime = count_ - second
                binding.tvTitleDiscoverDevice.text = "配对成功"
                binding.btnPairDevice.text = "进入首页($remainingTime)"
                delay(1000) // 延迟1秒
            }
            finish()

        }
    }

    override fun initViewBinding(): ActivityDiscoverP2pDevice2Binding {
        return ActivityDiscoverP2pDevice2Binding.inflate(layoutInflater)
    }

    override fun onDestroy() {
        super.onDestroy()
        mWifiP2PClientService?.removeWifiP2PClientListener(mIWifiP2PClientListener)
        completeJob?.cancel()
    }

}
