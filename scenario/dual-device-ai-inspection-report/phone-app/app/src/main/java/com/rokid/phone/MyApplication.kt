package com.rokid.phone

import android.app.Application

class MyApplication : Application() {
    companion object {
        lateinit var instance: MyApplication
        const val MainAppClientId = "RokidESecurity"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
