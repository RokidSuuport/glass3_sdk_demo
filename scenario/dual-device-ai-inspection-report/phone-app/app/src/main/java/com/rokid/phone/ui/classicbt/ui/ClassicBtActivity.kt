package com.rokid.phone.ui.classicbt.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.rokid.phone.PairingActivity
import com.rokid.phone.base.BaseActivity
import com.rokid.phone.databinding.ActivityDiscoverDeviceBinding
import com.rokid.phone.ui.classicbt.adapter.BlueDeviceAdapter
import com.rokid.phone.ui.classicbt.repository.ClassicBtRepository
import kotlinx.coroutines.launch

class ClassicBtActivity : BaseActivity<ActivityDiscoverDeviceBinding>() {
    private val repository = ClassicBtRepository()
    private val adapter = BlueDeviceAdapter(::connect)
    private var rotation: RotateAnimation? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) ensureBluetoothEnabled() else finish()
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) startScan()
    }

    override fun initViewBinding(): ActivityDiscoverDeviceBinding =
        ActivityDiscoverDeviceBinding.inflate(layoutInflater)

    override fun onInit(savedInstanceState: Bundle?) {
        binding.ivBack.setOnClickListener { finish() }
        binding.tvSkip.setOnClickListener { finish() }
        binding.ivLoadReflesh.setOnClickListener { startScan() }
        binding.rvDiscoveredDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDiscoveredDevices.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL),
        )
        binding.rvDiscoveredDevices.adapter = adapter
        repository.addListener()

        lifecycleScope.launch {
            repository.events.collect {
                adapter.submit(it)
                binding.tvListTip2.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        lifecycleScope.launch {
            repository.scanFinished.collect {
                stopLoading()
                if (binding.tvListTip2.visibility == View.GONE) {
                    binding.tvListTip2.visibility = View.VISIBLE
                }
            }
        }
        ensureBluetoothEnabled()
    }

    private fun ensureBluetoothEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_SCAN)
            return
        }
        if (BluetoothAdapter.getDefaultAdapter()?.isEnabled != true) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        startScan()
    }

    private fun startScan() {
        startLoading()
        binding.tvListTip2.visibility = View.GONE
        repository.startScan()
    }

    private fun connect(device: com.rokid.phone.ui.classicbt.model.BluetoothDeviceInfo) {
        PairingActivity.start(this, device)
        finish()
    }

    private fun startLoading() {
        binding.ivLoadSmall.visibility = View.VISIBLE
        rotation = RotateAnimation(
            0f,
            360f,
            Animation.RELATIVE_TO_SELF,
            0.5f,
            Animation.RELATIVE_TO_SELF,
            0.5f,
        ).apply {
            duration = 900L
            repeatCount = Animation.INFINITE
            interpolator = LinearInterpolator()
        }
        binding.ivLoadSmall.startAnimation(rotation)
    }

    private fun stopLoading() {
        binding.ivLoadSmall.clearAnimation()
        binding.ivLoadSmall.visibility = View.INVISIBLE
        rotation = null
    }

    override fun onDestroy() {
        repository.stopScan()
        repository.removeListener()
        super.onDestroy()
    }
}
