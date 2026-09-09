package com.rokid.glass.mediastream.capture.internal.sdk

import com.rokid.glass.mediastream.capture.MediaErrorCode
import com.rokid.glass.mediastream.capture.MediaFailure
import com.rokid.security.glass3.open.sdk.client.IServiceConnectionCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassSdkConnectionTest {
    @Test
    fun `already ready SDK reports ready once without taking ownership of a bind`() {
        val gateway = FakeGateway(ready = true)
        val events = mutableListOf<String>()
        val connection = GlassSdkConnection(gateway)

        connection.bind(listener(events))
        connection.unbind()

        assertEquals(listOf("ready"), events)
        assertEquals(0, gateway.bindCount)
        assertEquals(0, gateway.unbindCount)
    }

    @Test
    fun `disconnect before readiness maps to SDK not ready and terminates that generation`() {
        val gateway = FakeGateway(ready = false)
        val failures = mutableListOf<MediaFailure>()
        val events = mutableListOf<String>()
        val connection = GlassSdkConnection(gateway)
        connection.bind(listener(events, failures))

        gateway.callback(0).onServiceDisconnected()
        gateway.callback(0).onServiceDisconnected()
        gateway.callback(0).onServiceConnected()

        assertTrue(events.isEmpty())
        assertEquals(MediaErrorCode.SDK_NOT_READY, failures.single().code)
    }

    @Test
    fun `disconnect after readiness maps to SDK disconnected and duplicate callbacks are ignored`() {
        val gateway = FakeGateway(ready = false)
        val failures = mutableListOf<MediaFailure>()
        val events = mutableListOf<String>()
        val connection = GlassSdkConnection(gateway)
        connection.bind(listener(events, failures))

        gateway.callback(0).onServiceConnected()
        gateway.callback(0).onServiceConnected()
        gateway.callback(0).onBindingDied()
        gateway.callback(0).onServiceDisconnected()

        assertEquals(listOf("ready"), events)
        assertEquals(MediaErrorCode.SDK_DISCONNECTED, failures.single().code)
    }

    @Test
    fun `callbacks from an unbound generation stay obsolete after rebind`() {
        val gateway = FakeGateway(ready = false)
        val firstEvents = mutableListOf<String>()
        val firstFailures = mutableListOf<MediaFailure>()
        val secondEvents = mutableListOf<String>()
        val secondFailures = mutableListOf<MediaFailure>()
        val connection = GlassSdkConnection(gateway)
        connection.bind(listener(firstEvents, firstFailures))
        connection.unbind()
        connection.bind(listener(secondEvents, secondFailures))

        gateway.callback(0).onServiceConnected()
        gateway.callback(0).onBindingDied()
        gateway.callback(1).onServiceConnected()

        assertTrue(firstEvents.isEmpty())
        assertTrue(firstFailures.isEmpty())
        assertEquals(listOf("ready"), secondEvents)
        assertTrue(secondFailures.isEmpty())
        assertEquals(2, gateway.bindCount)
        assertEquals(1, gateway.unbindCount)
    }

    @Test
    fun `binding exception reports SDK not ready with the original cause and remains unbindable`() {
        val bindError = IllegalStateException("security service bind failed")
        val gateway = FakeGateway(ready = false).apply { this.bindError = bindError }
        val failures = mutableListOf<MediaFailure>()
        val connection = GlassSdkConnection(gateway)

        connection.bind(listener(mutableListOf(), failures))
        connection.unbind()
        connection.unbind()

        assertEquals(MediaErrorCode.SDK_NOT_READY, failures.single().code)
        assertSame(bindError, failures.single().cause)
        assertEquals(1, gateway.unbindCount)
    }

    private fun listener(
        events: MutableList<String>,
        failures: MutableList<MediaFailure> = mutableListOf(),
    ) = object : SdkConnection.Listener {
        override fun onReady() {
            events += "ready"
        }

        override fun onFailure(failure: MediaFailure) {
            failures += failure
        }
    }

    private class FakeGateway(var ready: Boolean) : GlassSdkGateway {
        var bindCount = 0
        var unbindCount = 0
        var bindError: Throwable? = null
        val callbacks = mutableListOf<IServiceConnectionCallback>()

        override fun isReady(): Boolean = ready

        override fun bind(callback: IServiceConnectionCallback) {
            bindCount += 1
            callbacks += callback
            bindError?.let { throw it }
        }

        override fun unbind() {
            unbindCount += 1
        }

        fun callback(index: Int): IServiceConnectionCallback = callbacks[index]
    }
}
