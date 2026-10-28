package com.rokid.phone.ui

import com.rokid.phone.utils.demoString

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.blankj.utilcode.util.Utils
import com.rokid.phone.R
import com.rokid.phone.base.BaseActivity
import com.rokid.phone.databinding.ActivityDeviceLinkerBinding
import com.rokid.phone.utils.SystemGlobalConstant
import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.security.phone.sdk.api.bluetooth.ring.api.IBTRingClientListener
import com.rokid.security.phone.sdk.base.utils.log.L
import com.rokid.security.sdk.base.common.RingExtra
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 指环连接界面
 * Ring connection screen.
 */
class DeviceLinerActivity : BaseActivity<ActivityDeviceLinkerBinding>(), OnItemClickListener {

    private lateinit var availableDevicesAdapter: DeviceLinkerAdapter

    private var isConnectionEnabled = true
    private val TAG = "DeviceLinerActivity"
    private val availableDevices = mutableListOf<BluetoothDevice>()
    private var selectPosition = -1

    override fun onInit(savedInstanceState: Bundle?) {
        setupViews()
        setupRecyclerView()
        updateConnectionState()
    }

    private fun setupViews() {
        // 设置Switch监听
        // Set the Switch listener.
        binding.layoutTitle.ivBack.setOnClickListener {
            finish()
        }
        binding.switchConnection.setOnCheckedChangeListener { _, isChecked ->
            isConnectionEnabled = isChecked
            updateConnectionState()
        }

        // 设置外设连接区域点击事件
        // Set the accessory connection area click listener.
        binding.clDeviceLinker.setOnClickListener {
            binding.switchConnection.toggle()
        }
        showLinkDevice()
    }

    @SuppressLint("SetTextI18n")
    private fun showLinkDevice() {
        Log.d(TAG, "showLinkDevice glassRingConnected: " + SystemGlobalConstant.glassRingConnected + " name：" + SystemGlobalConstant.glassRingBluetoothDeviceName)
        CoroutineScope(Dispatchers.Main).launch {
            if (!SystemGlobalConstant.glassRingConnected && SystemGlobalConstant.glassRingBluetoothDeviceName?.isEmpty() == true) {
                binding.flConnectDevice.visibility = View.GONE
                return@launch
            }
            if (SystemGlobalConstant.glassRingConnected) {
                binding.flConnectDevice.visibility = View.VISIBLE
                binding.tvLinkDevice.text = SystemGlobalConstant.glassRingBluetoothDeviceName + demoString(R.string.demo_connected_2)
                binding.tvLinkDevice.setTextColor(ContextCompat.getColor(Utils.getApp(), R.color.green))
            } else {
                binding.flConnectDevice.visibility = View.VISIBLE
                binding.tvLinkDevice.text = SystemGlobalConstant.glassRingBluetoothDeviceName + demoString(R.string.demo_disconnected_2)
                binding.tvLinkDevice.setTextColor(ContextCompat.getColor(Utils.getApp(), R.color.red))
            }
        }
    }

    private val btRingListener = object : IBTRingClientListener {
        @SuppressLint("MissingPermission")
        override fun onDeviceFound(device: BluetoothDevice) {
            L.d(TAG, "onDeviceFound: $device")
            if (!availableDevices.contains(device)) {
                availableDevices.add(device)
                // 在主线程更新UI
                // Update the UI on the main thread.
                runOnUiThread {
                    availableDevicesAdapter.setNewData(availableDevices.map {
                        ListItem.DeviceItem(it.name ?: demoString(R.string.demo_unknown_device_status_34), false)
                    })
                }
            }
        }

        override fun onScanFinished() {
            L.d(TAG, "onScanFinished")
        }

        override fun onConnect(extra: RingExtra) {
            Log.e(TAG, "----->指环连接结果,${extra.name + " " + extra.address + ",连接状态：" + extra.connectSuccess + " " + extra.methodName}")
            ringConnect(extra.connectSuccess)
        }

        override fun onConnect(success: Boolean) {
            L.d(TAG, "----->指环连接结果: $success")
            ringConnect(success)
        }
    }

    private fun ringConnect(success: Boolean) {
        if (selectPosition == -1) {
            return
        }
        if (success) {
            availableDevicesAdapter.updateItemStatus(selectPosition, isConnected = true, isConnecting = false)
            Toast.makeText(this@DeviceLinerActivity, demoString(R.string.demo_connected_successfully), Toast.LENGTH_SHORT).show()
        } else {
            availableDevicesAdapter.updateItemStatus(selectPosition, isConnected = false, isConnecting = false)
            if (SystemGlobalConstant.glassRingConnected) {
                Toast.makeText(this@DeviceLinerActivity, demoString(R.string.demo_connection_interrupted), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@DeviceLinerActivity, demoString(R.string.demo_connection_failed), Toast.LENGTH_SHORT).show()
            }
        }
        SystemGlobalConstant.glassRingConnected = success
        showLinkDevice()
    }

    private fun setupRecyclerView() {
        availableDevicesAdapter = DeviceLinkerAdapter().apply {
            onItemClickListener = this@DeviceLinerActivity
        }

        binding.rvAvailableDevices.apply {
            layoutManager = LinearLayoutManager(this@DeviceLinerActivity)
            adapter = availableDevicesAdapter
        }

        PSecuritySDK.getBluetoothRingService()?.addClientListener(btRingListener)

        availableDevicesAdapter.setNewData(emptyList())
        val dividerItemDecoration = DividerItemDecoration(
            binding.rvAvailableDevices.context,
            DividerItemDecoration.VERTICAL
        )
        binding.rvAvailableDevices.addItemDecoration(dividerItemDecoration)
    }

    private fun updateConnectionState() {
        val visibility = if (isConnectionEnabled) View.VISIBLE else View.GONE
        binding.tvAvailableDevicesHeader.visibility = visibility
        binding.rvAvailableDevices.visibility = visibility
        if (visibility == View.VISIBLE) {
            fetchLinkersData()
        } else {
            PSecuritySDK.getBluetoothRingService()?.stopScan()
            if (SystemGlobalConstant.glassRingConnected) {
                PSecuritySDK.getBluetoothRingService()?.disconnect()
            }
            SystemGlobalConstant.glassRingConnected = false
            showLinkDevice()
        }
    }

    private fun fetchLinkersData() {
        L.d(TAG, "fetchLinkersData")
        PSecuritySDK.getBluetoothRingService()?.startScan(emptyList(), 10000)
    }

    override fun initViewBinding(): ActivityDeviceLinkerBinding {
        return ActivityDeviceLinkerBinding.inflate(layoutInflater)
    }

    @SuppressLint("MissingPermission")
    override fun onConnectClick(deviceItem: ListItem.DeviceItem, position: Int) {
        val deviceToConnect = availableDevices.find { it.name == deviceItem.name }
        if (deviceToConnect == null) {
            Toast.makeText(this, demoString(R.string.demo_device_not_found), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            SystemGlobalConstant.glassRingBluetoothDeviceName = deviceToConnect.name
            availableDevicesAdapter.updateItemStatus(position, isConnected = false, isConnecting = true)
            PSecuritySDK.getBluetoothRingService()?.stopScan()
            delay(100)
            PSecuritySDK.getBluetoothRingService()?.connectToServer(deviceToConnect)
            selectPosition = position
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        PSecuritySDK.getBluetoothRingService()?.removeClientListener(btRingListener)
        PSecuritySDK.getBluetoothRingService()?.stopScan()
    }

}
