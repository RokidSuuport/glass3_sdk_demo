package com.rokid.phone.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.blankj.utilcode.util.ToastUtils
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.rokid.phone.DeviceLinkerManager
import com.rokid.phone.DeviceLinkerManager.getBlueToothDevice
import com.rokid.phone.DeviceLinkerManager.getP2pDevice
import com.rokid.phone.DeviceLinkerManager.mBluetoothDevice
import com.rokid.phone.DeviceLinkerManager.mConnectingBluetoothDevice
import com.rokid.phone.DeviceLinkerManager.mWifiP2pDevice
import com.rokid.phone.GalleryActivity
import com.rokid.phone.MessageReceiveActivity
import com.rokid.phone.MyApplication
import com.rokid.phone.R
import com.rokid.phone.SendMessageActivity
import com.rokid.phone.SettingActivity
import com.rokid.phone.VideoReceiveActivity
import com.rokid.phone.base.BaseActivity
import com.rokid.phone.data.GlobalData
import com.rokid.phone.data.GlobalEvent
import com.rokid.phone.databinding.LayoutMainPhoneBinding
import com.rokid.phone.system.ui.SystemOtaActivity
import com.rokid.phone.ui.classicbt.ui.ClassicBtActivity
import com.rokid.phone.utils.SPUtil
import com.rokid.phone.utils.SpKeyConstant
import com.rokid.phone.utils.SystemGlobalConstant
import com.rokid.phone.utils.SystemStateUtils
import com.rokid.phone.utils.companion.AssociatedDeviceCompat
import com.rokid.phone.utils.companion.UuidDeviceDiscoveryManager
import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.security.phone.sdk.base.data.EngineParam
import com.rokid.security.phone.sdk.base.data.EnvType
import com.rokid.security.phone.sdk.base.data.NetServiceType
import com.rokid.security.phone.sdk.base.data.UserAuthInfo
import com.rokid.security.phone.sdk.base.utils.other.ktx.collect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pub.devrel.easypermissions.EasyPermissions
import pub.devrel.easypermissions.PermissionRequest
import kotlin.math.roundToInt

/**
 * Author: zhangshengwei
 * Date: 2025/6/23
 */
class MainPhoneActivity : BaseActivity<LayoutMainPhoneBinding>(), EasyPermissions.PermissionCallbacks {

    private lateinit var activity: Activity
    private val TAG = "MainPhoneActivity"
    private var volumeTime = 0L
    private var brightnessTime = 0L
    private var isResume = false

    @Volatile
    private var isDeviceDiscovered = false

