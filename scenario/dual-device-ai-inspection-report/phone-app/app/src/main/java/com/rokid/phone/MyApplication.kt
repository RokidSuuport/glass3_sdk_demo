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
                // 第一帧，持续100ms
                // The first frame lasts 100 ms.
                R.mipmap.fx_matching_000 to frameTime,
                R.mipmap.fx_matching_009 to frameTime,
                R.mipmap.fx_matching_018 to frameTime,
                R.mipmap.fx_matching_027 to frameTime,
                // 第一帧，持续100ms
                // The first frame lasts 100 ms.
                R.mipmap.fx_matching_036 to frameTime,
                R.mipmap.fx_matching_045 to frameTime,
                R.mipmap.fx_matching_054 to frameTime,
                R.mipmap.fx_matching_063 to frameTime,
                // 第一帧，持续100ms
                // The first frame lasts 100 ms.
                R.mipmap.fx_matching_072 to frameTime,
                R.mipmap.fx_matching_081 to frameTime,
                // 第一帧，持续100ms
                // The first frame lasts 100 ms.
                R.mipmap.fx_matching_090 to frameTime,
                R.mipmap.fx_matching_099 to frameTime,
                // 第一帧，持续100ms
                // The first frame lasts 100 ms.
                R.mipmap.fx_matching_108 to frameTime,
                R.mipmap.fx_matching_117 to frameTime,
            )
            frameList.addAll(list)
        }
    }

}
