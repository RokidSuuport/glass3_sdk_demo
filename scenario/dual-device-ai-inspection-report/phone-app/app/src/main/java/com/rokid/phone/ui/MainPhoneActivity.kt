package com.rokid.phone.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rokid.phone.DeviceLinkerManager
import com.rokid.phone.GalleryActivity
import com.rokid.phone.R
import com.rokid.phone.SettingActivity
import com.rokid.phone.ai.AiVisionActivity
import com.rokid.phone.base.BaseActivity
import com.rokid.phone.data.DeviceStatus
import com.rokid.phone.data.GlobalEvent
import com.rokid.phone.data.GlobalData
import com.rokid.phone.databinding.LayoutMainPhoneBinding
import com.rokid.phone.ui.classicbt.ui.ClassicBtActivity
import com.rokid.phone.utils.demoString
import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.security.phone.sdk.base.data.EngineParam
import com.rokid.security.phone.sdk.base.data.EnvType
import com.rokid.security.phone.sdk.base.data.NetServiceType
import com.rokid.security.phone.sdk.base.data.UserAuthInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 手机端入口：准备 SDK、连接眼镜，然后进入查验记录与报告页面。
 */
class MainPhoneActivity : BaseActivity<LayoutMainPhoneBinding>() {
    private val phoneEngineService by lazy { PSecuritySDK.getMobileEngineService() }
    private var autoConnectJob: Job? = null
    private var sdkReady = false

    private val requiredPermissions: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) {
            continueStartup()
        } else {
            Toast.makeText(this, demoString(R.string.demo_permissions_denied), Toast.LENGTH_LONG).show()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (BluetoothAdapter.getDefaultAdapter()?.isEnabled == true) {
            continueStartup()
        } else {
            Toast.makeText(this, demoString(R.string.demo_bluetooth_is_off), Toast.LENGTH_LONG).show()
        }
    }

    override fun initViewBinding(): LayoutMainPhoneBinding =
        LayoutMainPhoneBinding.inflate(layoutInflater)

    override fun onInit(savedInstanceState: Bundle?) {
        binding.btnConnect.setOnClickListener {
            autoConnectJob?.cancel()
            startActivity(Intent(this, ClassicBtActivity::class.java))
        }
        binding.btnAiVision.setOnClickListener { openAiVision() }
        binding.btnP2P.setOnClickListener {
            if (GlobalData.p2pConnectState.value) {
                DeviceLinkerManager.disconnectP2p()
            } else {
                DeviceLinkerManager.connectP2p()
            }
        }
        binding.btnGallery.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingActivity::class.java))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    GlobalData.btConnectState.collect { updateConnectionState(it) }
                }
                launch {
                    GlobalData.sdkInitState.collect {
                        sdkReady = it
                        updateConnectionState(GlobalData.btConnectState.value)
                        if (it) scheduleAutoConnect()
                    }
                }
                launch {
                    GlobalData.p2pConnectState.collect { updateP2pState(it) }
                }
                launch {
                    DeviceStatus.powerValue.collect { updateBattery() }
                }
                launch {
                    DeviceStatus.charging.collect { updateBattery() }
                }
            }
        }
        lifecycleScope.launch {
            GlobalEvent.autoConnectionEvent.collect { scheduleAutoConnect() }
        }
        continueStartup()
    }

    private fun continueStartup() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Toast.makeText(this, demoString(R.string.demo_this_device_does_not_support_bluetooth), Toast.LENGTH_LONG).show()
            return
        }
        if (!adapter.isEnabled) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        initializeSdk()
    }

    private fun initializeSdk() {
        if (phoneEngineService.isInit()) {
            onSdkReady()
            return
        }
        lifecycleScope.launch {
            val param = EngineParam(
                clientIds = arrayListOf("SecurityPhone", "GlassSample"),
                userAuthInfo = UserAuthInfo(appId = "", secret = ""),
                banServiceList = listOf<NetServiceType>(NetServiceType.TranslateService),
                envType = EnvType.Companion.PUBLIC,
            )
            phoneEngineService.initSDK(param) { result ->
                runOnUiThread {
                    if (result.isSuccess) {
                        onSdkReady()
                    } else {
                        binding.tvConnectionState.text = demoString(R.string.demo_sdk_init_failed)
                        Log.e(TAG, "Phone SDK initialization failed")
                    }
                }
            }
        }
    }

    private fun onSdkReady() {
        if (GlobalData.sdkInitState.value) return
        GlobalData.setSdkInitState(true)
        DeviceLinkerManager.loadSavedDevice()
        DeviceLinkerManager.initialize()
    }

    private fun scheduleAutoConnect() {
        if (GlobalData.btConnectState.value) return
        autoConnectJob?.cancel()
        autoConnectJob = lifecycleScope.launch {
            delay(500)
            val device = DeviceLinkerManager.mBluetoothDevice ?: return@launch
            DeviceLinkerManager.connectBt(device) {}
        }
    }

    private fun updateConnectionState(connected: Boolean) {
        binding.tvDeviceName.text = DeviceLinkerManager.getDeviceName()
        binding.tvConnectionState.text = demoString(
            if (connected) R.string.demo_bluetooth_connected else R.string.demo_bluetooth_disconnected,
        )
        binding.ivConnectStatus.setImageResource(
            if (connected) R.mipmap.icon_pair_complete else R.mipmap.icon_to_connect2,
        )
        binding.btnConnect.setText(
            if (connected) R.string.demo_reconnect_glasses else R.string.demo_connect_glasses,
        )
        binding.btnAiVision.isEnabled = connected && sdkReady
        binding.btnAiVision.alpha = if (connected && sdkReady) 1f else 0.45f
        binding.btnP2P.isEnabled = connected
        updateP2pState(GlobalData.p2pConnectState.value)
    }

    private fun updateP2pState(connected: Boolean) {
        binding.tvP2PState.text = demoString(
            if (connected) R.string.demo_p2p_connected_short else R.string.demo_p2p_disconnected_short,
        )
        binding.btnP2P.setText(
            if (connected) R.string.demo_disconnect_p2p else R.string.demo_connect_p2p,
        )
        binding.btnP2P.isEnabled = GlobalData.btConnectState.value
    }

    private fun updateBattery() {
        val power = DeviceStatus.powerValue.value
        binding.tvBattery.text = if (power <= 0) {
            demoString(R.string.demo_battery_unknown)
        } else {
            demoString(
                if (DeviceStatus.charging.value) {
                    R.string.demo_battery_charging
                } else {
                    R.string.demo_battery_level
                },
                power,
            )
        }
    }

    private fun openAiVision() {
        if (!GlobalData.btConnectState.value) {
            Toast.makeText(this, demoString(R.string.demo_connect_glasses_first), Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, AiVisionActivity::class.java))
    }

    override fun onDestroy() {
        autoConnectJob?.cancel()
        if (GlobalData.sdkInitState.value) {
            DeviceLinkerManager.release()
            phoneEngineService.destroy()
        }
        GlobalData.reset()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "MainPhoneActivity"
    }
}
