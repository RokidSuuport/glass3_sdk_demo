package com.rokid.phone.ui.classicbt.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.RotateAnimation
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.rokid.phone.DeviceLinkerManager
import com.rokid.phone.base.ui.MviActivity
import com.rokid.phone.data.GlobalData
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

    private val enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            Handler(Looper.getMainLooper()).postDelayed({
                startScanBt()
            }, 500)
        } else {
            finish()
        }
    }

    private val bluetoothConnectPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                ensureBluetoothEnabled()
            } else {
                Toast.makeText(this, "请授权蓝牙连接权限", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    override fun render(state: TestState) {
    }

    var mDevice: BluetoothDeviceInfo? = null

    private var lastConnectedDeviceName: String = ""

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

        ensureBluetoothEnabled()
    }

    private fun ensureBluetoothEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothConnectPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        if (!SystemStateUtils.isBluetoothEnabled()) {
            com.blankj.utilcode.util.ToastUtils.showShort("蓝牙未开启")
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        startScanBt()
    }

    private fun startScanBt() {
//        LoadingManager.showLoading(this, "蓝牙设备搜索中...")
        CoroutineScope(Dispatchers.Main).launch {
            showAnimation(binding.ivLoadSmall, true)
            viewModel.sendIntent(TestIntent.startScanBt)
            Log.d(TAG, "------startScanBt")
        }
    }

    fun itemClick() {
        if (mDevice != null && DeviceLinkerManager.mConnectingBluetoothDevice != null &&
            DeviceLinkerManager.mConnectingBluetoothDevice!!.name == mDevice!!.name
            && GlobalData.btConnectState.value
        ) {
            finish()
        } else {
            if (GlobalData.btConnectState.value) {
                viewModel.sendIntent(TestIntent.disconnect)
            }
            DeviceLinkerManager.mConnectingBluetoothDevice = mDevice
            BtWifiConnectActivity.start(this, isConnetBt = true, isConnetP2p = false)
            finish()
        }
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
