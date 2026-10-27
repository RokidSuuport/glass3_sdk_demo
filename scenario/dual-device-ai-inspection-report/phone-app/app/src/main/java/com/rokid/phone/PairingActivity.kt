package com.rokid.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rokid.phone.base.BaseActivity
import com.rokid.phone.data.GlobalData
import com.rokid.phone.databinding.ActivityPairingBinding
import com.rokid.phone.ui.classicbt.model.BluetoothDeviceInfo
import com.rokid.phone.utils.demoString
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PairingActivity : BaseActivity<ActivityPairingBinding>() {
    private var device = BluetoothDeviceInfo("", "", 0)
    private var pairJob: Job? = null
    private var completed = false

    override fun initViewBinding(): ActivityPairingBinding =
        ActivityPairingBinding.inflate(layoutInflater)

    override fun onInit(savedInstanceState: Bundle?) {
        device = BluetoothDeviceInfo(
            intent.getStringExtra(EXTRA_NAME).orEmpty(),
            intent.getStringExtra(EXTRA_ADDRESS).orEmpty(),
            intent.getIntExtra(EXTRA_TYPE, 0),
        )
        binding.tvDeviceName.text = device.name
        binding.btnRetry.setOnClickListener { startPairing() }
        binding.btnCancel.setOnClickListener { finish() }
        DeviceLinkerManager.disconnectBt()
        observeConnection()
        startPairing()
    }

    private fun observeConnection() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    GlobalData.btConnectState.collect { connected ->
                        binding.tvBluetoothState.text = demoString(
                            if (connected) R.string.demo_pair_bluetooth_done else R.string.demo_pair_bluetooth_waiting,
                        )
                        if (connected) DeviceLinkerManager.connectP2p()
                    }
                }
                launch {
                    GlobalData.p2pConnectState.collect { connected ->
                        binding.tvP2PState.text = demoString(
                            if (connected) R.string.demo_pair_p2p_done else R.string.demo_pair_p2p_waiting,
                        )
                        if (connected) completePairing()
                    }
                }
            }
        }
    }

    private fun startPairing() {
        if (completed || device.address.isBlank()) return
        pairJob?.cancel()
        DeviceLinkerManager.disconnectBt()
        binding.progress.visibility = View.VISIBLE
        binding.btnRetry.visibility = View.GONE
        binding.tvPairHint.text = demoString(R.string.demo_pair_preparing)
        binding.tvBluetoothState.text = demoString(R.string.demo_pair_bluetooth_waiting)
        binding.tvP2PState.text = demoString(R.string.demo_pair_p2p_waiting)
        pairJob = lifecycleScope.launch {
            delay(350L)
            binding.tvPairHint.text = demoString(R.string.demo_pair_bluetooth_connecting)
            DeviceLinkerManager.connectBt(device) { success ->
                runOnUiThread {
                    if (!success) showPairFailure()
                }
            }
            delay(20_000L)
            if (!completed && !GlobalData.p2pConnectState.value) showPairFailure()
        }
    }

    private fun completePairing() {
        if (completed) return
        completed = true
        pairJob?.cancel()
        binding.progress.visibility = View.GONE
        binding.tvPairHint.text = demoString(R.string.demo_pair_complete)
        binding.btnRetry.visibility = View.GONE
        lifecycleScope.launch {
            delay(800L)
            finish()
        }
    }

    private fun showPairFailure() {
        if (completed) return
        binding.progress.visibility = View.GONE
        binding.tvPairHint.text = demoString(R.string.demo_pair_failed)
        binding.btnRetry.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        pairJob?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_NAME = "device_name"
        private const val EXTRA_ADDRESS = "device_address"
        private const val EXTRA_TYPE = "device_type"

        fun start(context: Context, device: BluetoothDeviceInfo) {
            context.startActivity(
                Intent(context, PairingActivity::class.java)
                    .putExtra(EXTRA_NAME, device.name)
                    .putExtra(EXTRA_ADDRESS, device.address)
                    .putExtra(EXTRA_TYPE, device.type),
            )
        }
    }
}
