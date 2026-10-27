package com.rokid.glass.mediastream.transport.signaling

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalingClientTest {
    @Test
    fun incoming_listener_can_wait_for_a_concurrent_send_without_holding_the_signaling_lock() {
        val factory = FakeSignalingSocketFactory()
        val client = SignalingClient(socketFactory = factory)
        val completed = CountDownLatch(1)
        var sentWhileCallbackActive = false
        val listener = object : SignalingClient.Listener {
            override fun onOpen() = Unit
            override fun onClosed(reason: String) = Unit
            override fun onFailure(error: Throwable) = throw AssertionError(error)
            override fun onMessage(message: SignalingMessage) {
                Thread {
                    client.send(SignalingMessage(SignalingType.OFFER, sdp = "offer"))
                    completed.countDown()
                }.apply { isDaemon = true }.start()
                sentWhileCallbackActive = completed.await(1, TimeUnit.SECONDS)
            }
        }
        client.connect("ws://192.168.1.10:8080/ws", "default", listener)
        factory.lastSocket.callback.onOpen()
        factory.lastSocket.callback.onText("{\"type\":\"peer-ready\"}")

        assertTrue("listener must not hold the signaling lock while waiting for publisher work", sentWhileCallbackActive)
        assertTrue(completed.await(1, TimeUnit.SECONDS))
        client.dispose()
    }

    @Test
    fun opening_socket_joins_as_sender_before_application_messages() {
        val factory = FakeSignalingSocketFactory()
        val received = mutableListOf<SignalingMessage>()
        val listener = RecordingListener(received)
        val client = SignalingClient(socketFactory = factory)

        client.connect("ws://192.168.1.10:8080/ws", "room_1", listener)
        assertFalse(client.send(SignalingMessage(SignalingType.OFFER, roomId = "room_1", sdp = "v=0\r\n")))
        factory.lastSocket.callback.onOpen()

        assertEquals(
            SignalingMessage(SignalingType.JOIN, roomId = "room_1", role = "sender"),
            SignalingCodec().decode(factory.lastSocket.sent.single()),
        )
        assertEquals(1, listener.openCount)
        assertTrue(client.send(SignalingMessage(SignalingType.OFFER, roomId = "room_1", sdp = "v=0\r\n")))
        assertEquals(SignalingType.OFFER, SignalingCodec().decode(factory.lastSocket.sent.last()).type)

        factory.lastSocket.callback.onText("{\"type\":\"peer-ready\",\"roomId\":\"room_1\"}")
        assertEquals(SignalingType.PEER_READY, received.single().type)
    }

    @Test
    fun duplicate_open_callback_sends_exactly_one_join() {
        val factory = FakeSignalingSocketFactory()
        val listener = RecordingListener()
        val client = SignalingClient(socketFactory = factory)
        client.connect("ws://192.168.1.10:8080/ws", "default", listener)

        factory.lastSocket.callback.onOpen()
        factory.lastSocket.callback.onOpen()

        assertEquals(1, factory.lastSocket.sent.size)
        assertEquals(1, listener.openCount)
    }

    @Test
    fun synchronous_socket_open_cannot_race_ahead_of_socket_assignment() {
        val listener = RecordingListener()
        val factory = ImmediateOpenSocketFactory()
        val client = SignalingClient(socketFactory = factory)

        client.connect("ws://192.168.1.10:8080/ws", "default", listener)

        assertEquals(1, listener.openCount)
        assertEquals(SignalingType.JOIN, SignalingCodec().decode(factory.socket.sent.single()).type)
    }

    @Test
    fun reconnect_closes_old_socket_and_ignores_every_old_generation_callback() {
        val factory = FakeSignalingSocketFactory()
        val first = RecordingListener()
        val second = RecordingListener()
        val client = SignalingClient(socketFactory = factory)
        client.connect("ws://192.168.1.10:8080/ws", "first", first)
        val oldSocket = factory.lastSocket
        oldSocket.callback.onOpen()

        client.connect("ws://192.168.1.10:8080/ws", "second", second)
        val newSocket = factory.lastSocket
        oldSocket.callback.onText("{\"type\":\"answer\",\"roomId\":\"first\",\"sdp\":\"old\"}")
        oldSocket.callback.onFailure(IOException("old failure"))
        oldSocket.callback.onClosed("old close")
        oldSocket.callback.onOpen()
        newSocket.callback.onOpen()

        assertTrue(oldSocket.closed)
        assertEquals(listOf(SignalingType.JOIN, SignalingType.LEAVE), oldSocket.sent.map { SignalingCodec().decode(it).type })
        assertTrue(first.messages.isEmpty())
        assertTrue(first.failures.isEmpty())
        assertTrue(first.closedReasons.isEmpty())
        assertEquals(1, second.openCount)
        assertEquals(SignalingType.JOIN, SignalingCodec().decode(newSocket.sent.single()).type)
    }

    @Test
    fun reconnect_while_an_old_message_is_being_decoded_drops_that_message() {
        val factory = FakeSignalingSocketFactory()
        val first = RecordingListener()
        val second = RecordingListener()
        val codec = SignalingCodec()
        lateinit var client: SignalingClient
        var reconnectTriggered = false
        val decoder = SignalingMessageDecoder { raw ->
            if (!reconnectTriggered) {
                reconnectTriggered = true
                client.connect("ws://192.168.1.10:8080/ws", "second", second)
            }
            codec.decode(raw)
        }
        client = SignalingClient(socketFactory = factory, decoder = decoder)
        client.connect("ws://192.168.1.10:8080/ws", "first", first)
        val oldSocket = factory.lastSocket
        oldSocket.callback.onOpen()

        oldSocket.callback.onText("{\"type\":\"answer\",\"roomId\":\"first\",\"sdp\":\"old\"}")

        assertEquals(2, factory.sockets.size)
        assertTrue(first.messages.isEmpty())
        assertTrue(first.failures.isEmpty())
        assertTrue(second.messages.isEmpty())
        assertTrue(second.failures.isEmpty())
    }

    @Test
    fun invalid_incoming_message_or_wrong_room_is_failed_without_delivery() {
        val factory = FakeSignalingSocketFactory()
        val listener = RecordingListener()
        val client = SignalingClient(socketFactory = factory)
        client.connect("ws://192.168.1.10:8080/ws", "room_1", listener)
        factory.lastSocket.callback.onOpen()

        factory.lastSocket.callback.onText("not json")
        factory.lastSocket.callback.onText("{\"type\":\"answer\",\"roomId\":\"other\",\"sdp\":\"v=0\"}")
        factory.lastSocket.callback.onText("{\"type\":\"offer\",\"roomId\":\"room_1\",\"sdp\":\"v=0\"}")

        assertEquals(3, listener.failures.size)
        assertTrue(listener.messages.isEmpty())
    }

    @Test
    fun send_rejects_a_message_for_a_different_room() {
        val factory = FakeSignalingSocketFactory()
        val client = SignalingClient(socketFactory = factory)
        client.connect("ws://192.168.1.10:8080/ws", "room_1", RecordingListener())
        factory.lastSocket.callback.onOpen()

        assertThrows(IllegalArgumentException::class.java) {
            client.send(SignalingMessage(SignalingType.OFFER, roomId = "other", sdp = "v=0"))
        }
        assertEquals(1, factory.lastSocket.sent.size)
    }

    @Test
    fun remote_close_or_failure_deactivates_socket_and_notifies_once() {
        val factory = FakeSignalingSocketFactory()
        val listener = RecordingListener()
        val client = SignalingClient(socketFactory = factory)
        client.connect("ws://192.168.1.10:8080/ws", "default", listener)
        val socket = factory.lastSocket
        socket.callback.onOpen()

        socket.callback.onClosed("receiver stopped")
        socket.callback.onFailure(IOException("late"))

        assertEquals(listOf("receiver stopped"), listener.closedReasons)
        assertTrue(listener.failures.isEmpty())
        assertFalse(client.send(SignalingMessage(SignalingType.OFFER, sdp = "v=0")))
    }

    @Test
    fun socket_creation_failure_is_reported_and_next_connect_can_retry() {
        val firstError = IOException("connection refused")
        val factory = FailingOnceSocketFactory(firstError)
        val first = RecordingListener()
        val second = RecordingListener()
        val client = SignalingClient(socketFactory = factory)

        client.connect("ws://192.168.1.10:8080/ws", "default", first)
        client.connect("ws://192.168.1.10:8080/ws", "default", second)
        factory.socket.callback.onOpen()

        assertSame(firstError, first.failures.single())
        assertEquals(1, second.openCount)
    }

    @Test
    fun failed_join_is_reported_closed_and_never_marked_open() {
        val factory = RejectingSendSocketFactory()
        val listener = RecordingListener()
        val client = SignalingClient(socketFactory = factory)
        client.connect("ws://192.168.1.10:8080/ws", "default", listener)

        factory.socket.callback.onOpen()

        assertEquals(0, listener.openCount)
        assertEquals(1, listener.failures.size)
        assertTrue(factory.socket.closed)
    }

    @Test
    fun close_and_dispose_are_idempotent_and_late_callbacks_are_ignored() {
        val factory = FakeSignalingSocketFactory()
        val listener = RecordingListener()
        val client = SignalingClient(socketFactory = factory)
        client.connect("ws://192.168.1.10:8080/ws", "default", listener)
        val socket = factory.lastSocket
        socket.callback.onOpen()

        client.close()
        client.close()
        client.dispose()
        client.dispose()
        socket.callback.onText("{\"type\":\"peer-ready\",\"roomId\":\"default\"}")
        socket.callback.onFailure(IOException("late"))

        assertEquals(listOf(SignalingType.JOIN, SignalingType.LEAVE), socket.sent.map { SignalingCodec().decode(it).type })
        assertEquals(1, socket.closeCount)
        assertEquals(1, factory.closeCount)
        assertTrue(listener.messages.isEmpty())
        assertTrue(listener.failures.isEmpty())
        assertThrows(IllegalStateException::class.java) {
            client.connect("ws://192.168.1.10:8080/ws", "default", RecordingListener())
        }
    }

    private class RecordingListener(
        val messages: MutableList<SignalingMessage> = mutableListOf(),
    ) : SignalingClient.Listener {
        var openCount = 0
        val failures = mutableListOf<Throwable>()
        val closedReasons = mutableListOf<String>()

        override fun onOpen() {
            openCount += 1
        }

        override fun onMessage(message: SignalingMessage) {
            messages += message
        }

        override fun onClosed(reason: String) {
            closedReasons += reason
        }

        override fun onFailure(error: Throwable) {
            failures += error
        }
    }

    private class FakeSignalingSocketFactory : SignalingSocketFactory {
        val sockets = mutableListOf<FakeSignalingSocket>()
        val lastSocket: FakeSignalingSocket get() = sockets.last()
        var closeCount = 0

        override fun open(url: String, callback: SignalingSocketCallback): SignalingSocket =
            FakeSignalingSocket(callback).also(sockets::add)

        override fun close() {
            closeCount += 1
        }
    }

    private open class FakeSignalingSocket(
        val callback: SignalingSocketCallback,
    ) : SignalingSocket {
        val sent = mutableListOf<String>()
        var closed = false
        var closeCount = 0

        override fun send(text: String): Boolean {
            sent += text
            return !closed
        }

        override fun close(code: Int, reason: String): Boolean {
            closeCount += 1
            closed = true
            return true
        }

    }

    private class ImmediateOpenSocketFactory : SignalingSocketFactory {
        lateinit var socket: FakeSignalingSocket

        override fun open(url: String, callback: SignalingSocketCallback): SignalingSocket {
            socket = FakeSignalingSocket(callback)
            callback.onOpen()
            return socket
        }
    }

    private class FailingOnceSocketFactory(
        private val error: Throwable,
    ) : SignalingSocketFactory {
        var attempts = 0
        lateinit var socket: FakeSignalingSocket

        override fun open(url: String, callback: SignalingSocketCallback): SignalingSocket {
            attempts += 1
            if (attempts == 1) throw error
            return FakeSignalingSocket(callback).also { socket = it }
        }
    }

    private class RejectingSendSocketFactory : SignalingSocketFactory {
        lateinit var socket: FakeSignalingSocket

        override fun open(url: String, callback: SignalingSocketCallback): SignalingSocket =
            object : FakeSignalingSocket(callback) {
                override fun send(text: String): Boolean {
                    sent += text
                    return false
                }
            }.also { socket = it }
    }
}
