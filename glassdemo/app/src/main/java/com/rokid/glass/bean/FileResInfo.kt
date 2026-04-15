package com.rokid.glass.bean

/**
 * Author: zhangshengwei
 * Date: 2025/7/10
 */
class FileResInfo {

    companion object{
        var AlbumDirPath = "/Album/"
    }

    var fileName = ""

    var fileUrl= ""

    // SyncState中常量
    var syncState = ""

    // FileType中常量
    var fileType :String?= null

    var glassFilePath= ""
    var phoneFilePath= ""
    var fileMd5:String?= null

    var message = ""


}




object SyncState{


    //glass->phone 同步中
    val SyncIng = "SyncIng"

    //glass->phone 本地同步成功
    val SyncNativeSuccess= "SyncNativeSuccess"
    //glass->phone 本地同步失败
    val SyncNativeFailed= "SyncNativeFailed"

    //phone->net 同步服务端成功
    val SyncSuccess= "SyncNetSuccess"
    //phone->net 同步服务端成功
    val SyncFailed= "SyncFailed"


}




object FileType{


    val ALBUM = "ALBUM"

}



