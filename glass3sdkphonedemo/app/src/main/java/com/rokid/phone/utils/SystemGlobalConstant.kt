package com.rokid.phone.utils


object SystemGlobalConstant {

    var version = ""
    var osType = ""
    var cpuType = ""

    var deviceId = ""
    var deviceTypeId = ""

    var isCharge = false
    var powerValue = 0
    var brightness = 0
    var maxBrightness = 255
    var isAutoBrightness = false
    var curVolume = 8
    var maxVolume = 15




    @Volatile
    var glassRingConnected = false
    @Volatile
    var glassRingBluetoothDeviceName :String?= null

}
