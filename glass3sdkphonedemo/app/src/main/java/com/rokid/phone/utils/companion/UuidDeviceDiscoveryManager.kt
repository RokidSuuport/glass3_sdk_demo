package com.rokid.phone.utils.companion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor

/**
 * A manager class for discovering and handling Bluetooth devices with specific UUIDs.
 * This class encapsulates the functionality of CompanionDeviceManager to provide a more
 * focused API for UUID-based device discovery.
 */
@RequiresApi(Build.VERSION_CODES.O)
class UuidDeviceDiscoveryManager(private val context: Context) {

    companion object {
        private const val TAG = "UuidDeviceDiscoveryManager"
        
        // 静态存储全局launcher
        private var globalDeviceDiscoveryLauncher: ActivityResultLauncher<IntentSenderRequest>? = null
        private var currentCallback: DeviceDiscoveryCallback? = null
        
        /**
         * 在Activity的onCreate中初始化全局设备发现launcher
         * 这个方法必须在Activity的onCreate方法中调用
         * 
         * @param activity 注册launcher的Activity
         */
        fun initGlobalDeviceDiscoveryLauncher(activity: FragmentActivity) {
            Log.d(TAG, "Initializing global device discovery launcher")
                globalDeviceDiscoveryLauncher = activity.registerForActivityResult(
                    ActivityResultContracts.StartIntentSenderForResult()
                ) { result ->
                    Log.d(TAG, "Global device discovery result received")
                    currentCallback?.let { callback ->
                        processDeviceDiscoveryResult(result, callback)
                    } ?: run {
                        Log.d(TAG, "No callback registered to handle activity result")
                    }
                }
        }
        
        /**
         * 获取全局设备发现launcher
         */
        fun getGlobalDeviceDiscoveryLauncher(): ActivityResultLauncher<IntentSenderRequest>? {
            return globalDeviceDiscoveryLauncher
        }
        
        /**
         * 处理设备发现结果
         */
        @SuppressLint("MissingPermission")
        private fun processDeviceDiscoveryResult(result: ActivityResult, callback: DeviceDiscoveryCallback) {
            Log.d(TAG, "Processing device discovery result with code: ${result.resultCode}")
            
            when (result.resultCode) {
                CompanionDeviceManager.RESULT_OK -> {
                    val data = result.data
                    Log.d(TAG, "RESULT_OK received, intent data: $data")

                    // 直接从数据中获取关联设备信息
                    var deviceFound = false
                    
                    if (data != null) {
                        // 首先尝试Tiramisu+的API
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val associationInfo = data.getParcelableExtra(
                                CompanionDeviceManager.EXTRA_ASSOCIATION,
                                AssociationInfo::class.java
                            )
                            Log.d(TAG, "Tiramisu+ association info: $associationInfo")
                            
                            if (associationInfo != null) {
                                // 获取MAC地址
                                val macAddress = (associationInfo.deviceMacAddress ?: "N/A").toString()
                                Log.d(TAG, "Device MAC address: $macAddress")
                                
                                // 尝试根据MAC地址获取BluetoothDevice
                                val bluetoothDevice = try {
                                    if (macAddress != "N/A") {
                                        // 格式化MAC地址 - 转换为大写并确保正确的格式
                                        val formattedMac = macAddress
                                            .replace(":", "")  // 移除所有冒号
                                            .replace(".", "")  // 移除所有点
                                            .replace("-", "")  // 移除所有短线
                                            .uppercase()  // 转为大写
                                        
                                        // 重新格式化为 XX:XX:XX:XX:XX:XX 格式
                                        val standardMac = if (formattedMac.length == 12) {
                                            formattedMac.chunked(2).joinToString(":")
                                        } else null

                                        Log.d(TAG, "Original MAC: $macAddress, Formatted: $standardMac")
                                        
                                        if (standardMac != null) {
                                            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
                                            bluetoothAdapter?.getRemoteDevice(standardMac)
                                        } else null
                                    } else null
                                } catch (e: Exception) {
                                    Log.d(TAG, "Error getting BluetoothDevice from MAC: ${e.message}")
                                    null
                                }
                                
                                val device = AssociatedDeviceCompat(
                                    id = associationInfo.id,
                                    address = macAddress,
                                    name = (associationInfo.displayName ?: "N/A").toString(),
                                    device = bluetoothDevice // 从 MAC 地址创建的 BluetoothDevice
                                )
                                
                                if (bluetoothDevice != null) {
                                    Log.d(TAG, "Successfully created BluetoothDevice for: ${bluetoothDevice.name} (${bluetoothDevice.address})")
                                } else {
                                    Log.d(TAG, "Could not create BluetoothDevice from MAC: $macAddress")
                                }

                                Log.d(TAG, "Device found: ${device.name}, ${device.address}")
                                callback.onDeviceDiscovered(device)
                                deviceFound = true
                            }
                        } else {
                            // 旧版API处理
                            @Suppress("DEPRECATION")
                            val scanResult = data.getParcelableExtra<BluetoothDevice>(CompanionDeviceManager.EXTRA_DEVICE)
                            Log.d(TAG, "Pre-Tiramisu scan result: $scanResult")
                            
                            if (scanResult != null) {
                                val device = AssociatedDeviceCompat(
                                    id =0,
                                    address = scanResult.address ?: "N/A",
                                    name = scanResult.name ?: "N/A",
                                    device = scanResult
                                )
                                Log.d(TAG, "Device found: ${device.name}, ${device.address}")
                                callback.onDeviceDiscovered(device)
                                deviceFound = true
                            }
                        }
                    }
                    
                    if (!deviceFound) {
                        Log.d(TAG, "No device found in result")
                        callback.onError("No device found in result")
                    }
                }
                CompanionDeviceManager.RESULT_CANCELED -> {
                    Log.d(TAG, "Device discovery canceled by user")
                    callback.onCancelled()
                }
                else -> {
                    Log.d(TAG, "Device discovery failed with code: ${result.resultCode}")
                    callback.onError("Device discovery failed with code: ${result.resultCode}")
                }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private val deviceManager = context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as? CompanionDeviceManager
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val _associatedDevices = MutableStateFlow<List<AssociatedDeviceCompat>>(emptyList())
    val associatedDevices: StateFlow<List<AssociatedDeviceCompat>> = _associatedDevices.asStateFlow()

    private val _discoveryState = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    val discoveryState: StateFlow<DiscoveryState> = _discoveryState.asStateFlow()

    init {
        refreshAssociatedDevices()
    }

    /**
     * Refreshes the list of currently associated devices.
     */
    fun refreshAssociatedDevices() {
        deviceManager?.let {
            _associatedDevices.value = it.getAssociatedDevices()
        }
    }

    /**
     * 获取设备发现所需的IntentSender
     * 这个方法只负责准备设备发现流程，不执行任何生命周期相关操作
     *
     * @param serviceUuid 要发现的服务UUID
     * @param singleDevice 是否只查找单个设备
     * @return IntentSender 用于启动设备发现UI
     */
    @SuppressLint("MissingPermission")
    suspend fun prepareDeviceDiscovery(
        address: String,
        singleDevice: Boolean = true
    ): IntentSender {
        if (deviceManager == null || bluetoothManager == null) {
            throw IllegalStateException("Device does not support CompanionDeviceManager or Bluetooth")
        }
        
        _discoveryState.value = DiscoveryState.Discovering
        Log.d(TAG, "Preparing device discovery with UUID: ")
        
        return requestDeviceAssociation(address,singleDevice)
    }
    
    /**
     * 创建用于设备发现的ActivityResultLauncher
     * 该方法必须在Activity/Fragment的onCreate中调用，以符合Android生命周期限制
     *
     * @param activity FragmentActivity实例
     * @param callback 用于接收发现结果的回调
     * @return ActivityResultLauncher 用于启动设备发现
     */
    fun createDeviceDiscoveryLauncher(
        activity: FragmentActivity,
        callback: DeviceDiscoveryCallback
    ): ActivityResultLauncher<IntentSenderRequest> {
        Log.d(TAG, "Creating device discovery launcher for ${activity.javaClass.name}")
        return activity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            handleActivityResult(result, callback)
        }
    }
    
    /**
     * 使用预先创建的launcher启动设备发现
     * 
     * @param launcher 预先创建的ActivityResultLauncher
     * @param serviceUuid 要发现的服务UUID
     * @param singleDevice 是否只查找单个设备
     */
    suspend fun startDeviceDiscovery(
        launcher: ActivityResultLauncher<IntentSenderRequest>,
        address: String,
        singleDevice: Boolean = true
    ) {
        try {
            val intentSender = prepareDeviceDiscovery(address, singleDevice)
            launcher.launch(IntentSenderRequest.Builder(intentSender).build())
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Unknown error occurred"
            Log.d(TAG, "Exception during device discovery: ${e.javaClass.name}")
            Log.d(TAG, "Error message: $errorMsg")
            Log.d(TAG, "Stack trace: ${e.stackTraceToString()}")
            _discoveryState.value = DiscoveryState.Error(errorMsg)
            throw e
        }
    }
    
    /**
     * 使用全局launcher启动设备发现
     * 这个方法会使用静态全局launcher，适用于生命周期的任何阶段
     * 
     * @param serviceUuid 要发现的服务UUID
     * @param singleDevice 是否只查找单个设备
     * @param callback 用于接收发现结果的回调
     */
    suspend fun startDeviceDiscoveryWithGlobalLauncher(
        address: String,
        singleDevice: Boolean = false,
        callback: DeviceDiscoveryCallback
    ) {
        _discoveryState.value = DiscoveryState.Discovering
        
        try {
            val globalLauncher = getGlobalDeviceDiscoveryLauncher()
            if (globalLauncher == null) {
                val errorMsg = "Global device discovery launcher not initialized. Call initGlobalDeviceDiscoveryLauncher in your Activity's onCreate."
                Log.d(TAG, errorMsg)
                _discoveryState.value = DiscoveryState.Error(errorMsg)
                callback.onError(errorMsg)
                return
            }
            
            // 设置当前回调
            currentCallback = callback
            
            // 准备和启动设备发现
            val intentSender = prepareDeviceDiscovery(address, singleDevice)
            Log.d(TAG, "Launching device discovery with global launcher")
            globalLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Unknown error occurred"
            Log.d(TAG, "Exception during global device discovery: ${e.javaClass.name}")
            Log.d(TAG, "Error message: $errorMsg")
            Log.d(TAG, "Stack trace: ${e.stackTraceToString()}")
            _discoveryState.value = DiscoveryState.Error(errorMsg)
            callback.onError(errorMsg)
        }
    }



    fun unregisterDiscoveryLauncher(){
        globalDeviceDiscoveryLauncher?.unregister()
    }


    /**
     * 兼容旧版API - 不推荐使用
     * 这个方法可能在Activity的RESUMED状态下失败
     */
    @Deprecated("Use createDeviceDiscoveryLauncher and startDeviceDiscovery instead", ReplaceWith("createDeviceDiscoveryLauncher(activity, callback) and startDeviceDiscovery(launcher, serviceUuid, singleDevice)"))
    @SuppressLint("MissingPermission")
    suspend fun discoverDeviceWithUuid(
        activity: FragmentActivity,
        address: String,
        singleDevice: Boolean = true,
        callback: DeviceDiscoveryCallback
    ) {
        if (deviceManager == null || bluetoothManager == null) {
            val errorMsg = "Device does not support CompanionDeviceManager or Bluetooth"
            Log.d(TAG, "Error: $errorMsg")
            callback.onError(errorMsg)
            return
        }

        _discoveryState.value = DiscoveryState.Discovering

        try {
            Log.d(TAG, "Starting device discovery with UUID: $address")
            Log.d(TAG, "Activity instance: ${activity.javaClass.name}")
            Log.d(TAG, "WARNING: Using deprecated method. This will likely fail in RESUMED state!")
            
            val intentSender = requestDeviceAssociation(address, singleDevice)

            // Create ActivityResultLauncher - 这里可能会失败，因为Activity可能已经处于RESUMED状态
            val launcher = activity.registerForActivityResult(
                ActivityResultContracts.StartIntentSenderForResult()
            ) { result ->
                handleActivityResult(result, callback)
            }

            // Launch device association UI
            launcher.launch(IntentSenderRequest.Builder(intentSender).build())
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Unknown error occurred"
            Log.d(TAG, "Exception during device discovery: ${e.javaClass.name}")
            Log.d(TAG, "Error message: $errorMsg")
            Log.d(TAG, "Stack trace: ${e.stackTraceToString()}")
            
            _discoveryState.value = DiscoveryState.Error(errorMsg)
            callback.onError(errorMsg)
        }
    }

    /**
     * Handle the result from the CompanionDeviceManager UI.
     */
    private fun handleActivityResult(result: ActivityResult, callback: DeviceDiscoveryCallback) {
        Log.d(TAG, "Activity result received with code: ${result.resultCode}")

        when (result.resultCode) {
            CompanionDeviceManager.RESULT_OK -> {
                val data = result.data
                Log.d(TAG, "RESULT_OK received, intent data: $data")

                data?.getAssociationResult()?.let { device ->
                    Log.d(TAG, "Device found: ${device.name}, ${device.address}")
                    _discoveryState.value = DiscoveryState.Success(device)
                    callback.onDeviceDiscovered(device)
                    refreshAssociatedDevices()
                } ?: run {
                    val error = "No device data received"
                    Log.d(TAG, "Error: $error")
                    _discoveryState.value = DiscoveryState.Error(error)
                    callback.onError(error)
                }
            }
            CompanionDeviceManager.RESULT_CANCELED -> {
                Log.d(TAG, "RESULT_CANCELED: User canceled the operation")
                _discoveryState.value = DiscoveryState.Cancelled
                callback.onCancelled()
            }
            CompanionDeviceManager.RESULT_INTERNAL_ERROR -> {
                val error = "Internal error occurred"
                Log.d(TAG, "RESULT_INTERNAL_ERROR: $error")
                _discoveryState.value = DiscoveryState.Error(error)
                callback.onError(error)
            }
            CompanionDeviceManager.RESULT_DISCOVERY_TIMEOUT -> {
                val error = "No matching devices found"
                Log.d(TAG, "RESULT_DISCOVERY_TIMEOUT: $error")
                _discoveryState.value = DiscoveryState.Error(error)
                callback.onError(error)
            }
            CompanionDeviceManager.RESULT_USER_REJECTED -> {
                val error = "User rejected the request"
                Log.d(TAG, "RESULT_USER_REJECTED: $error")
                _discoveryState.value = DiscoveryState.Error(error)
                callback.onError(error)
            }
            else -> {
                val error = "Unknown error with result code: ${result.resultCode}"
                Log.d(TAG, error)
                _discoveryState.value = DiscoveryState.Error(error)
                callback.onError(error)
            }
        }
    }

    /**
     * Connect to a previously associated device.
     * @param device The device to connect to
     * @return The BluetoothDevice object, or null if the connection failed
     */
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: AssociatedDeviceCompat): BluetoothDevice? {
        if (bluetoothManager?.adapter == null) return null

        return device.device ?: bluetoothManager.adapter.getRemoteDevice(device.address)
    }

