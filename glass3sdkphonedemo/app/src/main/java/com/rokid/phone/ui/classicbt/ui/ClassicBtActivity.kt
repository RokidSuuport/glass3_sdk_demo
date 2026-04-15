package com.rokid.phone.ui.classicbt.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.RotateAnimation
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.rokid.phone.DeviceLinkerManager.mConnectingBluetoothDevice
import com.rokid.phone.base.ui.MviActivity
import com.rokid.phone.databinding.ActivityDiscoverDeviceBinding
import com.rokid.phone.ui.BtWifiConnectActivity
import com.rokid.phone.ui.WifiP2PSettingActivity
import com.rokid.phone.ui.classicbt.adapter.BlueDeviceAdapter
import com.rokid.phone.ui.classicbt.adapter.OnBlueItemClickListener
import com.rokid.phone.ui.classicbt.model.TestEvent
import com.rokid.phone.ui.classicbt.model.TestIntent
import com.rokid.phone.ui.classicbt.model.TestState
import com.rokid.phone.ui.classicbt.repository.ClassicBtRepository
import com.rokid.phone.ui.classicbt.viewmodel.ClassicBtViewModel
import com.rokid.phone.utils.AnimationUtils
import com.rokid.phone.utils.LoadingManager
import com.rokid.phone.utils.SPUtil
import com.rokid.phone.utils.SpKeyConstant
import com.rokid.phone.utils.SystemStateUtils
import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.phone.ui.classicbt.model.BluetoothDeviceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 蓝牙扫描与跳转到蓝牙连接界面
 */
class ClassicBtActivity : MviActivity<ActivityDiscoverDeviceBinding, TestState, TestIntent, TestEvent, ClassicBtViewModel>() {

    private val deviceAdapter = BlueDeviceAdapter(arrayListOf())
    private val TAG = "ClassicBtActivity"
    override val viewModel = ClassicBtViewModel(ClassicBtRepository(PSecuritySDK))
    override fun render(state: TestState) {
    }

    var mDevice: BluetoothDeviceInfo? = null

    private var lastConnectedDeviceName: String = ""

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            finish()
            return
        }
        // 只有在Android 4.3版本以上才可以使用BLE功能；在Android 6.0 以上使用扫描方法必须获取位置权限
        if (requestCode == 111) {
            Handler(Looper.getMainLooper()).postDelayed({
//                LoadingManager.showLoading(this, "蓝牙设备搜索中...")
                CoroutineScope(Dispatchers.Main).launch {
                    showAnimation(binding.ivLoadSmall, true)
                    viewModel.sendIntent(TestIntent.startScanBt)
                    Log.d(TAG,"------onActivityResult------startScanBt")
                }
            }, 500)
        }
    }

    override fun onInit(savedInstanceState: Bundle?) {
        // 观察一次性事件
//        lifecycleScope.launch {
//            viewModel.event
//                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
//                .collect { event ->
//                    handleEvent(event)
//                }
//        }

        binding.ivBack.setOnClickListener {
            finish()
        }

        binding.ivLoadReflesh.setOnClickListener {
            binding.tvListTip2.visibility = View.GONE
            showAnimation(binding.ivLoadSmall, true)
            showAnimation(binding.ivLoadReflesh, true)
            viewModel.sendIntent(TestIntent.startScanBt)
        }

        binding.rvDiscoveredDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDiscoveredDevices.adapter = deviceAdapter

        val dividerItemDecoration = DividerItemDecoration(
            binding.rvDiscoveredDevices.context,
            DividerItemDecoration.VERTICAL
        )
        binding.rvDiscoveredDevices.addItemDecoration(dividerItemDecoration)
        binding.tvSkip.setOnClickListener {
            finish()
        }
        deviceAdapter.onItemClickListener = object : OnBlueItemClickListener {

            override fun onItemClick(position: Int, device: BluetoothDeviceInfo) {
                Log.d(TAG, "onItemClick::" + device.name + " address:" + device.address)
                val name = device.name
                lastConnectedDeviceName = name
                mDevice = device
                itemClick()
            }

            override fun onSelect(device: BluetoothDeviceInfo) {
                val name = device.name
                lastConnectedDeviceName = name
                mDevice = device
                Log.d(TAG, "onSelect::" + name + " address:" + device.address)
            }
        }

        if (!SystemStateUtils.isBluetoothEnabled()) {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            com.blankj.utilcode.util.ToastUtils.showShort("蓝牙未开启")
            startActivityForResult(intent, 111)
            return
        }
//        LoadingManager.showLoading(this, "蓝牙设备搜索中...")
        CoroutineScope(Dispatchers.Main).launch {
            showAnimation(binding.ivLoadSmall, true)
            viewModel.sendIntent(TestIntent.startScanBt)
        }
    }

    fun itemClick() {
        viewModel.sendIntent(TestIntent.disconnect)
        lifecycleScope.launch {
            mConnectingBluetoothDevice = mDevice
        }
        BtWifiConnectActivity.start(this, isConnetBt = true, isConnetP2p = false)
        finish()
    }

    override fun initViewBinding(): ActivityDiscoverDeviceBinding {
        return ActivityDiscoverDeviceBinding.inflate(layoutInflater)
    }

    override fun handleEvent(event: TestEvent) {
        when (event) {
            is TestEvent.ShowBlueToothList -> {
                LoadingManager.hideLoading()
                deviceAdapter.setNewData(event.list)
                if (event.list.size > 0) {
                    binding.tvListTip2.visibility = View.GONE
                }
            }

            is TestEvent.FinishBlueToothList -> {
                LoadingManager.hideLoading()
                showAnimation(binding.ivLoadReflesh, false)
                deviceAdapter.setNewData(event.list)
                if (event.list.isEmpty()) {
                    goNoFindBluetooth()
                } else {
                    binding.tvListTip2.visibility = View.GONE
                }
                showAnimation(binding.ivLoadSmall, false)
            }

            is TestEvent.ShowToast -> {

            }

            is TestEvent.UpdateConnectState -> {
//                GlobalData.setBtConnectState(event.isConnect)
                Log.d(TAG, "handleEvent::" + event.isConnect)
                LoadingManager.hideLoading()
                if (event.isConnect) {
                    startActivity(Intent(this, WifiP2PSettingActivity::class.java))
                    finish()
                } else {
                    Toast.makeText(this, "连接失败", Toast.LENGTH_SHORT).show()
                }
            }

            else -> {}
        }
    }


    private fun goNoFindBluetooth() {
        //首次配对成功, 自动进入新手教学
        Log.d(TAG, "goNoFindBluetooth-->")
        binding.tvListTip2.visibility = View.VISIBLE
        if (SPUtil.getInstance(this).getBoolean(
                SpKeyConstant.is_no_find,
                true
            )
        ) {
            SPUtil.getInstance(this).putBoolean(
                SpKeyConstant.is_no_find,
                false
            )
            startActivity(Intent(this, BluetoothNoFindActivity::class.java))
        }
    }

    override fun onDestroy() {
        showAnimation(binding.ivLoadReflesh, false)
        showAnimation(binding.ivLoadSmall, false)
        LoadingManager.hideLoading()
        super.onDestroy()
    }


    var rotationAnim: RotateAnimation? = null

    private fun showAnimation(view: View, start: Boolean) {
        if (start) {
            if (rotationAnim == null) {
                rotationAnim = AnimationUtils.createInfiniteRotation(2000)
            }
            AnimationUtils.startAnimation(view, rotationAnim!!)
        } else {
            AnimationUtils.stopAnimation(view)
        }
    }

}