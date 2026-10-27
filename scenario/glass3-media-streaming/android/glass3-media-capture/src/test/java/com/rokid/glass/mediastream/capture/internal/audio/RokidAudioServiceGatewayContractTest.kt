package com.rokid.glass.mediastream.capture.internal.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RokidAudioServiceGatewayContractTest {
    @Test
    fun `unavailable or throwing media service rejects start`() {
        val unavailable = RokidAudioServiceGateway(mediaServiceProvider = { null })
        val throwingProvider = RokidAudioServiceGateway(
            mediaServiceProvider = { throw IllegalStateException("SDK unavailable") },
        )
        val callback = collectingCallback()

        assertFalse(unavailable.start(callback))
        assertFalse(throwingProvider.start(callback))

        val throwingService = FakeRokidAudioService().apply {
            startError = IllegalStateException("start failed")
        }
        val throwingStart = RokidAudioServiceGateway(mediaServiceProvider = { throwingService })
        assertFalse(throwingStart.start(callback))
    }

    @Test
    fun `filters null and nonpositive SDK buffers and clamps oversized lengths`() {
        val service = FakeRokidAudioService()
        val received = mutableListOf<ReceivedAudio>()
        val gateway = RokidAudioServiceGateway(
            mediaServiceProvider = { service },
            nanoTime = { 700L },
        )
        val callback = AudioServiceGateway.Callback { buffer, bufferLen, timestampNs ->
            received += ReceivedAudio(buffer, bufferLen, timestampNs)
        }
        assertTrue(gateway.start(callback))
        val sdkCallback = service.startedCallbacks.single()

        sdkCallback.onAudioStream(null, 4)
        sdkCallback.onAudioStream(byteArrayOf(1, 2), 0)
        sdkCallback.onAudioStream(byteArrayOf(1, 2), -1)
        val sdkBuffer = byteArrayOf(1, 2, 3, 4)
        sdkCallback.onAudioStream(sdkBuffer, 99)

        val audio = received.single()
        assertSame(sdkBuffer, audio.buffer)
        assertEquals(4, audio.bufferLen)
        assertEquals(700L, audio.timestampNs)
    }

    @Test
    fun `stop uses the exact callback and service instance captured by start`() {
        val firstService = FakeRokidAudioService()
        val replacementService = FakeRokidAudioService()
        var currentService: RokidAudioService? = firstService
        val gateway = RokidAudioServiceGateway(mediaServiceProvider = { currentService })
        val callback = collectingCallback()
        assertTrue(gateway.start(callback))
        val sdkCallback = firstService.startedCallbacks.single()
        currentService = replacementService

        gateway.stop(callback)
        gateway.stop(callback)

        assertSame(sdkCallback, firstService.stoppedCallbacks.single())
        assertTrue(replacementService.stoppedCallbacks.isEmpty())
    }

    @Test
    fun `each active start owns a distinct SDK callback`() {
        val service = FakeRokidAudioService()
        val gateway = RokidAudioServiceGateway(mediaServiceProvider = { service })
        val first = collectingCallback()
        val second = collectingCallback()

        assertTrue(gateway.start(first))
        assertTrue(gateway.start(second))

        assertEquals(2, service.startedCallbacks.size)
        assertNotSame(service.startedCallbacks[0], service.startedCallbacks[1])
    }

    @Test
    fun `SDK binder death is forwarded only to the matching active callback`() {
        val service = FakeRokidAudioService()
        val disconnects = mutableListOf<Throwable?>()
        val gateway = RokidAudioServiceGateway(mediaServiceProvider = { service })
        val callback = object : AudioServiceGateway.Callback {
            override fun onAudioStream(buffer: ByteArray, bufferLen: Int, timestampNs: Long) = Unit

            override fun onDisconnected(cause: Throwable?) {
                disconnects += cause
            }
        }
        assertTrue(gateway.start(callback))
        val disconnect = IllegalStateException("binder died")

        service.disconnect(disconnect)
        gateway.stop(callback)
        service.disconnect(IllegalStateException("late binder death"))

        assertEquals(listOf(disconnect), disconnects)
    }

    private fun collectingCallback(): AudioServiceGateway.Callback =
        object : AudioServiceGateway.Callback {
            override fun onAudioStream(buffer: ByteArray, bufferLen: Int, timestampNs: Long) = Unit
        }

    private data class ReceivedAudio(
        val buffer: ByteArray,
        val bufferLen: Int,
        val timestampNs: Long,
    )

    private class FakeRokidAudioService : RokidAudioService {
        val startedCallbacks = mutableListOf<RokidAudioCallback>()
        val stoppedCallbacks = mutableListOf<RokidAudioCallback>()
        private val disconnectListeners = mutableMapOf<RokidAudioCallback, (Throwable?) -> Unit>()
        var startError: Throwable? = null

        override fun startAudioRecord(
            callback: RokidAudioCallback,
            onDisconnected: (Throwable?) -> Unit,
        ) {
            startError?.let { throw it }
            startedCallbacks += callback
            disconnectListeners[callback] = onDisconnected
        }

        override fun stopAudioRecord(callback: RokidAudioCallback) {
            stoppedCallbacks += callback
            disconnectListeners.remove(callback)
        }

        fun disconnect(cause: Throwable?) {
            disconnectListeners.values.toList().forEach { listener -> listener(cause) }
        }
    }
}
