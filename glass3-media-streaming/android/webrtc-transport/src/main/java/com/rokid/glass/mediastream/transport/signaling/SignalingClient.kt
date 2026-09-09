package com.rokid.glass.mediastream.transport.signaling

import java.io.Closeable
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

interface SignalingSocket {
    fun send(text: String): Boolean
    fun close(code: Int, reason: String): Boolean
}

interface SignalingSocketCallback {
    fun onOpen()
    fun onText(text: String)
    fun onClosed(reason: String)
    fun onFailure(error: Throwable)
}

interface SignalingSocketFactory : Closeable {
    fun open(url: String, callback: SignalingSocketCallback): SignalingSocket
    override fun close() = Unit
}

internal fun interface SignalingMessageDecoder {
    fun decode(raw: String): SignalingMessage
}

class SignalingClient internal constructor(
    private val socketFactory: SignalingSocketFactory,
    private val codec: SignalingCodec = SignalingCodec(),
    private val decoder: SignalingMessageDecoder = SignalingMessageDecoder(codec::decode),
) : Closeable {
    constructor() : this(OkHttpSignalingSocketFactory())

    interface Listener {
        fun onOpen()
        fun onMessage(message: SignalingMessage)
        fun onClosed(reason: String)
        fun onFailure(error: Throwable)
    }

    private val lock = Any()
    private var socket: SignalingSocket? = null
    private var roomId = SignalingMessage.DEFAULT_ROOM_ID
    private var listener: Listener? = null
    private var generation = 0L
    private var pendingOpenGeneration: Long? = null
    private var joinedGeneration: Long? = null
    private var disposed = false

    fun connect(url: String, roomId: String, listener: Listener) {
        val normalizedUrl = validateSignalingUrl(url)
        codec.encode(SignalingMessage(SignalingType.JOIN, roomId = roomId, role = SENDER_ROLE))
        synchronized(lock) {
            check(!disposed) { "SignalingClient is disposed" }
        }
        closeSocket(notifyRemote = true)

        val callbackGeneration: Long
        synchronized(lock) {
            check(!disposed) { "SignalingClient is disposed" }
            generation += 1
            callbackGeneration = generation
            this.roomId = roomId
            this.listener = listener
        }

        val callback = createSocketCallback(callbackGeneration)
        val openedSocket = try {
            socketFactory.open(normalizedUrl, callback)
        } catch (error: Throwable) {
            failGeneration(callbackGeneration, error)
            return
        }

        val completePendingOpen = synchronized(lock) {
            if (generation == callbackGeneration && !disposed) {
                socket = openedSocket
                (pendingOpenGeneration == callbackGeneration).also { pending ->
                    if (pending) pendingOpenGeneration = null
                }
            } else {
                false
            }
        }
        if (completePendingOpen) {
            completeOpen(callbackGeneration)
        } else if (synchronized(lock) { generation != callbackGeneration || disposed }) {
            openedSocket.close(NORMAL_CLOSURE, "superseded")
        }
    }

    fun send(message: SignalingMessage): Boolean {
        val encoded = codec.encode(message)
        val activeSocket = synchronized(lock) {
            val activeRoom = roomId
            require(message.roomId == activeRoom) {
                "Signaling message room does not match the active room"
            }
            socket.takeIf {
                !disposed && joinedGeneration == generation
            }
        } ?: return false
        return activeSocket.send(encoded)
    }

    override fun close() {
        closeSocket(notifyRemote = true)
    }

    fun dispose() {
        val shouldDispose = synchronized(lock) {
            if (disposed) {
                false
            } else {
                disposed = true
                true
            }
        }
        if (!shouldDispose) return
        closeSocket(notifyRemote = true)
        socketFactory.close()
    }

    private fun createSocketCallback(callbackGeneration: Long): SignalingSocketCallback =
        object : SignalingSocketCallback {
            override fun onOpen() {
                completeOpen(callbackGeneration)
            }

            override fun onText(text: String) {
                val current = synchronized(lock) {
                    val activeListener = listener
                    val activeSocket = socket
                    if (
                        generation == callbackGeneration &&
                        joinedGeneration == callbackGeneration &&
                        activeListener != null &&
                        activeSocket != null
                    ) {
                        Triple(activeListener, roomId, activeSocket)
                    } else {
                        null
                    }
                } ?: return
                try {
                    val message = decoder.decode(text)
                    require(message.type in ALLOWED_INCOMING_TYPES) {
                        "Unexpected signaling message for sender: " + message.type.wireValue
                    }
                    if (message.type in ROOM_SCOPED_INCOMING_TYPES) {
                        require(message.roomId == current.second) {
                            "Signaling message room does not match the active room"
                        }
                    }
                    synchronized(lock) {
                        if (
                            generation == callbackGeneration &&
                            joinedGeneration == callbackGeneration &&
                            listener === current.first &&
                            socket === current.third
                        ) {
                            current.first.onMessage(message)
                        }
                    }
                } catch (error: Throwable) {
                    synchronized(lock) {
                        if (
                            generation == callbackGeneration &&
                            joinedGeneration == callbackGeneration &&
                            listener === current.first &&
                            socket === current.third
                        ) {
                            current.first.onFailure(error)
                        }
                    }
                }
            }

            override fun onClosed(reason: String) {
                closeGeneration(callbackGeneration, reason.ifBlank { "WebSocket closed" })
            }

            override fun onFailure(error: Throwable) {
                failGeneration(callbackGeneration, error)
            }
        }

    private fun completeOpen(expectedGeneration: Long) {
        val current = synchronized(lock) {
            if (
                disposed ||
                generation != expectedGeneration ||
                joinedGeneration == expectedGeneration
            ) {
                return@synchronized null
            }
            val activeSocket = socket
            if (activeSocket == null) {
                pendingOpenGeneration = expectedGeneration
                return@synchronized null
            }
            val activeListener = listener ?: return@synchronized null
            Triple(activeSocket, roomId, activeListener)
        } ?: return

        val joined = current.first.send(
            codec.encode(
                SignalingMessage(
                    type = SignalingType.JOIN,
                    roomId = current.second,
                    role = SENDER_ROLE,
                ),
            ),
        )
        if (!joined) {
            current.first.close(NORMAL_CLOSURE, "join failed")
            failGeneration(expectedGeneration, IllegalStateException("Failed to send join message"))
            return
        }
        synchronized(lock) {
            if (generation == expectedGeneration && socket === current.first && !disposed) {
                joinedGeneration = expectedGeneration
                current.third.onOpen()
            }
        }
    }

    private fun closeGeneration(expectedGeneration: Long, reason: String) {
        val currentListener = synchronized(lock) {
            if (generation != expectedGeneration) return
            val active = listener
            generation += 1
            socket = null
            listener = null
            pendingOpenGeneration = null
            joinedGeneration = null
            active
        }
        currentListener?.onClosed(reason)
    }

    private fun failGeneration(expectedGeneration: Long, error: Throwable) {
        val currentListener = synchronized(lock) {
            if (generation != expectedGeneration) return
            val active = listener
            generation += 1
            socket = null
            listener = null
            pendingOpenGeneration = null
            joinedGeneration = null
            active
        }
        currentListener?.onFailure(error)
    }

    private fun closeSocket(notifyRemote: Boolean) {
        val closingSocket: SignalingSocket?
        val closingRoomId: String
        val wasJoined: Boolean
        synchronized(lock) {
            val closingGeneration = generation
            generation += 1
            closingSocket = socket
            closingRoomId = roomId
            wasJoined = joinedGeneration == closingGeneration
            socket = null
            listener = null
            pendingOpenGeneration = null
            joinedGeneration = null
        }
        if (notifyRemote && wasJoined && closingSocket != null) {
            closingSocket.send(
                codec.encode(SignalingMessage(SignalingType.LEAVE, roomId = closingRoomId)),
            )
        }
        closingSocket?.close(NORMAL_CLOSURE, "client stop")
    }

    companion object {
        private const val NORMAL_CLOSURE = 1000
        private const val SENDER_ROLE = "sender"
        private val ALLOWED_INCOMING_TYPES = setOf(
            SignalingType.PEER_READY,
            SignalingType.ANSWER,
            SignalingType.ICE_CANDIDATE,
            SignalingType.LEAVE,
            SignalingType.ERROR,
        )
        private val ROOM_SCOPED_INCOMING_TYPES = setOf(
            SignalingType.PEER_READY,
            SignalingType.ANSWER,
            SignalingType.ICE_CANDIDATE,
        )
    }
}

private class OkHttpSignalingSocketFactory : SignalingSocketFactory {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    override fun open(url: String, callback: SignalingSocketCallback): SignalingSocket {
        val request = Request.Builder().url(url).build()
        val webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = callback.onOpen()

                override fun onMessage(webSocket: WebSocket, text: String) = callback.onText(text)

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                    callback.onClosed(reason)

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    callback.onFailure(t)
            },
        )
        return OkHttpSignalingSocket(webSocket)
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}

private class OkHttpSignalingSocket(
    private val delegate: WebSocket,
) : SignalingSocket {
    override fun send(text: String): Boolean = delegate.send(text)
    override fun close(code: Int, reason: String): Boolean {
        // A graceful OkHttp close can remain queued while this short-lived client is disposed.
        // Force the transport down as well so the room registry always observes disconnect and
        // releases the sender slot before a bounded retry reconnects.
        val accepted = delegate.close(code, reason)
        delegate.cancel()
        return accepted
    }
}
