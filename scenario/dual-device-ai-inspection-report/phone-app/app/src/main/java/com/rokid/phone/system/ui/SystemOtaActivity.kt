package com.rokid.phone.system.ui

import com.rokid.phone.utils.demoString
import com.rokid.phone.R

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.blankj.utilcode.util.ToastUtils
import com.google.gson.Gson
import com.rokid.phone.MyApplication
import com.rokid.phone.base.ui.MviActivity
import com.rokid.phone.data.GlobalData
import com.rokid.phone.data.GlobalEvent
import com.rokid.phone.databinding.ActivitySystemBinding
import com.rokid.phone.system.OtaManager
import com.rokid.phone.system.OtaManager.isInOTAActivity
import com.rokid.phone.system.model.SystemEvent
import com.rokid.phone.system.model.SystemIntent
import com.rokid.phone.system.model.SystemState
import com.rokid.phone.system.model.UpdateStatus
import com.rokid.phone.system.viewmodel.SystemOtaStatus
import com.rokid.phone.system.viewmodel.SystemViewModel
import com.rokid.phone.ui.BtWifiConnectActivity
import com.rokid.phone.ui.MainPhoneActivity
import com.rokid.phone.utils.FolderUtils
import com.rokid.phone.utils.LoadingManager
import com.rokid.phone.utils.NetworkUtil
import com.rokid.phone.utils.OTA_UPDATE_STATUS
import com.rokid.phone.utils.ProtocolVersionUtils
import com.rokid.phone.utils.RKSystemInfo
import com.rokid.phone.utils.SPUtil
import com.rokid.phone.utils.SpKeyConstant
import com.rokid.phone.utils.SystemGlobalConstant
import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.security.phone.sdk.base.utils.log.L
import com.rokid.security.phone.sdk.base.utils.other.ktx.call
import com.rokid.security.phone.sdk.base.utils.other.ktx.collect
import com.rokid.security.phone.sdk.base.utils.other.workScope
import java.io.File
import java.lang.ref.WeakReference

