package com.rokid.glass.event

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.blankj.utilcode.util.Utils
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.client.IServiceConnectionCallback
import com.rokid.security.system.server.IClientCallback
import com.rokid.security.system.server.device.listener.DeviceEventListener

/**
 * Receives custom events in a separate process so the sender can verify the full Binder path.
 * 使用独立进程接收自定义事件，便于发送进程验证完整 Binder 链路。
 */
class CusEventReceiverService : Service() {
    companion object {
        private const val TAG = "CusEventReceiver"
    }

    private val deviceEventListener = object : DeviceEventListener.Stub() {
        override fun onDeviceEvent(eventCode: Int, extra: String?) {
            Log.d(TAG, "onDeviceEvent code=$eventCode extra=$extra")
        }

        override fun onCusEvent(eventCode: Int, extra: String?) {
            Log.d(TAG, "onCusEvent code=$eventCode extra=$extra")
        }
    }

    private val clientCallback = object : IClientCallback.Stub() {
        override fun onReady() {
            val service = GlassSdk.getGlassDeviceService()
            if (service == null) {
                Log.e(TAG, "device service unavailable")
                return
            }
            runCatching {
                service.setDeviceEventListener(deviceEventListener)
                Log.d(TAG, "setDeviceEventListener success")
            }.onFailure { error ->
                Log.e(TAG, "setDeviceEventListener failed: ${error.message}", error)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        GlassSdk.bindSecurityService(Utils.getApp(), object : IServiceConnectionCallback {
            override fun onServiceConnected() {
                runCatching {
                    GlassSdk.registerClient("GlassCusEventReceiver", clientCallback)
                    Log.d(TAG, "register receiver client returned")
                }.onFailure { error ->
                    Log.e(TAG, "register receiver client failed: ${error.message}", error)
                }
            }

            override fun onServiceDisconnected() {
                Log.w(TAG, "security service disconnected")
            }

            override fun onBindingDied() {
                Log.e(TAG, "security service binding died")
            }
        })
    }

    override fun onDestroy() {
        runCatching {
            GlassSdk.getGlassDeviceService()?.setDeviceEventListener(null)
            Log.d(TAG, "setDeviceEventListener(null) success")
        }.onFailure { error ->
            Log.w(TAG, "clear receiver listener failed: ${error.message}", error)
        }
        GlassSdk.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
