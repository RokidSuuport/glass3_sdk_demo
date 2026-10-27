package com.rokid.phone

import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rokid.phone.base.BaseActivity
import com.rokid.phone.data.DeviceStatus
import com.rokid.phone.data.GlobalData
import com.rokid.phone.databinding.ActivitySettingBinding
import com.rokid.phone.utils.demoString
import kotlinx.coroutines.launch

class SettingActivity : BaseActivity<ActivitySettingBinding>() {
    override fun initViewBinding(): ActivitySettingBinding =
        ActivitySettingBinding.inflate(layoutInflater)

    override fun onInit(savedInstanceState: Bundle?) {
        binding.btnBack.setOnClickListener { finish() }
        binding.tvDeviceName.text = DeviceLinkerManager.getDeviceName()
        binding.tvAppVersion.text =
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        observeStatus()
        DeviceLinkerManager.refreshDeviceStatus()
    }

    private fun observeStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { DeviceStatus.deviceId.collect { binding.tvDeviceSn.text = it.ifBlank { "-" } } }
                launch { DeviceStatus.systemVersion.collect { binding.tvSystemVersion.text = it.ifBlank { "-" } } }
                launch { DeviceStatus.powerValue.collect { updateBattery() } }
                launch { DeviceStatus.charging.collect { updateBattery() } }
                launch { GlobalData.btConnectState.collect { updateBluetooth(it) } }
                launch { GlobalData.p2pConnectState.collect { updateP2p(it) } }
            }
        }
    }

    private fun updateBattery() {
        val power = DeviceStatus.powerValue.value
        binding.tvBatteryStatus.text = if (power <= 0) {
            demoString(R.string.demo_battery_unknown)
        } else {
            demoString(
                if (DeviceStatus.charging.value) {
                    R.string.demo_battery_charging_short
                } else {
                    R.string.demo_battery_level_short
                },
                power,
            )
        }
    }

    private fun updateP2p(connected: Boolean) {
        binding.tvP2PStatus.text = demoString(
            if (connected) R.string.demo_p2p_connected_short else R.string.demo_p2p_disconnected_short,
        )
    }

    private fun updateBluetooth(connected: Boolean) {
        binding.tvBluetoothStatus.text = demoString(
            if (connected) R.string.demo_bluetooth_connected else R.string.demo_bluetooth_disconnected,
        )
    }
}
