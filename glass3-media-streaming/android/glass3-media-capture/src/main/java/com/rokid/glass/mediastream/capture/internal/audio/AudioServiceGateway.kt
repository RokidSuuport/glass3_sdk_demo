package com.rokid.glass.mediastream.capture.internal.audio

internal interface AudioServiceGateway {
    fun start(callback: Callback): Boolean
    fun stop(callback: Callback)

    fun interface Callback {
        fun onAudioStream(buffer: ByteArray, bufferLen: Int, timestampNs: Long)

        fun onDisconnected(cause: Throwable?) = Unit
    }
}
