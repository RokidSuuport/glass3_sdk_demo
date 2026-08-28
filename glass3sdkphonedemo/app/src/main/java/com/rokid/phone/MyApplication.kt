package com.rokid.phone

import android.app.Application
import com.rokid.phone.ui.BtWifiConnectActivity.Companion.frameTime
import com.rokid.security.phone.sdk.base.utils.other.defaultScope
import kotlinx.coroutines.launch


class MyApplication : Application() {

    var isBtConnect = false

    companion object {
        lateinit var instance: MyApplication
        val frameList = mutableListOf<Pair<Int, Int>>()
        var MainAppClientId = "RokidESecurity"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        initFrame()

    }
    fun initFrame() {
        defaultScope.launch {
            val list = listOf(
                R.mipmap.fx_matching_000 to frameTime,  // 第一帧，持续100ms
//                R.mipmap.fx_matching_003 to frameTime,
//                R.mipmap.fx_matching_006 to frameTime,
                R.mipmap.fx_matching_009 to frameTime,
//                R.mipmap.fx_matching_012 to frameTime,  // 第一帧，持续100ms
//                R.mipmap.fx_matching_015 to frameTime,
                R.mipmap.fx_matching_018 to frameTime,
//                R.mipmap.fx_matching_021 to frameTime,
//                R.mipmap.fx_matching_024 to frameTime,  // 第一帧，持续100ms
                R.mipmap.fx_matching_027 to frameTime,
//                R.mipmap.fx_matching_030 to frameTime,
//                R.mipmap.fx_matching_033 to frameTime,
                R.mipmap.fx_matching_036 to frameTime,  // 第一帧，持续100ms
//                R.mipmap.fx_matching_039 to frameTime,
//                R.mipmap.fx_matching_042 to frameTime,
                R.mipmap.fx_matching_045 to frameTime,
//                R.mipmap.fx_matching_048 to frameTime,  // 第一帧，持续100ms
//                R.mipmap.fx_matching_051 to frameTime,
                R.mipmap.fx_matching_054 to frameTime,
//                R.mipmap.fx_matching_057 to frameTime,
//                R.mipmap.fx_matching_060 to frameTime,  // 第一帧，持续100ms
                R.mipmap.fx_matching_063 to frameTime,
//                R.mipmap.fx_matching_066 to frameTime,
//                R.mipmap.fx_matching_069 to frameTime,
                R.mipmap.fx_matching_072 to frameTime,  // 第一帧，持续100ms
//                R.mipmap.fx_matching_075 to frameTime,
//                R.mipmap.fx_matching_078 to frameTime,
                R.mipmap.fx_matching_081 to frameTime,
//                R.mipmap.fx_matching_084 to frameTime,
//                R.mipmap.fx_matching_087 to frameTime,
                R.mipmap.fx_matching_090 to frameTime,  // 第一帧，持续100ms
//                R.mipmap.fx_matching_093 to frameTime,
//                R.mipmap.fx_matching_096 to frameTime,
                R.mipmap.fx_matching_099 to frameTime,
//                R.mipmap.fx_matching_102 to frameTime,
//                R.mipmap.fx_matching_105 to frameTime,
                R.mipmap.fx_matching_108 to frameTime,  // 第一帧，持续100ms
//                R.mipmap.fx_matching_111 to frameTime,
//                R.mipmap.fx_matching_114 to frameTime,
                R.mipmap.fx_matching_117 to frameTime,
//                R.mipmap.fx_matching_120 to frameTime
            )
            frameList.addAll(list)
        }
    }

}
