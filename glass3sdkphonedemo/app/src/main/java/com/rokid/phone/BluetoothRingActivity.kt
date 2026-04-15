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
import com.rokid.phone.databinding.ActivityBluetoothRingActivityBinding
import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.security.phone.sdk.api.bluetooth.ring.api.IBTRingClientListener
import com.rokid.security.phone.sdk.base.utils.log.L
import com.rokid.security.sdk.base.common.RingExtra
import kotlinx.coroutines.launch


class BluetoothRingActivity : ComponentActivity() {
    private val TAG = "BluetoothRingActivity"
    private val mBluetoothRingService by lazy {
        PSecuritySDK.getBluetoothRingService()
    }
    private lateinit var adapter: BleDeviceAdapter
    private var deviceList = mutableListOf<BluetoothDevice>()
    private lateinit var wifiBinding: ActivityBluetoothRingActivityBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        wifiBinding = ActivityBluetoothRingActivityBinding.inflate(layoutInflater)
//        PSecuritySDK.getTrackService().addTrackListener()
        setContentView(wifiBinding.root)


        //扫描设备
        wifiBinding.wfDiscoverPeersBtn.setOnClickListener {
            mBluetoothRingService?.startScan()
        }
        wifiBinding.wfConnectBtn.setOnClickListener {
            setupBleDeviceList()
        }

        PSecuritySDK.getBluetoothRingService()?.addClientListener(object : IBTRingClientListener {
            override fun onDeviceFound(device: BluetoothDevice) {
                Log.e(TAG, device.name)
                deviceList.add(device)

            }

            override fun onScanFinished() {
                Log.e(TAG, "扫描结束")
            }

            override fun onConnect(extra: RingExtra) {
                Log.e(TAG, "----->指环连接结果,${extra.name + " " + extra.address + ",连接状态：" + extra.connectSuccess + " " + extra.methodName}")
            }

            override fun onConnect(success: Boolean) {
                L.d(TAG, "----->指环连接结果: $success")
            }
        })

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



    private fun setupBleDeviceList() {
        Log.e(TAG, "sssss");
        lifecycleScope.launch {
            val recycler = findViewById<RecyclerView>(R.id.rv_wifi)
            recycler.layoutManager = LinearLayoutManager(this@BluetoothRingActivity)
            adapter = BleDeviceAdapter {
                handleAnimation(it)

            }
            recycler.adapter = adapter
            adapter.submitList(deviceList)
        }
    }

    private fun handleAnimation(model: BluetoothDevice) {

        PSecuritySDK.getBluetoothRingService()?.connectToServer(model)
//        val requestStr = SenderHandler.mGson.toJson(model)
//
//        PSecuritySDK.getMessageService()?.sendTextMessageByBle(requestStr)


    }
}