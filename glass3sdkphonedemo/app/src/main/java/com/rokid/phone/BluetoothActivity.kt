package com.rokid.phone

import android.bluetooth.BluetoothDevice
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rokid.phone.adapter.BleDeviceAdapter
import com.rokid.phone.adapter.WifiDeviceAdapter
import com.rokid.phone.data.BluetoothDeviceInfo
import com.rokid.phone.databinding.ActivityBluetoothBinding
import com.rokid.phone.utils.AppDataManager
import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.security.phone.sdk.api.bluetooth.classic.listener.IClassicBTClientListener
import com.rokid.security.phone.sdk.api.wifip2p.listener.IWifiP2PClientListener
import kotlinx.coroutines.launch


class BluetoothActivity : ComponentActivity() {
    private val TAG = "BluetoothActivity::"
    private lateinit var btBinding: ActivityBluetoothBinding
    private var deviceList = mutableListOf<BluetoothDevice>()
    private var deviceWifiList = mutableListOf<WifiP2pDevice>()
    private var isWifiConnected = MyApplication.instance.isBtConnect
    private lateinit var adapter: BleDeviceAdapter
    private lateinit var adapterP2p: WifiDeviceAdapter
    private val mWifiP2PClientService by lazy {
        PSecuritySDK.getWifiP2PClientService()

    }
    private lateinit var device01: BluetoothDevice

    private var mDevice: WifiP2pDevice? = null
    private var mBlDevice: BluetoothDeviceInfo? = null
    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        btBinding = ActivityBluetoothBinding.inflate(layoutInflater)

        setContentView(btBinding.root)

        //开启蓝牙扫描，在onDeviceFound获取到在线蓝牙
        btBinding.btStartScanBtn.setOnClickListener {
            if (isWifiConnected) {
                mWifiP2PClientService
            }
            PSecuritySDK.getClassicBlueToothClientService()?.startScan(2000)

        }
        //连接蓝牙
//        btBinding.btStartScanWifi.setOnClickListener {
//
//
//            mWifiP2PClientService?.startDiscoverPeers()
//
//        }
        //断开蓝牙
//        btBinding.btDisconnectBtn.setOnClickListener {
//            PSecuritySDK.getClassicBlueToothClientService()?.disconnect()
////            setupBleDeviceList()
//        }
//        //停止扫描
//        btBinding.btStopScanBtn.setOnClickListener {
//            PSecuritySDK.getClassicBlueToothClientService()?.stopScan();
//
//        }
//        //查询当前连接状态
//        btBinding.btIsConnectedBtn.setOnClickListener {
//            isConnected = PSecuritySDK.getClassicBlueToothClientService()!!.isConnected();
//            btBinding.btIsConnectedBtn.text = isConnected.toString();
//        }
        //发送文本消息
//        btBinding.btSendTextBtn.setOnClickListener {
//
//                val messageBean = NotificationMessage(packageName, "测试", "测试111", "测试消息222", 2000)
//                PSecuritySDK.getAbsNotificationService()?.sendNotification(messageBean)
//
//            PSecuritySDK.getMessageService()?.sendTextMessageByClassicBT("测试的消息")
//        }
//        //发送音视频流
//        btBinding.btSendAudioBtn.setOnClickListener {
////            PSecuritySDK.getMessageService()?.sendAudioStreamDataByClassicBT()
//
//        }
        //蓝牙连接回调
        PSecuritySDK.getClassicBlueToothClientService()?.addClientListener(object :
            IClassicBTClientListener {
            override fun onDeviceFound(device: BluetoothDevice) {
                Log.e(TAG, "onDeviceFound::" + device.name + " " + device.address)
                if(btDeviceFilter(device.name) &&  device.type != BluetoothDevice.DEVICE_TYPE_LE){
//                    val bluetoothDeviceInfo = BluetoothDeviceInfo(name,address,type)
                    deviceList.add(device)
                    adapter.submitList(deviceList.toList())

                }


            }

            override fun onScanFinished() {
                Log.e(TAG, "onScanFinished")
            }

            override fun onConnect(success: Boolean) {
                if (success) {
//                    Toast.makeText(BluetoothActivity,"连接成功",Toast.LENGTH_LONG).show()
//                    Toast.makeText(this@BluetoothActivity, "蓝牙连接成功", Toast.LENGTH_LONG).show()

                }

                Log.e(TAG, "onConnect::$success")
            }

            override fun onConnectionRejected(reason: String, code: Int) {
            }
        })
//        mWifiP2PClientService?.addWifiP2PClientListener(mIWifiP2PClientListener)

        PSecuritySDK.getClassicBlueToothClientService()?.startScan(2000)
//        mWifiP2PClientService?.disconnect()
//        mWifiP2PClientService?.initialize()
//        mWifiP2PClientService?.startDiscoverPeers()
        setupBleDeviceList()
//        setupWifiDeviceList()
    }

    override fun onResume() {
        super.onResume()
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )

    }


    private val mIWifiP2PClientListener = object : IWifiP2PClientListener {
        override fun onWifiP2pEnabled(enabled: Boolean) {
            if (enabled){

//                Toast.makeText(this@BluetoothActivity, "WiFi连接成功", Toast.LENGTH_LONG).show()
            }
            Log.e(TAG, "onWifiP2pConnect ${enabled}")
        }

        override fun onSelfDeviceAvailable(device: WifiP2pDevice) {
        }

        override fun onPeersAvailable(devices: List<WifiP2pDevice>) {
            deviceWifiList = devices.toMutableList()
            adapterP2p.submitList(deviceWifiList)
            devices.forEach {
                Log.e(TAG, it.deviceName + it.deviceAddress + it.status)
            }
        }
    }
    private fun setupBleDeviceList() {
        Log.e(TAG, "sssss");
        lifecycleScope.launch {
            val recycler = findViewById<RecyclerView>(R.id.rv_bluetooths11)
            recycler.layoutManager = LinearLayoutManager(this@BluetoothActivity)
             adapter = BleDeviceAdapter {
                handleAnimation(it)

            }
            recycler.adapter = adapter
            adapter.submitList(deviceList)
        }
    }


//    private fun setupWifiDeviceList() {
//        Log.e(TAG, "sssss");
//        lifecycleScope.launch {
//            val recycler = findViewById<RecyclerView>(R.id.rv_wifi)
//            recycler.layoutManager = LinearLayoutManager(this@BluetoothActivity)
//           adapterP2p = WifiDeviceAdapter {
//                handleWifiAnimation(it)
//
//            }
//            recycler.adapter = adapterP2p
//            adapterP2p.submitList(deviceWifiList)
//        }
//    }

//    private fun handleWifiAnimation(model: WifiP2pDevice) {
//        mDevice=model
//        mDevice?.let { DeviceLinkerManager.saveP2pDevice(it) }
//        mWifiP2PClientService?.connectDevice(model) {
//
//        }
//    }
   private  fun btDeviceFilter(deviceName:String?):Boolean{
        if(deviceName.isNullOrEmpty() || "null".equals(deviceName.lowercase())){
            return false;
        }


        //正式版本只展示Glass3 开头的蓝牙设备
        if(deviceName.contains("Glass3")){
            return true
        }
        return false
    }
    private fun handleAnimation(model: BluetoothDevice) {
        val deviceneme = model.name
       AppDataManager.mBluetoothDeviceName = deviceneme
        Log.e(TAG, deviceneme)
        PSecuritySDK.getClassicBlueToothClientService()?.connectToServer(model) {
            if (it) {
                Log.e(TAG, "连接成功")

            } else {
                Log.e(TAG, "连接失败")
            }
        }


    }
}