class SystemOtaActivity :
    MviActivity<ActivitySystemBinding, SystemState, SystemIntent, SystemEvent, SystemViewModel>() {

    private var updateStatus = UpdateStatus.UPDATE_UN
    private var isNeedUpdate = false
    private var fileMd5 = ""
    var mGson = Gson()
    private var TAG = "SystemOtaActivityPhone"

    private var filePath = ""
    private var exitTipsMsg = ""

    private var isDownOrSend = false
    @Volatile
    private var isStartDownload = false
    private var clickTime = 0L

    private var isFirst = true
    private var mDialog: AlertDialog? = null

    private val btSystemFirstText = demoString(R.string.demo_check_for_updates)

    // 使用单例 ViewModel，使 OTA 状态不受页面重建影响。
    // Use a singleton ViewModel so OTA state survives screen recreation.
    override val viewModel: SystemViewModel by lazy {
        SystemViewModel.getInstance()
    }

    override fun onInit(savedInstanceState: Bundle?) {
        viewModel.sendIntent(SystemIntent.getSystemInfo())
        filePath = FolderUtils.getSystemDirectory(this).absolutePath + "/update.zip"
        if (!File(filePath).exists()) {
            File(filePath).createNewFile()
        }
        binding.tvVersion.text = SystemGlobalConstant.version
        binding.btSystem.text = btSystemFirstText

        initListener()

        // 多层基类可能重复触发初始化，通过标记确保 OTA 管理器只启动一次。
        // Multiple base classes may trigger initialization repeatedly; use a flag to start the OTA manager only once.
        if (isFirst) {
            isFirst = false
            OtaManager.start()
        }

        L.i(TAG, "SystemOtaActivity onInit p2p: ${GlobalData.p2pConnectState.value}" +
                " 是否是第三阶段: ${OtaManager.isState3}, progress: ${OtaManager.progressState3}")
    }

    private fun initListener() {
        binding.btSystem.setOnClickListener {
            L.i(TAG, "onInit SystemGlobalConstant: " + SystemGlobalConstant.isCharge + " "
                    + SystemGlobalConstant.powerValue + " updateStatus:" + updateStatus)

            if (!(SystemGlobalConstant.isCharge) && SystemGlobalConstant.powerValue != 100) {
                Toast.makeText(this, demoString(R.string.demo_keep_the_glasses_charging), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (updateStatus == UpdateStatus.UPDATE_ING_DOWNLOAD || updateStatus == UpdateStatus.UPDATE_ING_SEND) {
                Toast.makeText(this, demoString(R.string.demo_updating_please_wait), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!NetworkUtil.isNetworkAvailable(this)) {
                ToastUtils.showShort(demoString(R.string.demo_no_network_connection))
                return@setOnClickListener
            }

            if (!GlobalData.btConnectState.value) {
                ToastUtils.showShort(demoString(R.string.demo_bluetooth_disconnected))
                return@setOnClickListener
            }

            if (isOtaInProgress()) {
                L.i(TAG, "initListener 正处于 OTA 过程中，忽略按钮点击。。。")
                return@setOnClickListener
            }

            // 热点状态在不同厂商设备上的检测结果不一致，因此仅通过界面提示用户确认网络状态。
            // Hotspot detection varies across device manufacturers, so only prompt the user in the UI to verify the network state.

            if (System.currentTimeMillis() - clickTime < 500) {
                L.i(TAG, "initListener 忽略快速重复点击。。。")
                return@setOnClickListener
            }

            clickTime = System.currentTimeMillis()

            if (isNeedUpdate) {

                L.i(TAG, "initListener 开始更新准备中...")

                prepareConnectP2P()
            } else {
                L.i(TAG, "initListener 版本检测中...")

                LoadingManager.showLoading(this, demoString(R.string.demo_checking_for_updates))

                val info = RKSystemInfo()
                info.osType = SystemGlobalConstant.osType
                info.version = SystemGlobalConstant.version
                info.deviceId = SystemGlobalConstant.deviceId
                info.deviceTypeId = SystemGlobalConstant.deviceTypeId

                viewModel.sendIntent(SystemIntent.checkUpdate(info))
            }
        }

        binding.layoutTitle.ivBack.setOnClickListener {
            onBackPressed()
        }

        GlobalData.btConnectState.collect(lifecycleScope){
            L.i(TAG, "initListener btConnectState：$it")

            if (!it) {
                showBTConnectTips()
            }
        }

    }

    override fun onBackPressed() {
        L.d(TAG, "onBackPressed updateStatus: $updateStatus")

        if (updateStatus == OTA_UPDATE_STATUS.OTA_CORE_SYSTEM_START || OtaManager.isState3) {
            exitTipsMsg = demoString(R.string.demo_the_glasses_are_updating_you_can_close_this)
            showFinishTips()

            return
        } else if (updateStatus == UpdateStatus.UPDATE_ING_DOWNLOAD || updateStatus == UpdateStatus.UPDATE_ING_SEND) {
            exitTipsMsg = demoString(R.string.demo_the_glasses_are_updating_interrupt_the_update)

            showTipsForExit()

            return
        }

        super.onBackPressed()
    }

    private fun prepareConnectP2P() {
        L.d(TAG, "prepareConnectP2P 开始更新准备中，P2PConnect: ${GlobalData.p2pConnectState.value}, " +
                "filePath: $filePath")

        if (isStartDownload) {
            L.d(TAG, "prepareConnectP2P 已经开始下载。。。")
            return
        }

        PSecuritySDK.getWifiP2PClientService()?.isConnect {
            L.d(TAG, "prepareConnectP2P 是否连接：$it")

            if (isStartDownload) {
                L.d(TAG, "prepareConnectP2P 在isConnect回调期间下载已启动，直接返回。。。")
                return@isConnect
            }

            if (it) {
                L.d(TAG, "prepareConnectP2P 111 准备更新...")
                LoadingManager.showLoading(this, demoString(R.string.demo_preparing_update))

                startDownload()
                getP2PKeepConnect()
            } else {
                L.d(TAG, "prepareConnectP2P 222 通道连接中...")
                LoadingManager.showLoading(this, demoString(R.string.demo_connecting))

                PSecuritySDK.getWifiP2PClientService()?.sendConnectP2pRequest { isConnect ->
                    L.d(TAG, "prepareConnectP2P P2P自动连接结果 isConnect: $isConnect, isStartDownload: $isStartDownload")

                    if (!isStartDownload) {
                        runOnUiThread {
                            if (isConnect) {
                                L.d(TAG, "prepareConnectP2P 222 准备更新...")
                                LoadingManager.showLoading(this, demoString(R.string.demo_preparing_update))

                                startDownload()
                                getP2PKeepConnect()
                            } else {
                                L.d(TAG, "prepareConnectP2P P2P自动连接失败，提示手动尝试...")

                                LoadingManager.hideLoading()
                                p2pConnectFailTips()
                            }
                        }
                    }
                }
            }
        }
    }


    private fun startDownload() {
        L.d(TAG, "startDownload 开始准备开始下载流程 isStartDownload: $isStartDownload")

        if (!isStartDownload) {
            isStartDownload = true

            viewModel.sendIntent(SystemIntent.startUpdate(filePath, fileMd5))
        }
    }

    private fun getP2PKeepConnect() {
        val weakActivity = WeakReference(this@SystemOtaActivity)

        PSecuritySDK.getWifiP2PClientService()?.getIP2PConnectControl()?.getKeepP2PConnectState(
            action = { isKeepConnect ->
                val activity = weakActivity.get()
                if (activity == null || activity.isFinishing || activity.isDestroyed) {
                    L.d(TAG, "Activity已销毁，忽略P2P保持连接回调")
                    return@getKeepP2PConnectState
                }

                L.d(TAG, "getP2PKeepConnect 眼镜端是否开启P2P长连: $isKeepConnect")

                if (!isKeepConnect) {
                    // 使用新的弱引用
                    // Use the new weak reference.
                    val innerWeakActivity = WeakReference(activity)
                    PSecuritySDK.getWifiP2PClientService()?.getIP2PConnectControl()?.setKeepP2PConnect(true) { isSuccess ->
                        val innerActivity = innerWeakActivity.get()
                        if (innerActivity == null || innerActivity.isFinishing || innerActivity.isDestroyed) {
                            L.d(TAG, "Activity已销毁，忽略设置P2P保持连接回调")
                            return@setKeepP2PConnect
                        }

                        L.d(TAG, "setKeepP2PConnect 设置P2P保持连接，是否设置成功: $isSuccess")

                        if (isSuccess) {
                            SPUtil.getInstance(MyApplication.instance).putBoolean(
                                SystemGlobalConstant.deviceId + SpKeyConstant.OTA_IS_CLOSE_P2P_KEEP_CONNECT,
                                true
                            )
                        }
                    }
                }
            },
            onFail = { errMsg, code ->
                val activity = weakActivity.get()
                if (activity == null || activity.isFinishing || activity.isDestroyed) {
                    return@getKeepP2PConnectState
                }

                L.e(TAG, "getP2PKeepConnect: onFail --- errMsg = $errMsg, code = $code")
            }
        )
    }

    private fun p2pConnectFailTips() {
        AlertDialog.Builder(this)
            .setTitle(demoString(R.string.demo_connection_failed))
            .setMessage(demoString(R.string.demo_automatic_p2p_connection_failed_try_manually))
            .setPositiveButton(demoString(R.string.demo_ok)) { dialog, which ->
                if (!GlobalData.p2pConnectState.value) {
                    if (ProtocolVersionUtils.isGlassVersion1()) {
                        startActivity(Intent(this, BtWifiConnectActivity::class.java))
                    } else {
                        BtWifiConnectActivity.start(this, isConnetBt = false, isConnetP2p = true)
                    }
                }

                dialog.dismiss()
            }
            .setNegativeButton(demoString(R.string.prompter_cancel)) { dialog, which ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    override fun initViewBinding(): ActivitySystemBinding {
        return ActivitySystemBinding.inflate(layoutInflater)
    }

    @SuppressLint("SetTextI18n")
    override fun handleEvent(event: SystemEvent) {
        L.d(TAG, "handleEvent " + event + " " +
                "tojsonString: ${mGson.toJson(event)}")

        LoadingManager.hideLoading()

        when (event) {
            is SystemEvent.NeedUpdate -> {

                fileMd5 = event.fileMd5
                isNeedUpdate = event.need
                if (event.need) {
                    binding.clUpdateContent.visibility = View.VISIBLE
                    binding.btSystem.text = demoString(R.string.demo_update_now)
                    binding.tvNewSystemVersion.text = demoString(R.string.demo_version) + event.version
                    binding.clUpdateContent.visibility = View.VISIBLE
                    binding.tvUpdateContent.text = event.content
                } else {
                    binding.clUpdateContent.visibility = View.GONE
                    binding.llEmpty.visibility = View.VISIBLE
                }
            }

            is SystemEvent.SystemInfo -> {
                binding.tvVersion.text = event.info.version
            }

            is SystemEvent.UpdateState -> {
                var mSystemOtaStatus = mGson.fromJson(event.msg, SystemOtaStatus::class.java)
                var status = mSystemOtaStatus.status

                updateStatus = status

                L.d(TAG, "handleEvent updateStatus: $updateStatus")

                if (status == OTA_UPDATE_STATUS.OTA_FILE_MOVE) {
                    binding.progressBar.visibility = View.GONE
                    binding.tvProcessTips.text = demoString(R.string.demo_moving_ota_file_to_the_update_location)
                    isDownOrSend = false
                } else if (status == OTA_UPDATE_STATUS.OTA_FILE_MOVE_SUCCESS) {
                    binding.progressBar.visibility = View.GONE
                    binding.tvProcessTips.text = demoString(R.string.demo_ota_file_moved_to_the_update_location)
                    isDownOrSend = false
                } else if (status == OTA_UPDATE_STATUS.OTA_FILE_MOVE_FAILED) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this, demoString(R.string.demo_failed_to_move_ota_file_to_the_update), Toast.LENGTH_SHORT).show()
                    isDownOrSend = false
                } else if (status == OTA_UPDATE_STATUS.OTA_CORE_SYSTEM_START) {
                    // 恢复页面时隐藏操作按钮，确保升级进度视图可见。
                    // When restoring the screen, hide action buttons and keep the update progress view visible.
                    binding.btSystem.visibility = View.GONE
                    binding.clProcess.visibility = View.VISIBLE
                    binding.progressBar.visibility = View.VISIBLE
                    binding.tvStatus.text = demoString(R.string.demo_stage_3_3)
                    binding.tvProcessTips.text = "${OtaManager.STATE_3} " + mSystemOtaStatus.process + " %"
                    binding.progressBar.progress = mSystemOtaStatus.process.toFloat()

                    // 只展示当前阶段的进度，避免多个进度视图交替出现。
                    // Show only the current stage's progress to avoid alternating between multiple progress views.
                    if (isDownOrSend) {
                        isDownOrSend = false
                        viewModel.sendIntent(SystemIntent.cancelDownload())
                        PSecuritySDK.getMessageService()?.getFileOperater()?.stopSendFile()

                        L.i(TAG, "handleEvent 眼镜系统正在升级，停止前两个阶段的流程。。。")
                    }
                } else if (status == OTA_UPDATE_STATUS.OTA_CORE_SYSTEM_REBOOT) {
                    binding.progressBar.visibility = View.GONE
                    binding.tvProcessTips.text = demoString(R.string.demo_update_complete_restarting)
                    GlobalEvent.autoConnectionEvent.call(workScope)

                    exitTipsMsg = ""
                    isDownOrSend = false

                    OtaManager.resetState3()
                    OtaManager.checkResetP2P()
                    showDoneTips()
                } else if (status == OTA_UPDATE_STATUS.OTA_CORE_SYSTEM_UPDATE_FAILED) {
                    binding.progressBar.visibility = View.GONE
                    exitTipsMsg = ""
                    Toast.makeText(this, demoString(R.string.demo_update_failed), Toast.LENGTH_SHORT).show()

                    isDownOrSend = false
                    OtaManager.resetState3()
                } else if (status == OTA_UPDATE_STATUS.OTA_CHECK_FAILED) {
                    binding.progressBar.visibility = View.GONE
                    exitTipsMsg = ""
                    Toast.makeText(this, demoString(R.string.demo_failed_to_check_for_updates_try_again), Toast.LENGTH_SHORT).show()

                    isDownOrSend = false
                    OtaManager.resetState3()
                }

            }

            else -> {}
        }

    }

    @SuppressLint("SetTextI18n")
    override fun render(state: SystemState) {
        L.d(TAG, "render " + state.updateStatus + " " + state.process +
                ", isState3: ${OtaManager.isState3}, isOTACompleted: ${OtaManager.isOTACompleted}")

        updateStatus = state.updateStatus

        if (OtaManager.isState3 || OtaManager.isOTACompleted) {
            L.d(TAG, "render 当前是第三阶段，或者已经升级完成。。。")
            return
        }

        isDownOrSend = (state.updateStatus == UpdateStatus.UPDATE_ING_DOWNLOAD || state.updateStatus == UpdateStatus.UPDATE_ING_SEND)

        // 下载
        // Download.
        if (state.updateStatus == UpdateStatus.UPDATE_ING_DOWNLOAD) {
            LoadingManager.hideLoading()
            binding.btSystem.visibility = View.GONE
            binding.clProcess.visibility = View.VISIBLE
            binding.progressBar.progress = state.process
            binding.progressBar.visibility = View.VISIBLE
            binding.tvStatus.text = demoString(R.string.demo_stage_1_3)
            binding.tvProcessTips.text =
                "${OtaManager.STATE_1} " + state.process.toInt() + " %"
        } else if (state.updateStatus == UpdateStatus.UPDATE_ING_SEND) {
            // 发送镜像到眼镜
            // Send the image to the glasses.
            LoadingManager.hideLoading()
            binding.btSystem.visibility = View.GONE
            binding.clProcess.visibility = View.VISIBLE
            binding.progressBar.progress = state.process
            binding.progressBar.visibility = View.VISIBLE
            binding.tvStatus.text = demoString(R.string.demo_stage_2_3)
            binding.tvProcessTips.text =
                "${OtaManager.STATE_2} " + state.process.toInt() + " %"
        } else if (state.updateStatus == UpdateStatus.UPDATE_FAILED) {
            L.i(TAG, "render 更新失败 btSystem.text: ${binding.btSystem.text}")

            if (binding.btSystem.text == btSystemFirstText) {
                L.i(TAG, "render 刚刚进入页面，不提示失败消息。。。")
                return
            }

            binding.btSystem.text = demoString(R.string.demo_retry)
            binding.btSystem.visibility = View.VISIBLE
            binding.clProcess.visibility = View.GONE
            isStartDownload = false
            Toast.makeText(this, demoString(R.string.demo_update_failed_2), Toast.LENGTH_SHORT).show()
        }

    }

    private fun setState3(progress: Int) {
        binding.btSystem.visibility = View.GONE
        binding.clProcess.visibility = View.VISIBLE
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = demoString(R.string.demo_stage_3_3)
        binding.tvProcessTips.text =
            "${OtaManager.STATE_3} " + progress + " %"
        binding.progressBar.progress = OtaManager.progressState3
    }

    override fun onResume() {
        super.onResume()
        isInOTAActivity = true

        L.i(TAG, "onResume updateStatus: $updateStatus, isState3: ${OtaManager.isState3}, " +
                "isInOTAActivity: $isInOTAActivity, isOTACompleted: ${OtaManager.isOTACompleted}")

        if (OtaManager.isState3) {
            setState3(OtaManager.progressState3.toInt())
            updateStatus = OTA_UPDATE_STATUS.OTA_CORE_SYSTEM_START
        }

        if (OtaManager.isOTACompleted) {
            binding.progressBar.visibility = View.GONE
            binding.tvProcessTips.text = demoString(R.string.demo_update_complete_restarting)
            GlobalEvent.autoConnectionEvent.call(workScope)

            exitTipsMsg = ""
            isDownOrSend = false

            OtaManager.resetState3()
            OtaManager.checkResetP2P()
            showDoneTips()
        }
    }

    override fun onStop() {
        super.onStop()

        L.d(TAG, "onStop.... , isInOTAActivity: $isInOTAActivity")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isOtaInProgress()) {
            SystemViewModel.release()
            L.d(TAG, "SystemViewModel release ...")
        }

        isInOTAActivity = false
        OtaManager.isOTACompleted = false

        L.d(TAG, "onDestroy isInOTAActivity: $isInOTAActivity, isOTACompleted: ${OtaManager.isOTACompleted}")

        mDialog?.dismiss()
        mDialog = null
    }

    private fun isOtaInProgress(): Boolean {
        L.d(TAG, "isOtaInProgress OTA 所处阶段：$updateStatus")

        return updateStatus == UpdateStatus.UPDATE_ING_DOWNLOAD ||
                updateStatus == UpdateStatus.UPDATE_ING_SEND ||
                OtaManager.isState3 ||
                updateStatus == OTA_UPDATE_STATUS.OTA_FILE_MOVE ||
                updateStatus == OTA_UPDATE_STATUS.OTA_FILE_MOVE_SUCCESS ||
                updateStatus == OTA_UPDATE_STATUS.OTA_CORE_SYSTEM_START
    }

    private fun stopUpdate() {
        L.i(TAG, "stopUpdate 停止升级。。。")

        viewModel.sendIntent(SystemIntent.cancelDownload())
        PSecuritySDK.getMessageService()?.getFileOperater()?.stopSendFile()
        OtaManager.cancelNotification()
        OtaManager.checkResetP2P()
        OtaManager.resetState3()
        SystemViewModel.release()
    }

    private fun showDoneTips() {
        L.d(TAG, "showDoneTips isOTACompleted: OtaManager.isOTACompleted, mDialog: $mDialog")

        OtaManager.isOTACompleted = false

        mDialog?.dismiss()
        mDialog = null
        mDialog = AlertDialog.Builder(this)
            .setTitle(demoString(R.string.demo_update_complete))
            .setMessage(demoString(R.string.demo_the_glasses_are_restarting))
            .setPositiveButton(demoString(R.string.demo_ok)) { dialog, which ->
                finish()
                SystemViewModel.release()
                startActivity(Intent(this, MainPhoneActivity::class.java))
            }
            .setCancelable(false)
            .show()
    }

    private fun showFinishTips() {
        L.d(TAG, "showFinishTips exitTipsMsg: $exitTipsMsg")

        if (exitTipsMsg.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(demoString(R.string.demo_notice))
                .setMessage(exitTipsMsg)
                .setPositiveButton(demoString(R.string.UserGuide_touchpad_Click_system_description)) { dialog, which ->
                    OtaManager.isShow = true
                    finish()
                }
                .setNegativeButton(demoString(R.string.prompter_cancel)) { dialog, which ->
                    dialog.dismiss()
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun showTipsForExit() {
        AlertDialog.Builder(this)
            .setTitle(demoString(R.string.demo_notice))
            .setMessage(demoString(R.string.demo_the_glasses_are_updating))
            .setPositiveButton(demoString(R.string.demo_run_in_background)) { dialog, which ->
                L.i(TAG, "退入后台继续升级。。。")

                ToastUtils.showLong(demoString(R.string.demo_updating_in_background))
                OtaManager.isShow = true
                finish()
            }
            .setNegativeButton(demoString(R.string.demo_cancel_update)) { dialog, which ->
                stopUpdate()
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun showBTConnectTips() {
        AlertDialog.Builder(this)
            .setTitle(demoString(R.string.demo_notice))
            .setMessage(demoString(R.string.demo_bluetooth_disconnected_reconnect_before_updating))
            .setPositiveButton(demoString(R.string.demo_ok)) { dialog, which ->
                L.i(TAG, "蓝牙断开，中断升级。。。")

                stopUpdate()
                finish()
                startActivity(Intent(this, MainPhoneActivity::class.java))
            }
            .setCancelable(false)
            .show()
    }

    private fun showApTips() {
    }

}