    /**
     * 获取手机端SDK引擎服务
     */
    private val mPhoneEngineService by lazy {
        PSecuritySDK.getMobileEngineService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG,"--------------MainPhoneActivity---")
    }

    private val permissions: Array<String> = run {
        val list = arrayListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 发现附近的 Wi-Fi 设备权限
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            // 获取用户大概地理位置
            list.add(Manifest.permission.ACCESS_FINE_LOCATION)
            list.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        // 如果是安卓12,需要蓝牙扫描和连接的运行时权限,否则,无法使用蓝牙功能
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // 如果版本低于Android 12,蓝牙扫描需要获取位置信息,添加旧版蓝牙权限和定位权限
            list.add(Manifest.permission.BLUETOOTH)
            list.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        list.toTypedArray()
    }

    private lateinit var deviceManager: CompanionDeviceManager
    private fun initSDK() {
        lifecycleScope.launch {
            // SecurityPhone: 系统分配的ID；GlassSample: 自定义ID，手机端与眼镜端需保持一致。
            val clientIds = arrayListOf("SecurityPhone", "GlassSample")

            // TODO: 在线语音转文本和文本转语音秘钥，appId填写accessKey，secret填写secretKey简称AKSK,找商务申请。
            // TODO: https://x-docs.rokid.com/docs/%E5%8A%9F%E8%83%BD%E7%A4%BA%E4%BE%8B.html#_9-%E5%88%9D%E5%A7%8B%E5%8C%96%E5%9C%A8%E7%BA%BF%E8%AF%AD%E9%9F%B3%E8%BD%AC%E6%96%87%E6%9C%AC%E5%92%8C%E6%96%87%E6%9C%AC%E8%BD%AC%E8%AF%AD%E9%9F%B3
            val userAuthInfo = UserAuthInfo("", "")

            // 不初始化翻译服务 ，初始化语音转文本和文本转语音
            val banServiceList: List<NetServiceType> = arrayListOf(NetServiceType.TranslateService)

            // ALL表示 所有服务都不初始化
//             val banServiceList: List<NetServiceType>? = arrayListOf(NetServiceType.ALL)

            // PUBLIC表示 公网环境
            val param = EngineParam(clientIds = clientIds, userAuthInfo = userAuthInfo, banServiceList = banServiceList, envType = EnvType.Companion.PUBLIC)
            mPhoneEngineService.initSDK(param) {
                if (it.isSuccess) {
                    Log.d(TAG, "手机端初始化SDK成功")
                    // SDK初始化成功回调
                    GlobalData.setSdkInitState(it.isSuccess)
                    // 设置系统信息监听
                    DeviceLinkerManager.addSystemInfoListener(systemInfoCallback)
                    DeviceLinkerManager.addMessageListener()
                    DeviceLinkerManager.initObserver()
                    autoConnectBt()
                } else {
                    Log.e(TAG, "手机端初始化SDK失败")
                }
            }
        }

    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        autoConnectBtJob?.cancel()
        GlobalData.reset()
        DeviceLinkerManager.release()
        mPhoneEngineService.destroy()
    }

    fun initDevice() {
        SystemGlobalConstant.deviceId =
            SPUtil.getInstance(MyApplication.instance.baseContext).getString(SpKeyConstant.DEVICE_ID, "")
        CoroutineScope(Dispatchers.IO).launch {
            mWifiP2pDevice = getP2pDevice()
            mBluetoothDevice = getBlueToothDevice()
            mConnectingBluetoothDevice = mBluetoothDevice
        }
    }


    override fun onResume() {
        super.onResume()
        if (!SystemStateUtils.isBluetoothEnabled()) {
            ToastUtils.showShort("蓝牙未开启")
        }
        isResume = true
        connectStatus(GlobalData.btConnectState.value, GlobalData.p2pConnectState.value)
    }

    private fun openBluetooth() {
        // 初始化蓝牙适配器
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "设备不支持蓝牙", Toast.LENGTH_SHORT).show()
            return
        }
        // 开启蓝牙（若未开启）
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            // 注册开启蓝牙的回调（可选）
            val enableBtLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    // 蓝牙已开启，可执行扫描、连接等操作
                    getPermission()
                } else {
                    Toast.makeText(this, "请开启蓝牙以使用功能", Toast.LENGTH_SHORT).show()
                }
            }
            enableBtLauncher.launch(enableBtIntent)
            return
        }
        getPermission(true)
    }

    private fun getPermission(isFirst: Boolean = false) {
        val hasPermissions = EasyPermissions.hasPermissions(this@MainPhoneActivity, *permissions)
        if (hasPermissions) {
            tryCount = 0
            Log.e(TAG, "----->已获取所有权限, 是否初始化SDK=" + mPhoneEngineService.isInit())
            showIgnoreBatteryOptimizations()
            if (!mPhoneEngineService.isInit()) {
                initDevice()
                initSDK()
            }
        } else {
            checkPermissionList(isFirst)
        }
    }

    var dialogBattery: BottomSheetDialog? = null
    fun showIgnoreBatteryOptimizations() {
        if (dialogBattery != null && dialogBattery?.isShowing == true) {
            return
        }
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val isIgnoring = powerManager.isIgnoringBatteryOptimizations(packageName)
        if (isIgnoring) {
            return
        }
        dialogBattery = BottomPromptDialog.show(activity, "请授权", "为了保证P2P连接稳定，需要避免电池优化") {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:${packageName}")
            startActivity(intent)
        }
    }

    override fun onStop() {
        super.onStop()
        isResume = false
        autoConnectBtJob?.cancel()

    }

    private var autoConnectBtJob: Job? = null

    /**
     * 自动连接上次连接的蓝牙设备
     */
    private fun autoConnectBt(isDelay: Boolean = false) {
        autoConnectBtJob?.cancel()
        autoConnectBtJob = lifecycleScope.launch {
            val bluetoothDevice = mBluetoothDevice
            if (isDelay) {
                delay(3000)
            }
            if ((!GlobalData.btConnectState.value) && bluetoothDevice != null) {
                Log.d(TAG, "--->蓝牙连接中...")
                if (!GlobalData.btConnectState.value) {
                    binding.ivConnect.setImageResource(R.mipmap.icon_connecting)
                }
                DeviceLinkerManager.connectBt(bluetoothDevice) {
                    autoConnectBt(true)
                }
            }
        }
    }

    override fun onInit(savedInstanceState: Bundle?) {
        activity = this

        binding.tvDeviceName.text = DeviceLinkerManager.getDeviceName()

        binding.btAppInstall.setOnClickListener {
            if (!GlobalData.btConnectState.value) {
                Toast.makeText(this, "请先完成蓝牙设备连接", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
        }

        binding.btMessageReceive.setOnClickListener {
            if (!GlobalData.btConnectState.value) {
                Toast.makeText(this, "请先完成蓝牙设备连接", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startActivity(Intent(this@MainPhoneActivity, MessageReceiveActivity::class.java))
        }

        binding.btMessageSend.setOnClickListener {
            if (!GlobalData.btConnectState.value) {
                Toast.makeText(this, "请先完成蓝牙设备连接", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startActivity(Intent(this@MainPhoneActivity, SendMessageActivity::class.java))
        }

        binding.ivSetting.setOnClickListener {
            startActivity(Intent(this, SettingActivity::class.java))
        }

        binding.ivAdd.setOnClickListener {
            startActivity(Intent(this, ClassicBtActivity::class.java))
        }

        binding.ivConnect.setOnClickListener {
            Log.i(TAG, "ivConnect setOnClickListener: ${GlobalData.btConnectState.value}")
            if (!GlobalData.btConnectState.value) {
                if (mBluetoothDevice != null) {
                    mConnectingBluetoothDevice = mBluetoothDevice
                    BtWifiConnectActivity.start(this, isConnetBt = true, isConnetP2p = false)
                } else {
                    startActivity(Intent(this, ClassicBtActivity::class.java))
                }
            }
        }

        binding.btAlbum.setOnClickListener {
            if (!GlobalData.btConnectState.value) {
                Toast.makeText(this, "请先完成蓝牙设备连接", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startActivity(Intent(this@MainPhoneActivity, GalleryActivity::class.java))
        }

        binding.btP2PConnect.setOnClickListener {
            if (!GlobalData.btConnectState.value) {
                Toast.makeText(this, "请先完成蓝牙设备连接", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!GlobalData.p2pConnectState.value) {
                BtWifiConnectActivity.start(this, isConnetBt = false, isConnetP2p = true)
            }else{
                PSecuritySDK.getWifiP2PClientService()?.disconnect()
            }
        }

        binding.btVideoReceive.setOnClickListener {
            if (!GlobalData.btConnectState.value) {
                Toast.makeText(this, "请先完成蓝牙设备连接", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            DeviceLinkerManager.getGlassPowerInfoMsg()
            startActivity(Intent(this@MainPhoneActivity, VideoReceiveActivity::class.java))
        }

        binding.clOta.setOnClickListener {
            if (!GlobalData.btConnectState.value) {
                Toast.makeText(this, "请先完成蓝牙设备连接", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!GlobalData.p2pConnectState.value) {
                Toast.makeText(this, "请先完成P2P连接", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, SystemOtaActivity::class.java))
        }

        binding.brightnessSeekbar.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(p0: SeekBar?, p1: Int, p2: Boolean) {
                Log.d(
                    TAG,
                    "brightnessSeekbar# onProgressChanged-> p1: ${p1}; p2: ${p2};  p0.progress: ${p0?.progress} "
                )
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {
                Log.d(
                    TAG,
                    "brightnessSeekbar# onStartTrackingTouch-> p0.progress: ${p0?.progress} "
                )
            }

            override fun onStopTrackingTouch(p0: SeekBar?) {
                if (!GlobalData.btConnectState.value) {
                    return
                }
                var progress = p0?.progress ?: 10
                progress = (progress * 255f / 15).roundToInt()
                Log.d(TAG, "brightnessSeekbar# onStopTrackingTouch-> p0.progress: ${progress} ")
                DeviceLinkerManager.setBrightness(progress)
                brightnessTime = System.currentTimeMillis()
            }

        })

        binding.volumeSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                p0: SeekBar?,
                p1: Int,
                p2: Boolean
            ) {
                Log.d(
                    TAG,
                    "volumeSeekbar# onProgressChanged-> p1: ${p1}; p2: ${p2};  p0.progress: ${p0?.progress} "
                )
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {
                Log.d(TAG, "volumeSeekbar# onStartTrackingTouch-> p0.progress: ${p0?.progress} ")
            }

            override fun onStopTrackingTouch(p0: SeekBar?) {
                Log.d(TAG, "volumeSeekbar# onStopTrackingTouch-> p0.progress: ${p0?.progress} ")
                if (!GlobalData.btConnectState.value) {
                    return
                }
                DeviceLinkerManager.setVolume(p0?.progress ?: 0)
                volumeTime = System.currentTimeMillis()
            }
        })
        initObserver()
        openBluetooth()
    }

    override fun initViewBinding(): LayoutMainPhoneBinding {
        return LayoutMainPhoneBinding.inflate(layoutInflater)
    }

    private var systemInfoCallback: () -> Unit = {
        if (!isFinishing) {
            binding.volumeSeekbar.max = SystemGlobalConstant.maxVolume
            powerShow()
            binding.volumeSeekbar.progress = SystemGlobalConstant.curVolume
            binding.brightnessSeekbar.progress = (15f * SystemGlobalConstant.brightness / 255).roundToInt()
        }
    }

    private fun initObserver() {
        GlobalData.btConnectState.collect(lifecycleScope) {
            connectStatus(it, GlobalData.p2pConnectState.value)
            if (it) {
                //检查眼睛端协议版本
                lifecycleScope.launch {
                    delay(800)
                    binding.tvDeviceName.text = DeviceLinkerManager.getDeviceName()
                    DeviceLinkerManager.getGlassSystemInfoMsg()
                }
            }
        }

        GlobalData.p2pConnectState.collect(lifecycleScope) {
            connectStatus(GlobalData.btConnectState.value, it)
            binding.tvDeviceName.text = DeviceLinkerManager.getDeviceName()
            if (it && GlobalData.btConnectState.value) {
                // true 设置P2P保持长连接,false关闭P2P长连接
                lifecycleScope.launch {
                    delay(800)
                    PSecuritySDK.getWifiP2PClientService()?.getIP2PConnectControl()?.getKeepP2PConnectState({ it ->
                        // 没有设置P2P长连接
                        if (!it) {
                            Log.d(TAG, "--->长连接未开启")
                            // true 设置P2P保持长连接,false关闭P2P长连接
                            PSecuritySDK.getWifiP2PClientService()?.getIP2PConnectControl()
                                ?.setKeepP2PConnect(true, { isSuccess ->
                                    if (isSuccess) {
                                        Log.d(TAG, "--->长连接设置成功")
                                    } else {
                                        Log.d(TAG, "--->长连接设置失败")
                                    }
                                })
                        } else {
                            Log.d(TAG, "--->长连接已开启")
                        }
                    }, { errMsg: String, code: Int ->
                    })
                }

            }
        }

        //取消重连的逻辑
        GlobalEvent.connectionRejectedEvent.collect(lifecycleScope) {
            autoConnectBtJob?.cancel()
        }

        GlobalEvent.autoConnectionEvent.collect(lifecycleScope) {
            autoConnectBt()
        }
    }

    var tryCount = 0
    private fun checkPermissionList(isFirst: Boolean = false): Boolean {
        if (tryCount > 1) {
            return false
        }
        tryCount++
        val deniedList = mutableListOf<String>()
        for (perm in permissions) {
            if (!EasyPermissions.hasPermissions(this, perm)) {
                deniedList.add(perm)
            }
        }
        if (deniedList.isNotEmpty()) {
            if (!isFirst) {
                Log.e(TAG, "以下权限未获取 (${deniedList.size} 个):")
                deniedList.forEach { Log.e(TAG, "❌ 未授权权限: $it") }
            }
            EasyPermissions.requestPermissions(
                PermissionRequest.Builder(this, 999, *deniedList.toTypedArray())
                    .setRationale("应用需要以下权限才能正常工作")
                    .setPositiveButtonText("确定")
                    .setNegativeButtonText("取消")
                    .setTheme(android.R.style.Theme_DeviceDefault_Light_Dialog)
                    .build()
            )
            return false
        }
        Log.i(TAG, "✅ 所有权限已获取")
        return true
    }

    // -------------------------- 必须重写：转发系统权限结果 --------------------------
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 关键：将系统回调结果转发给 EasyPermissions，框架会自动解析并触发上面的回调方法
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
    }

    override fun onPermissionsGranted(requestCode: Int, perms: List<String?>) {
        if (requestCode == 999) {
            val deniedList = mutableListOf<String>()
            for (perm in permissions) {
                if (!EasyPermissions.hasPermissions(this, perm)) {
                    deniedList.add(perm)
                }
            }
            if (deniedList.isNotEmpty()) {
                Log.e(TAG, "以下权限未获取 (${deniedList.size} 个):")
                deniedList.forEach { Log.e(TAG, "❌ 未授权权限: $it") }
                checkPermissionList()
            } else {
                getPermission()
            }
        }
    }

    override fun onPermissionsDenied(requestCode: Int, perms: MutableList<String>) {
        // 权限拒绝（perms 是被拒绝的权限列表）
        Toast.makeText(this, "权限被拒绝：" + perms.toString(), Toast.LENGTH_SHORT).show()
        checkPermissionList()
    }

    @SuppressLint("SetTextI18n")
    private fun powerShow() {
        runOnUiThread {
            if (SystemGlobalConstant.powerValue < 20) {
                binding.ivDl.setImageResource(R.mipmap.icon_dianchi_15)
            } else if (SystemGlobalConstant.powerValue < 50) {
                binding.ivDl.setImageResource(R.mipmap.icon_dianchi_30)
            } else if (SystemGlobalConstant.powerValue < 100) {
                binding.ivDl.setImageResource(R.mipmap.icon_dianchi)
            } else {
                binding.ivDl.setImageResource(R.mipmap.icon_dianchi_100)
            }
            binding.tvDl.text = "眼镜：" + SystemGlobalConstant.powerValue.toString() + "%"
        }
    }

    private fun isVolumeInterrupt(): Boolean {
        val overTime = System.currentTimeMillis() - volumeTime
        return overTime <= 5000
    }

    @SuppressLint("SetTextI18n")
    private fun connectStatus(bt_success: Boolean, p2_success: Boolean) {
        lifecycleScope.launch {
            if (bt_success && !isDeviceDiscovered) {
                mBluetoothDevice?.address?.let {
                    isDeviceDiscovered = true
                    startDiscover(it)
                }
            }
            if (!bt_success) {
                isDeviceDiscovered = false
                binding.ivConnect.setImageResource(R.mipmap.icon_to_connect2)
                binding.ivConnect.visibility = View.VISIBLE
                binding.llConnectInfo.visibility = View.GONE
            } else if (p2_success) {
                binding.btConnect.text = "蓝牙已连接"
                binding.btP2PConnect.text = "WIFI已连接"
                binding.ivConnect.visibility = View.GONE
                binding.llConnectInfo.visibility = View.VISIBLE
            } else {
                binding.btConnect.text = "蓝牙已连接"
                binding.btP2PConnect.text = "WIFI未连接"
                binding.ivConnect.visibility = View.GONE
                binding.llConnectInfo.visibility = View.VISIBLE
            }
            binding.brightnessSeekbar.max = 15
        }
    }

    var uuidDeviceDiscoveryManager: UuidDeviceDiscoveryManager? = null
    private var mDeviceDiscoveryCallback =
        object : UuidDeviceDiscoveryManager.DeviceDiscoveryCallback {
            override fun onDeviceDiscovered(device: AssociatedDeviceCompat) {
                Log.d(TAG, "startDiscover onDeviceDiscovered")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    deviceManager.startObservingDevicePresence(device.address)
                } else {
                    // 低版本（API < 31）使用替代方案（如蓝牙广播监听）
                    Log.d(TAG, "设备版本低于 Android 12，使用低版本监听方案")
                }
            }

            override fun onCancelled() {
                Log.d(TAG, "startDiscover onCancelled")
                isDeviceDiscovered = false
                unregisterDiscoveryLauncher()
            }

            override fun onError(message: String) {
                isDeviceDiscovered = false
                Log.d(TAG, "startDiscover onError " + message)
                unregisterDiscoveryLauncher()
            }

        }

    private fun unregisterDiscoveryLauncher() {
        mBluetoothDevice?.address?.let {
            deviceManager.disassociate(it)
        }
        uuidDeviceDiscoveryManager?.unregisterDiscoveryLauncher()
    }

    private fun startDiscover(address: String) {
        // 使用主线程启动设备发现，避免线程问题
        lifecycleScope.launch {
            try {
                // 使用全局launcher启动设备发现
                uuidDeviceDiscoveryManager?.startDeviceDiscoveryWithGlobalLauncher(address, true, mDeviceDiscoveryCallback)
            } catch (e: Exception) {
                isDeviceDiscovered = false
                Log.d(TAG, "startDiscover Exception:" + e.message)
            }
        }
    }

}