    /**
     * Disassociate a previously associated device.
     * @param device The device to disassociate
     */
    suspend fun disassociateDevice(device: AssociatedDeviceCompat): Boolean {
        return try {
            if (deviceManager == null) return false

            withContext(Dispatchers.IO) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    deviceManager.disassociate(device.id)
                } else {
                    @Suppress("DEPRECATION")
                    deviceManager.disassociate(device.address)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    deviceManager.stopObservingDevicePresence(device.address)
                }

                refreshAssociatedDevices()
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if a device with the given address is currently associated.
     * @param address The MAC address of the device
     * @return true if the device is associated, false otherwise
     */
    fun isDeviceAssociated(address: String): Boolean {
        return _associatedDevices.value.any { it.address == address }
    }

    /**
     * Requests device association using the CompanionDeviceManager.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun requestDeviceAssociation(address: String,singleDevice: Boolean): IntentSender {


        val deviceFilter = BluetoothDeviceFilter.Builder()
            .setAddress(address)
            .build()

        val pairingRequest = AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(singleDevice)
            .build()

        val result = CompletableDeferred<IntentSender>()

        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                result.complete(intentSender)
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onDeviceFound(intentSender: IntentSender) {
                result.complete(intentSender)
            }

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                // This callback was added in API 33 but the result is also sent in the activity result
            }

            override fun onFailure(errorMessage: CharSequence?) {
                val error = errorMessage?.toString() ?: "Unknown error"
                Log.d(TAG, "CompanionDeviceManager callback onFailure: $error")
                Log.d(TAG, "Error message type: ${errorMessage?.javaClass?.name ?: "null"}")
                if (errorMessage != null) {
                    Log.d(TAG, "Error message content: $errorMessage")
                }
                result.completeExceptionally(IllegalStateException(error))
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val executor = Executor { it.run() }
            deviceManager?.associate(pairingRequest, executor, callback)
        } else {
            deviceManager?.associate(pairingRequest, callback, null)
        }

        return result.await()
    }

    /**
     * Release resources when the manager is no longer needed.
     */
    fun release() {
        globalDeviceDiscoveryLauncher?.unregister()
        currentCallback =null
        scope.cancel()
    }

    /**
     * Get the extension to handle the association result from the Intent.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun Intent?.getAssociationResult(): AssociatedDeviceCompat? {
        if (this == null) {
            Log.d(TAG, "getAssociationResult: Intent is null")
            return null
        }

        Log.d(TAG, "Getting association result from intent: $this")
        Log.d(TAG, "Intent extras: ${extras?.keySet()?.joinToString() ?: "null"}")

        var result: AssociatedDeviceCompat? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val associationInfo = getParcelableExtra(
                CompanionDeviceManager.EXTRA_ASSOCIATION,
                AssociationInfo::class.java
            )
            Log.d(TAG, "Tiramisu+ association info: $associationInfo")
            result = associationInfo?.toAssociatedDevice()
        } else {
            @Suppress("DEPRECATION")
            val scanResult = getParcelableExtra<ScanResult>(CompanionDeviceManager.EXTRA_DEVICE)
            Log.d(TAG, "Pre-Tiramisu scan result: $scanResult")
            if (scanResult != null) {
                result = AssociatedDeviceCompat(
                    id = scanResult.advertisingSid,
                    address = scanResult.device.address ?: "N/A",
                    name = scanResult.scanRecord?.deviceName ?: "N/A",
                    device = scanResult.device,
                )
            }
        }
        Log.d(TAG, "Final associated device result: $result")
        return result
    }

    /**
     * Sealed class representing the state of device discovery.
     */
    sealed class DiscoveryState {
        object Idle : DiscoveryState()
        object Discovering : DiscoveryState()
        object Cancelled : DiscoveryState()
        data class Success(val device: AssociatedDeviceCompat) : DiscoveryState()
        data class Error(val message: String) : DiscoveryState()
    }

    /**
     * Callback interface for device discovery.
     */
    interface DeviceDiscoveryCallback {
        fun onDeviceDiscovered(device: AssociatedDeviceCompat)
        fun onCancelled()
        fun onError(message: String)
    }
}