package com.rokid.glass.mediastream.capture.internal.video

import com.rokid.glass.mediastream.capture.VideoCaptureOptions

internal interface CameraShareGateway {
    fun start(
        options: VideoCaptureOptions,
        callback: Callback,
    )

    fun stop()

    interface Callback {
        fun onOpened()
        fun onFrame(data: ByteArray, width: Int, height: Int, timestampNs: Long)
        fun onClosed()
        fun onError(code: Int, message: String)
    }
}
