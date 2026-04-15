package com.rokid.phone.system.ui

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

//    override val viewModel = SystemViewModel(SystemRepository())

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

    private val btSystemFirstText = "检测新版本"

    // 2025.12.3: 改为单例模式，关闭页面后不影响 ViewModule 生命周期
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

        // 2025.12.4: onInit 会执行两次，BaseActivity、MviActivity，
        //      如果把 MviActivity 里面的去掉，则按钮不起作用，先保持原样，不做更改
        if (isFirst) {
            isFirst = false
            OtaManager.start()
        }

//        if (OtaManager.isState3) {
//            setState3(OtaManager.progressState3.toInt())
//            updateStatus = OTA_UPDATE_STATUS.OTA_CORE_SYSTEM_START
//        }

        L.i(TAG, "SystemOtaActivity onInit p2p: ${GlobalData.p2pConnectState.value}" +
                " 是否是第三阶段: ${OtaManager.isState3}, progress: ${OtaManager.progressState3}")
    }

    private fun initListener() {
        binding.btSystem.setOnClickListener {
            L.i(TAG, "onInit SystemGlobalConstant: " + SystemGlobalConstant.isCharge + " "
                    + SystemGlobalConstant.powerValue + " updateStatus:" + updateStatus)

            if (!(SystemGlobalConstant.isCharge) && SystemGlobalConstant.powerValue != 100) {
                Toast.makeText(this, "请保持眼镜在充电状态", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (updateStatus == UpdateStatus.UPDATE_ING_DOWNLOAD || updateStatus == UpdateStatus.UPDATE_ING_SEND) {
                Toast.makeText(this, "正在更新请稍后", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!NetworkUtil.isNetworkAvailable(this)) {
                ToastUtils.showShort("网络无连接！")
                return@setOnClickListener
            }

            if (!GlobalData.btConnectState.value) {
                ToastUtils.showShort("蓝牙未连接！")
                return@setOnClickListener
            }

            if (isOtaInProgress()) {
                L.i(TAG, "initListener 正处于 OTA 过程中，忽略按钮点击。。。")
                return@setOnClickListener
            }

            // 2025.11.12: 由于官方没有统一的热点检测方法，极少数机型上可能会检测出错，所以改成底部文字提示
//            if (HotspotDetector.isHotspotEnabled(this)) {
//                showApTips()
//                return@setOnClickListener
//            }

            if (System.currentTimeMillis() - clickTime < 500) {
                L.i(TAG, "initListener 忽略快速重复点击。。。")
                return@setOnClickListener
            }

            clickTime = System.currentTimeMillis()

            if (isNeedUpdate) {
//                updateStatus = UpdateStatus.UPDATE_ING
//                binding.btSystem.visibility = View.GONE
//                LoadingManager.showLoading(this, "更新准备...")
//
//                L.d(TAG, "开始更新准备中。。。")
//
//                viewModel.sendIntent(SystemIntent.startUpdate(filePath, fileMd5))

                L.i(TAG, "initListener 开始更新准备中...")

                prepareConnectP2P()
            } else {
                L.i(TAG, "initListener 版本检测中...")

                LoadingManager.showLoading(this, "版本检测中...")

                val info = RKSystemInfo()
                info.osType = SystemGlobalConstant.osType
                info.version = SystemGlobalConstant.version
//                info.version = "1.01.002-20250619-151102"
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

        // 2025.12.2: 测试代码
//        Handler().postDelayed({
//            viewModel.startSystemUpdate()
//        }, 12000)
    }

    override fun onBackPressed() {
        L.d(TAG, "onBackPressed updateStatus: $updateStatus")

        if (updateStatus == OTA_UPDATE_STATUS.OTA_CORE_SYSTEM_START || OtaManager.isState3) {
            exitTipsMsg = "眼镜系统正在升级中，可关闭页面等待升级完成"
            showFinishTips()

            return
        } else if (updateStatus == UpdateStatus.UPDATE_ING_DOWNLOAD || updateStatus == UpdateStatus.UPDATE_ING_SEND) {
            exitTipsMsg = "眼镜系统正在升级中，确认中断吗？"
//            showFinishTips()

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
                LoadingManager.showLoading(this, "准备更新...")

                startDownload()
                getP2PKeepConnect()
            } else {
                L.d(TAG, "prepareConnectP2P 222 通道连接中...")
                LoadingManager.showLoading(this, "通道连接中...")

                PSecuritySDK.getWifiP2PClientService()?.sendConnectP2pRequest { isConnect ->
                    L.d(TAG, "prepareConnectP2P P2P自动连接结果 isConnect: $isConnect, isStartDownload: $isStartDownload")

                    if (!isStartDownload) {
                        runOnUiThread {
                            if (isConnect) {
                                L.d(TAG, "prepareConnectP2P 222 准备更新...")
                                LoadingManager.showLoading(this, "准备更新...")

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
            .setTitle("连接失败")
            .setMessage("P2P自动连接失败，请手动尝试")
            .setPositiveButton("确定") { dialog, which ->
                if (!GlobalData.p2pConnectState.value) {
                    if (ProtocolVersionUtils.isGlassVersion1()) {
                        startActivity(Intent(this, BtWifiConnectActivity::class.java))
                    } else {
                        BtWifiConnectActivity.start(this, isConnetBt = false, isConnetP2p = true)
                    }
                }

                dialog.dismiss()
            }
            .setNegativeButton("取消") { dialog, which ->
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
                    binding.btSystem.text = "立即更新"
                    binding.tvNewSystemVersion.text = "版本号 " + event.version
//                    binding.clProcess.visibility = View.VISIBLE
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
                    binding.tvProcessTips.text = "OTA文件正在迁移至升级路径"
                    isDownOrSend = false
                } else if (status == OTA_UPDATE_STATUS.OTA_FILE_MOVE_SUCCESS) {
                    binding.progressBar.visibility = View.GONE
                    binding.tvProcessTips.text = "OTA文件迁移至升级路径成功"
                    isDownOrSend = false
                } else if (status == OTA_UPDATE_STATUS.OTA_FILE_MOVE_FAILED) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this, "OTA文件迁移至升级路径失败", Toast.LENGTH_SHORT).show()
                    isDownOrSend = false
                } else if (status == OTA_UPDATE_STATUS.OTA_CORE_SYSTEM_START) {
                    // 2025.12.2: 如果此阶段中app页面关闭再进来，就不能正常看到进度，所以每次隐藏下按钮布局
                    binding.btSystem.visibility = View.GONE
                    binding.clProcess.visibility = View.VISIBLE
                    binding.progressBar.visibility = View.VISIBLE
//                    binding.tvProcessTips.text = "正在进行升级，阶段"+mSystemOtaStatus.state+" 更新中"+mSystemOtaStatus.process+"%"
                    binding.tvStatus.text = "阶段(3/3):"
                    binding.tvProcessTips.text = "${OtaManager.STATE_3} " + mSystemOtaStatus.process + " %"
                    binding.progressBar.progress = mSystemOtaStatus.process.toFloat()

                    // 2025.12.2: 避免交替显示两个下载进度的现象
                    if (isDownOrSend) {
                        isDownOrSend = false
                        viewModel.sendIntent(SystemIntent.cancelDownload())
                        PSecuritySDK.getMessageService()?.getFileOperater()?.stopSendFile()

                        L.i(TAG, "handleEvent 眼镜系统正在升级，停止前两个阶段的流程。。。")
                    }
                } else if (status == OTA_UPDATE_STATUS.OTA_CORE_SYSTEM_REBOOT) {
                    binding.progressBar.visibility = View.GONE
                    binding.tvProcessTips.text = "升级完成正在重启中"
                    GlobalEvent.autoConnectionEvent.call(workScope)

                    exitTipsMsg = ""
                    isDownOrSend = false

                    OtaManager.resetState3()
                    OtaManager.checkResetP2P()
                    showDoneTips()
                } else if (status == OTA_UPDATE_STATUS.OTA_CORE_SYSTEM_UPDATE_FAILED) {
                    binding.progressBar.visibility = View.GONE
                    exitTipsMsg = ""
                    Toast.makeText(this, "升级失败", Toast.LENGTH_SHORT).show()

                    isDownOrSend = false
                    OtaManager.resetState3()
                } else if (status == OTA_UPDATE_STATUS.OTA_CHECK_FAILED) {
                    binding.progressBar.visibility = View.GONE
                    exitTipsMsg = ""
                    Toast.makeText(this, "升级检测失败，请重试", Toast.LENGTH_SHORT).show()

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

        if (state.updateStatus == UpdateStatus.UPDATE_ING_DOWNLOAD) { // 下载
            LoadingManager.hideLoading()
            binding.btSystem.visibility = View.GONE
            binding.clProcess.visibility = View.VISIBLE
            binding.progressBar.progress = state.process
            binding.progressBar.visibility = View.VISIBLE
            binding.tvStatus.text = "阶段(1/3):"
            binding.tvProcessTips.text =
//                "镜像下载 " + String.format(Locale.US, "%.1f", state.process) + " %"
                "${OtaManager.STATE_1} " + state.process.toInt() + " %"
        } else if (state.updateStatus == UpdateStatus.UPDATE_ING_SEND) { // 发送镜像到眼镜
            LoadingManager.hideLoading()
            binding.btSystem.visibility = View.GONE
            binding.clProcess.visibility = View.VISIBLE
            binding.progressBar.progress = state.process
            binding.progressBar.visibility = View.VISIBLE
            binding.tvStatus.text = "阶段(2/3):"
            binding.tvProcessTips.text =
//                "镜像同步 " + String.format(Locale.US, "%.1f", state.process) + " %"
                "${OtaManager.STATE_2} " + state.process.toInt() + " %"
        } else if (state.updateStatus == UpdateStatus.UPDATE_FAILED) {
            L.i(TAG, "render 更新失败 btSystem.text: ${binding.btSystem.text}")

            if (binding.btSystem.text == btSystemFirstText) {
                L.i(TAG, "render 刚刚进入页面，不提示失败消息。。。")
                return
            }

            binding.btSystem.text = "重试"
            binding.btSystem.visibility = View.VISIBLE
            binding.clProcess.visibility = View.GONE
            isStartDownload = false
            Toast.makeText(this, "更新失败", Toast.LENGTH_SHORT).show()
        }

    }

    private fun setState3(progress: Int) {
        binding.btSystem.visibility = View.GONE
        binding.clProcess.visibility = View.VISIBLE
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = "阶段(3/3):"
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
            binding.tvProcessTips.text = "升级完成正在重启中"
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
            .setTitle("升级完成")
            .setMessage("眼镜系统正在重启中...")
            .setPositiveButton("确定") { dialog, which ->
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
                .setTitle("提示")
                .setMessage(exitTipsMsg)
                .setPositiveButton("确认") { dialog, which ->
                    OtaManager.isShow = true
                    finish()
                }
                .setNegativeButton("取消") { dialog, which ->
                    dialog.dismiss()
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun showTipsForExit() {
        AlertDialog.Builder(this)
            .setTitle("提示")
            .setMessage("眼镜系统正在升级中")
            .setPositiveButton("后台运行") { dialog, which ->
                L.i(TAG, "退入后台继续升级。。。")

                ToastUtils.showLong("后台升级中...")
                OtaManager.isShow = true
                finish()
            }
            .setNegativeButton("中止升级") { dialog, which ->
                stopUpdate()
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun showBTConnectTips() {
        AlertDialog.Builder(this)
            .setTitle("提示")
            .setMessage("蓝牙断开，请重新连接后再进行升级！")
            .setPositiveButton("确定") { dialog, which ->
                L.i(TAG, "蓝牙断开，中断升级。。。")

                stopUpdate()
                finish()
                startActivity(Intent(this, MainPhoneActivity::class.java))
            }
//            .setNegativeButton("取消") {dialog, which ->
//                dialog.dismiss()
//            }
            .setCancelable(false)
            .show()
    }

    private fun showApTips() {
//        AlertDialog.Builder(this)
//            .setTitle("通道冲突")
//            .setMessage("请关闭手机热点后再次重试!")
//            .setPositiveButton("确定") { dialog, which ->
//                dialog.dismiss()
//            }
//            .setCancelable(true)
//            .show()
    }

}