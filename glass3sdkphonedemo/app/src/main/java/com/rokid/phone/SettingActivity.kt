package com.rokid.phone

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.rokid.phone.base.BaseActivity
import com.rokid.phone.data.GlobalData
import com.rokid.phone.databinding.ActivitySettingBinding
import com.rokid.phone.system.ui.SystemOtaActivity
import com.rokid.phone.utils.SystemGlobalConstant
import com.rokid.phone.ui.DeviceLinerActivity


/**
 * Author: zhangshengwei
 * Date: 2025/6/23
 */
class SettingActivity : BaseActivity<ActivitySettingBinding>() {

    override fun onInit(savedInstanceState: Bundle?) {
        binding.clOta.setOnClickListener {
            if (!GlobalData.btConnectState.value) {
                Toast.makeText(this, "请先完成设备蓝牙连接", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!GlobalData.p2pConnectState.value) {
                Toast.makeText(this, "请先完成P2P连接", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, SystemOtaActivity::class.java))
        }

        binding.clDeviceLinker.setOnClickListener {
            if (!GlobalData.btConnectState.value) {
                Toast.makeText(this, "请先完成设备蓝牙连接", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            var intent = Intent(this, DeviceLinerActivity::class.java)
            startActivity(intent)
        }
        binding.layoutTitle.ivBack.setOnClickListener {
            finish()
        }
        binding.tvDeviceName.text = DeviceLinkerManager.getDeviceName()
        binding.tvDeviceSn.text = SystemGlobalConstant.deviceId
        binding.tvDeviceSystem.text = SystemGlobalConstant.version
        DeviceLinkerManager.addSystemInfoListener(systemInfoListener)
        DeviceLinkerManager.getGlassSystemInfoMsg()
    }

    private val systemInfoListener = { binding.tvDeviceSystem.text = SystemGlobalConstant.version }

    override fun onDestroy() {
        super.onDestroy()
        DeviceLinkerManager.removeSystemInfoListener(systemInfoListener)
    }

    override fun initViewBinding(): ActivitySettingBinding {
        return ActivitySettingBinding.inflate(layoutInflater)
    }

}