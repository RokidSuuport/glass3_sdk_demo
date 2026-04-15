package com.rokid.phone.system.repository

import com.google.gson.Gson
import com.rokid.phone.data.CustomMessage
import com.rokid.phone.utils.ProjectBusinessType
import com.rokid.phone.utils.RKSystemInfo
import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.security.phone.sdk.api.base.DownloadListener
import com.rokid.security.phone.sdk.api.msg.listener.FileReceiveListener
import com.rokid.security.phone.sdk.base.utils.log.L
import java.io.File

/**
 * Author: zhangshengwei
 * Date: 2025/5/26
 */
class SystemRepository(
) {

    companion object{
        val TAG = "SystemRepository::"
    }

    var otaUrl = ""


    suspend fun checkUpdate(systemInfo: RKSystemInfo, listener: CheckUpdateListener){
        L.i(TAG,"checkUpdate-> sysVersion: ${systemInfo.version}, deviceTypeId: ${systemInfo.deviceTypeId},  deviceid: ${systemInfo.deviceId}")
        try {
            PSecuritySDK.getOtaEngineService()?.checkUpdate(systemInfo.version,systemInfo.osType,systemInfo.cpuType,systemInfo.deviceId,systemInfo.deviceTypeId)?.collect(
                { result ->

                    L.i(TAG,"checkUpdate->"+result.success+" "+result.data?.code+" "+result.data?.imageUrl + " newVersion: ${result.data?.version}")

                    if (!result.success || result.data ==null){
                        listener.onNeedUpdate(false,"","","")
                        return@collect
                    }
                    if (result.data!!.code != "OK" || result.data!!.imageUrl.isEmpty()){
                        listener.onNeedUpdate(false,"","","")
                    }else{
                        otaUrl = result.data!!.imageUrl
                        L.d(TAG,"checkUpdate otaUrl: $otaUrl")
                        listener.onNeedUpdate(true, result.data!!.changelog,result.data!!.checksum, result.data!!.version)
                    }
                }
            )
        }catch (e: Exception){
            L.i(TAG,"checkUpdate->"+e.message)
            throw kotlin.Exception(e.message)
        }
    }

    fun downUpdateWithResume(
        filePath: String,
        listener: DownloadListener,
        downloadedSize: Long = 0L
    ) {
        L.i(TAG, "downUpdateWithResume: 从 $downloadedSize 字节开始")

        try {
            // 尝试使用新的断点续传方法
            PSecuritySDK.getOtaEngineService().otaDownloadWithResume(
                otaUrl, filePath, createEnhancedListener(listener), downloadedSize
            )
        } catch (e: Exception) {
            L.d(TAG, "断点续传不可用，降级到普通下载: ${e.message}")
            // 降级到普通下载
//            downUpdate(filePath, listener)
        }
    }

    private fun createEnhancedListener(originalListener: DownloadListener): DownloadListener {
        return object : DownloadListener {
            override fun onResult(status: Int) {
                originalListener.onResult(status)
            }

            override fun onProgress(progress: Int) {
                originalListener.onProgress(progress)
            }

            override fun onProgress(progress: Int, downloaded: Long, total: Long) {
                // 传递详细信息
                originalListener.onProgress(progress, downloaded, total)
            }
        }
    }

//    fun downUpdate(filePath :String,listener: DownloadListener){
//        L.i(TAG, "downUpdate: filePath $filePath otaUrl:$otaUrl")
//        PSecuritySDK.getOtaEngineService()?.otaDownload(otaUrl,filePath,listener)
//
//    }


    fun cancelDownload(){
        PSecuritySDK.getOtaEngineService()?.cancelDownload()
    }


    fun sendGlassUpdateFile(filePath :String,fileReceiveListener:FileReceiveListener){
        L.i(TAG,"sendGlassUpdateFile: filePath ${filePath}")
        val file = File(filePath)
        if(file.exists()){
            PSecuritySDK.getMessageService()?.getFileOperater()?.sendFile(null,File(filePath),fileReceiveListener) {
                L.i(TAG,"sendFile: 操作 ${it.isSuccess}")
                if (!it.isSuccess){
                    fileReceiveListener.onFail()
                }
            }
        }else{
            fileReceiveListener.onFail()
            L.i(TAG,"文件不存在")
        }

    }

    fun sendGlassUpdateMsg(packagePath:String){

        var mCustomMessage = CustomMessage()
        mCustomMessage.type = ProjectBusinessType.SYSTEM_OTA_UPDATE
        mCustomMessage.message = packagePath

        var message = Gson().toJson(mCustomMessage)
        PSecuritySDK.getMessageService()?.sendTextMessageByClassicBT(message)

    }

    fun getGlassSystemInfoMsg(){

        var mCustomMessage = CustomMessage()
        mCustomMessage.type = ProjectBusinessType.GET_SYSTEM_INFO

        var message = Gson().toJson(mCustomMessage)
        PSecuritySDK.getMessageService()?.sendTextMessageByClassicBT(message)
    }

    interface CheckUpdateListener{
        fun onNeedUpdate(need:Boolean,content:String,fileMd5:String,version: String)
    }



}