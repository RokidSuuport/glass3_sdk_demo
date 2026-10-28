package com.rokid.phone.system.viewmodel

import com.rokid.phone.utils.demoString
import com.rokid.phone.R

import com.blankj.utilcode.util.ToastUtils
import com.google.gson.Gson
import com.rokid.phone.MyApplication
import com.rokid.phone.base.viewmodel.MviViewModel
import com.rokid.phone.data.CustomMessage
import com.rokid.phone.system.OtaManager
import com.rokid.phone.system.model.SystemEvent
import com.rokid.phone.system.model.SystemIntent
import com.rokid.phone.system.model.SystemState
import com.rokid.phone.system.model.UpdateStatus
import com.rokid.phone.system.repository.SystemRepository
import com.rokid.phone.utils.FileUtil
import com.rokid.phone.utils.OTA_UPDATE_STATUS.Companion.OTA_CHECK_FAILED
import com.rokid.phone.utils.ProjectBusinessType
import com.rokid.phone.utils.RKSystemInfo
import com.rokid.phone.utils.SPUtil
import com.rokid.phone.utils.SpKeyConstant
import com.rokid.phone.utils.SystemGlobalConstant
import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.security.phone.sdk.api.base.DownloadListener
import com.rokid.security.phone.sdk.api.base.DownloadStatus
import com.rokid.security.phone.sdk.api.msg.listener.FileReceiveListener
import com.rokid.security.phone.sdk.api.msg.listener.IMessageListener
import com.rokid.security.phone.sdk.base.utils.log.L
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class SystemViewModel constructor(
    private val repository: SystemRepository
) : MviViewModel<SystemState, SystemIntent, SystemEvent>() {

    private val TAG = "SystemViewModel::"
    private var mGson = Gson()
    private var filePath = ""
    private var serverFileMd5 = ""

    companion object {
        @Volatile
        private var instance: SystemViewModel? = null

        fun getInstance(): SystemViewModel {
            return instance ?: synchronized(this) {
                instance ?: SystemViewModel(SystemRepository()).also {
                    L.i("SystemOtaActivity", "SystemViewModel init...")

                    instance = it
                    PSecuritySDK.getMessageService()?.addMessageListener(it.mListener)
                }
            }
        }

        fun release() {
            synchronized(this) {
                L.i("SystemOtaActivity", "SystemViewModel release...")

                instance?.let { viewModel ->
                    PSecuritySDK.getMessageService()?.removeMessageListener(viewModel.mListener)
                }

                instance = null
            }
        }

        fun isNull(): Boolean {
            synchronized(this) {
                return instance == null
            }
        }
    }

    private val mListener = object : IMessageListener {
        override fun onClassicBTTextMessage(msg: String, clientId: String) {
            try {
                // 普通蓝牙文本不是系统业务消息，解析不到 CustomMessage 时直接忽略。
                // Plain Bluetooth text is not a system business message; ignore it if it cannot be parsed as CustomMessage.
                val customMessage = CustomMessage.fromClassicBtPayload(mGson, msg) ?: return
                if (customMessage.type == ProjectBusinessType.SYSTEM_INFO_RESPONSE) {
                    val systemInfo =
                        mGson.fromJson(customMessage.message, RKSystemInfo::class.java)
                    L.d(TAG, "onClassicBTTextMessage: ${Gson().toJson(systemInfo)}")
                    if (systemInfo != null) {
                        SystemGlobalConstant.osType = systemInfo.osType
                        SystemGlobalConstant.cpuType = systemInfo.cpuType
                        SystemGlobalConstant.version = systemInfo.version
                        SystemGlobalConstant.deviceId = systemInfo.deviceId
                        SystemGlobalConstant.deviceTypeId = systemInfo.deviceTypeId
                        SystemGlobalConstant.isCharge = systemInfo.isCharge
                        SystemGlobalConstant.powerValue = systemInfo.powerValue
                        SystemGlobalConstant.brightness = systemInfo.brightness
                        SystemGlobalConstant.maxBrightness = systemInfo.maxBrightness
                        SystemGlobalConstant.isAutoBrightness = systemInfo.isAutoBrightness
                        SystemGlobalConstant.curVolume = systemInfo.curVolume
                        SystemGlobalConstant.maxVolume = systemInfo.maxVolume

                        sendEvent(SystemEvent.SystemInfo(systemInfo))
                    }
                } else if (customMessage.type == ProjectBusinessType.SYSTEM_OTA_UPDATE_STATUS) {
                    sendEvent(SystemEvent.UpdateState(customMessage.message))

                    OtaManager.updateState3(customMessage.message, "SystemViewModel")
                } else if (customMessage.type == ProjectBusinessType.POWER_UPDATE) {
                    val systemInfo =
                        mGson.fromJson(customMessage.message, RKSystemInfo::class.java)
                    L.d(TAG, "POWER_UPDATE onClassicBTTextMessage: ${Gson().toJson(systemInfo)}")
                    if (systemInfo != null) {
                        SystemGlobalConstant.isCharge = systemInfo.isCharge
                        SystemGlobalConstant.powerValue = systemInfo.powerValue
                    }
                }
            } catch (e: Exception) {
                L.e(TAG, "onClassicBTTextMessage 解析异常: ${e.message}", e)
            }
        }
    }

    override fun createInitialState(): SystemState = SystemState()
    override suspend fun handleIntent(intent: SystemIntent) {

        L.d(TAG, "handleIntent intent: ${mGson.toJson(intent)}")

        if (intent is SystemIntent.checkUpdate) {
            checkUpdate(intent.mSystemInfo)
        } else if (intent is SystemIntent.startUpdate) {

            CoroutineScope(Dispatchers.Default).launch {
                downUpdate(intent.path, intent.fileMd5)
            }
        } else if (intent is SystemIntent.getSystemInfo) {
            getGlassSystemInfoMsg()
        } else if (intent is SystemIntent.cancelDownload) {
            cancelDownload()
        }
    }


    private suspend fun checkUpdate(info: RKSystemInfo) {
        this.filePath = filePath
        try {
            repository.checkUpdate(info, mCheckUpdateListener)
        } catch (e: Exception) {
            L.d(TAG, "checkUpdate handleIntent: ${e.message}")
            var mSystemOtaStatus = SystemOtaStatus()
            mSystemOtaStatus.status = OTA_CHECK_FAILED
            mSystemOtaStatus.process = 0
            mSystemOtaStatus.state = 0
            var message = mGson.toJson(mSystemOtaStatus)
            sendEvent(SystemEvent.UpdateState(message))
        }
    }

    private fun downUpdate(path: String, fileMd5: String) {
        this.filePath = path
        val file = File(path)

        serverFileMd5 = fileMd5

        L.d(TAG, "downUpdate 开始更新检测 path: $path, 服务端返回的fileMd5: $fileMd5")

        try {
            if (file.exists()) {
                val md5 = FileUtil.getFileMD5(file)
                val oldMd5 = getOldOtaFileMd5()

                L.d(TAG, "文件存在，计算的 MD5: $md5, 保存的上一次的 oldMd5: $oldMd5")

                if (md5 == fileMd5) {
                    // 文件完整且校验通过
                    // The file is complete and verification passed.
                    L.d(TAG, "文件完整，直接上传，md5 == fileMd5")
                    setState {
                        SystemState(
                            updateStatus = UpdateStatus.UPDATE_ING_DOWNLOAD,
                            process = 100f
                        )
                    }
                    sendGlassUpdateFile(path)
                    return
                } else {
                    // 文件存在但MD5不匹配或是文件不完整
                    // The file exists, but its MD5 does not match or it is incomplete.
                    val downloadedSize = file.length()
                    L.d(TAG, "文件不完整，大小: $downloadedSize 字节")

                    // 检查是否可能是断点续传（文件大小>0且MD5匹配）
                    // Check whether the download may be resumed: the file size is greater than zero and the MD5 matches.
                    if (oldMd5 == fileMd5 && downloadedSize > 0) {
                        // 尝试获取服务器文件信息来验证是否支持续传
                        // Try to obtain server file information to verify resumable download support.
                        L.d(TAG, "md5 一致，且检测到不完整文件，尝试续传: $downloadedSize 字节")
                        repository.downUpdateWithResume(path, mResumeListener, downloadedSize)
                        return
                    } else {
                        if (oldMd5 != fileMd5) {
                            L.d(TAG, "md5 不一致，文件版本变更，重新下载。。。")
                        } else {
                            L.d(TAG, "md5 一致，但文件为空，重新下载。。。")
                        }

                        // 保存最新  md5
                        // Save the latest MD5.
                        saveNewOtaFileMd5(fileMd5)

                        if (file.delete()) {
                            L.d(TAG, "成功删除旧文件")
                        } else {
                            L.w(TAG, "删除文件失败，尝试覆盖下载")
                        }

                        repository.downUpdateWithResume(path, mResumeListener, 0L)

                        return
                    }
                }
            } else {
                // 文件不存在，重新下载
                // The file does not exist; download it again.
                L.d(TAG, "文件不存在，开始新下载")

                // 保存最新  md5
                // Save the latest MD5.
                saveNewOtaFileMd5(fileMd5)

                repository.downUpdateWithResume(path, mResumeListener, 0L)
            }
        } catch (e: Exception) {
            L.e(TAG, "downUpdate 异常: ${e.message}", e)
            // 异常处理：显示错误状态
            // Exception handling: display an error state.

            setState {
                SystemState(
                    updateStatus = UpdateStatus.UPDATE_FAILED,
                    process = 0f
                )
            }

            // 可以选择重试或提示用户
            // Optionally retry or prompt the user.
            ToastUtils.showLong(demoString(R.string.demo_failed_to_prepare_download, e.message))
        }
    }

    private fun getOldOtaFileMd5(): String {
        return SPUtil.getInstance(MyApplication.instance)
            .getString("${SystemGlobalConstant.deviceId}_${SpKeyConstant.OTA_FILE_MD5}", "")
    }

    private fun saveNewOtaFileMd5(md5: String) {
        // 保存文件 MD5，供下一次下载判断是否可以断点续传。
        // Save the file's MD5 to determine whether a later download can be resumed.
        SPUtil.getInstance(MyApplication.instance).putString(
            "${SystemGlobalConstant.deviceId}_${SpKeyConstant.OTA_FILE_MD5}",
            md5
        )
    }

    // 统一的监听器
    // Shared listener.
    private var mResumeListener = object : DownloadListener {
        override fun onResult(status: Int) {
            L.d(TAG, "onResult $status")
            if (status == DownloadStatus.DOWNLOAD_STATUS_DOWNLOAD_FAILED) {
                setState {
                    SystemState(
                        updateStatus = UpdateStatus.UPDATE_FAILED,
                        process = currentState().process
                    )
                }

                OtaManager.showFailure(demoString(R.string.demo_download_failed))
            } else if (status == DownloadStatus.DOWNLOAD_STATUS_DOWNLOADED) {
                sendGlassUpdateFile(filePath)
            }
        }

        override fun onProgress(progress: Int) {
            onProgress(progress, 0L, 0L)
        }

        override fun onProgress(progress: Int, downloaded: Long, total: Long) {
            L.d(TAG, "下载进度: $progress%")
            setState {
                SystemState(
                    updateStatus = UpdateStatus.UPDATE_ING_DOWNLOAD,
                    process = progress.toFloat()
                )
            }
            OtaManager.showDownloadProgress(progress, OtaManager.STATE_1_ALL)
        }
    }


    private fun cancelDownload() {
        repository.cancelDownload()
    }


    fun sendGlassUpdateFile(glassFilePath: String) {
        if (glassFilePath.isNotEmpty()) {
            L.d(TAG, "sendGlassUpdateFile serverFileMd5: $serverFileMd5, glassFilePath: $glassFilePath")

            // 续传前再次校验本地记录与服务端文件 MD5 是否一致。
            // Before resuming, verify again that the locally recorded MD5 matches the server file's MD5.
            val file = File(glassFilePath)
            if (file.exists()) {
                val md5 = FileUtil.getFileMD5(file)
                // 校验通过
                // Verification passed.
                if (md5 == serverFileMd5) {
                    L.d(TAG, "sendGlassUpdateFile md5 校验通过，开始发送镜像。。。")

                    repository.sendGlassUpdateFile(glassFilePath, fileReceiveListener)
                } else {
                    L.e(
                        TAG, "sendGlassUpdateFile md5 校验不通过，file size: ${file.length()}, " +
                                "md5: $md5, serverFileMd5: $serverFileMd5, 删除文件重新下载。。。"
                    )

                    ToastUtils.showLong(demoString(R.string.demo_md5_verification_failed_download_again))
                    repeatDownload(glassFilePath)
                }
            } else {
                L.e(TAG, "sendGlassUpdateFile 文件不存在，重新下载。。。")

                repeatDownload(glassFilePath)
            }
        } else {
            setState { SystemState(updateStatus = UpdateStatus.UPDATE_FAILED, process = 0F) }
        }
    }

    private fun repeatDownload(path: String) {
        L.d(TAG, "repeatDownload 重新下载，path: $path")

        File(path).delete()

        // 保存最新  md5
        // Save the latest MD5.
        saveNewOtaFileMd5(serverFileMd5)

        repository.downUpdateWithResume(path, mResumeListener, 0L)
    }


    private fun sendGlassUpdateMsg(packagePath: String) {
        repository.sendGlassUpdateMsg(packagePath)
    }


    private fun getGlassSystemInfoMsg() {
        repository.getGlassSystemInfoMsg()
    }

    private var mCheckUpdateListener = object : SystemRepository.CheckUpdateListener {

        override fun onNeedUpdate(
            need: Boolean,
            content: String,
            fileMd5: String,
            version: String
        ) {
            L.d(TAG, "onNeedUpdate $need")
            sendEvent(SystemEvent.NeedUpdate(need, content, fileMd5, version))
        }

    }


    private val fileReceiveListener = object : FileReceiveListener {
        override fun onStart() {
            L.d(TAG, "fileReceiveListener onStart ")
            setState { SystemState(updateStatus = UpdateStatus.UPDATE_ING_SEND, process = 0F) }

            OtaManager.showDownloadProgress(0, OtaManager.STATE_2_ALL)
        }

        override fun onProgressChanged(progress: Float) {
            L.d(TAG, "fileReceiveListener onProgressChanged $progress")
            setState {
                SystemState(
                    updateStatus = UpdateStatus.UPDATE_ING_SEND,
                    process = progress
                )
            }

            OtaManager.showDownloadProgress(progress.toInt(), OtaManager.STATE_2_ALL)
        }

        override fun onComplete(filePath: String) {
            L.d(TAG, "fileReceiveListener onComplete")
            setState { SystemState(updateStatus = UpdateStatus.UPDATE_ING_SEND, process = 100F) }
            sendGlassUpdateMsg(filePath)

            OtaManager.showDownloadProgress(100, OtaManager.STATE_2_ALL)
        }

        override fun onFail() {
            L.d(TAG, "fileReceiveListener onFail")
            setState { SystemState(updateStatus = UpdateStatus.UPDATE_FAILED, process = 0F) }

            OtaManager.showFailure(demoString(R.string.demo_file_transfer_failed))
        }

        override fun onCancel() {
            L.d(TAG, "fileReceiveListener onCancel: 取消了发送文件,对方停止接收文件")

            OtaManager.cancelNotification()
        }
    }

}
