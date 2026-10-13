package com.rokid.glass.speech

import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A backend is one exact Binder generation, never a dynamic service getter. */
internal interface MessageTtsEndpoint {
    val identity: Any
    fun version(): Int
    fun isAlive(): Boolean
    fun isDead(error: Throwable): Boolean
    fun watchDeath(onDeath: () -> Unit): () -> Unit
    fun register(onState: (Long, Int, Int) -> Unit): () -> Unit
    fun speak(text: String, queueMode: Int): Long
    fun cancel(id: Long): Boolean
    fun query(id: Long): Int
}

internal interface MessageTtsQueue {
    fun execute(task: () -> Unit)
    fun schedule(delayMillis: Long, task: () -> Unit): () -> Unit
}

/**
 * One process-wide listener per engine/Binder, shared by all message screens. All backend access is
 * serialized, including registration, death, late callbacks and state queries. The SDK Managers are
 * deliberately not mixed in: the service has one listener slot per PID and Managers may silently
 * swallow registration failures or retain a stale registered flag after Binder replacement.
 */
internal class MessageTtsBridge(
    private val provider: (Boolean) -> MessageTtsEndpoint?,
    private val queue: MessageTtsQueue,
    private val diagnostic: (Event) -> Unit = {}
) {
    enum class Kind { ACCEPTED, STATE, QUERY, CANCEL, API_REQUIRED, LISTENER_FAILED, INVALID_SESSION,
        INVALIDATED, PENDING_CANCELED, NO_SESSIONS, LIMIT, FAILURE }
    data class Event(
        val owner: Long, val online: Boolean, val kind: Kind, val id: Long = -1L,
        val state: Int = -1, val observed: Int = -1, val error: Int = 0,
        val accepted: Boolean = false, val detail: String = ""
    )
    private class Connection(val online: Boolean, val epoch: Long, val backend: MessageTtsEndpoint, val version: Int) {
        var unwatch: (() -> Unit)? = null
        var unregister: (() -> Unit)? = null
    }
    private val ownerIds = AtomicLong()
    private val owners = ConcurrentHashMap<Long, WeakReference<(Event) -> Unit>>()
    private val book = MessageTtsSessionBook()
    private val connections = mutableMapOf<Boolean, Connection>()
    private val lastQueries = mutableMapOf<MessageTtsSessionBook.Ticket, Int>()
    private var epoch = 0L
    private var scheduledPoll: (() -> Unit)? = null

    fun openOwner(report: (Event) -> Unit): Long = ownerIds.incrementAndGet().also {
        owners[it] = WeakReference(report)
    }

    fun speak(owner: Long, online: Boolean, text: String, queueMode: Int, guard: () -> Boolean = { true }) {
        if (!owners.containsKey(owner)) return
        val ticket = book.reserve(owner, online)
        if (ticket == null) {
            queue.execute { emit(Event(owner, online, Kind.LIMIT)) }
            return
        }
        queue.execute {
            try {
                if (book.get(ticket)?.stopRequested == true) {
                    book.remove(ticket)
                    emit(Event(owner, online, Kind.PENDING_CANCELED))
                    return@execute
                }
                if (!guard()) { book.remove(ticket); return@execute }
                val connection = acquire(owner, online) ?: run { book.remove(ticket); return@execute }
                // Stop can arrive while registration is blocked, before any audio was submitted.
                if (book.get(ticket)?.stopRequested == true) {
                    book.remove(ticket)
                    emit(Event(owner, online, Kind.PENDING_CANCELED))
                    return@execute
                }
                val id = connection.backend.speak(text, queueMode)
                if (id <= 0L) {
                    book.remove(ticket)
                    emit(Event(owner, online, Kind.INVALID_SESSION))
                    return@execute
                }
                val entry = checkNotNull(book.attach(ticket, connection.epoch, id))
                emit(Event(owner, online, Kind.ACCEPTED, id,
                    detail = "queueMode=$queueMode apiVersion=${connection.version}"))
                // A death callback may be queued behind this call. Check actual Binder liveness now.
                if (!connection.backend.isAlive()) invalidate(connection, "binder_died")
                else query(entry, connection)
            } catch (error: Exception) {
                val connection = connections[online]
                if (connection != null && connection.backend.isDead(error)) invalidate(connection, "binder_died")
                book.remove(ticket)
                emit(Event(owner, online, Kind.FAILURE, detail = error.javaClass.simpleName))
            } finally { schedulePoll() }
        }
    }

    fun cancelOwned(owner: Long, online: Boolean? = null, reportEmpty: Boolean = true) {
        val tickets = book.requestStop(owner, online)
        queue.execute {
            if (tickets.isEmpty() && reportEmpty && online != null) emit(Event(owner, online, Kind.NO_SESSIONS))
            tickets.forEach { ticket ->
                val entry = book.get(ticket) ?: return@forEach
                if (entry.id == null) return@forEach // Its already-queued submit will observe stopRequested.
                refresh(entry.ticket.online)
                val connection = connectionFor(entry) ?: return@forEach
                val accepted = try {
                    connection.backend.cancel(entry.id)
                } catch (error: Exception) {
                    if (connection.backend.isDead(error)) invalidate(connection, "binder_died")
                    else emit(Event(owner, entry.ticket.online, Kind.FAILURE, entry.id, detail = error.javaClass.simpleName))
                    false
                }
                emit(Event(owner, entry.ticket.online, Kind.CANCEL, entry.id, accepted = accepted))
                if (book.get(ticket) != null) query(entry, connection)
            }
            schedulePoll()
        }
    }

    fun reconcile(owner: Long) {
        queue.execute {
            book.snapshot().filter { it.ticket.owner == owner }.forEach { entry ->
                refresh(entry.ticket.online)
                connectionFor(entry)?.let { query(entry, it) }
            }
            schedulePoll()
        }
    }

    fun closeOwner(owner: Long) {
        cancelOwned(owner, reportEmpty = false)
        queue.execute { owners.remove(owner) }
        // Do not unregister the process listener on Activity destruction. Offline unregister ignores
        // listener identity and clears the PID's slot, which would also break another live screen.
    }

    private fun acquire(owner: Long, online: Boolean): Connection? {
        val candidate = refresh(online)
        if (candidate == null) { emit(Event(owner, online, Kind.API_REQUIRED)); return null }
        connections[online]?.let { return it }
        val version = runCatching { candidate.version() }.getOrDefault(-1)
        if (version < 2 || !candidate.isAlive()) {
            emit(Event(owner, online, Kind.API_REQUIRED, detail = "apiVersion=$version"))
            return null
        }
        val connection = Connection(online, ++epoch, candidate, version)
        try {
            connection.unwatch = candidate.watchDeath {
                queue.execute { invalidate(connection, "binder_died"); schedulePoll() }
            }
            connection.unregister = candidate.register { id, state, error ->
                // Every listener captures its own connection, never the current engine generation.
                queue.execute { callback(connection, id, state, error) }
            }
            check(candidate.isAlive()) { "binder_died_during_registration" }
            connections[online] = connection
            return connection
        } catch (error: Exception) {
            runCatching { connection.unregister?.invoke() }
            runCatching { connection.unwatch?.invoke() }
            emit(Event(owner, online, Kind.LISTENER_FAILED, detail = error.javaClass.simpleName))
            return null
        }
    }

    /** A temporarily null SDK getter is not proof of death. A different non-null Binder is. */
    private fun refresh(online: Boolean): MessageTtsEndpoint? {
        val candidate = runCatching { provider(online) }.getOrNull()
        val old = connections[online]
        if (old != null) {
            when {
                !old.backend.isAlive() -> invalidate(old, "binder_died")
                candidate != null && candidate.identity != old.backend.identity -> invalidate(old, "service_replaced")
            }
        }
        return candidate
    }

    private fun connectionFor(entry: MessageTtsSessionBook.Entry): Connection? = connections[entry.ticket.online]
        ?.takeIf { it.epoch == entry.epoch && book.get(entry.ticket) != null }

    private fun callback(connection: Connection, id: Long, state: Int, error: Int) {
        if (connections[connection.online] !== connection) return
        book.matching(connection.online, connection.epoch, id).forEach { entry ->
            val updated = book.observe(entry.ticket, state) ?: return@forEach
            emit(Event(entry.ticket.owner, entry.ticket.online, Kind.STATE, id, updated.state, state, error))
            if (MessageTtsSessionBook.terminal(updated.state)) lastQueries.remove(entry.ticket)
        }
        schedulePoll()
    }

    private fun query(entry: MessageTtsSessionBook.Entry, connection: Connection) {
        val id = entry.id ?: return
        if (connectionFor(entry) !== connection) return
        val observed = try { connection.backend.query(id) } catch (error: Exception) {
            if (connection.backend.isDead(error)) { invalidate(connection, "binder_died"); return }
            MessageTtsSessionBook.UNKNOWN
        }
        val updated = book.observe(entry.ticket, observed) ?: return
        if (lastQueries.put(entry.ticket, observed) != observed) {
            emit(Event(entry.ticket.owner, entry.ticket.online, Kind.QUERY, id, updated.state, observed))
        }
        if (MessageTtsSessionBook.terminal(updated.state)) lastQueries.remove(entry.ticket)
    }

    private fun invalidate(connection: Connection, reason: String) {
        if (connections[connection.online] !== connection) return
        connections.remove(connection.online)
        book.invalidate(connection.online, connection.epoch).forEach { entry ->
            lastQueries.remove(entry.ticket)
            emit(Event(entry.ticket.owner, entry.ticket.online, Kind.INVALIDATED, entry.id ?: -1L, detail = reason))
        }
        // This cleanup only addresses the retired Binder. It must never unregister a newer Binder.
        runCatching { connection.unregister?.invoke() }
        runCatching { connection.unwatch?.invoke() }
    }

    private fun schedulePoll() {
        if (book.snapshot().isEmpty()) { scheduledPoll?.invoke(); scheduledPoll = null; return }
        if (scheduledPoll != null) return
        scheduledPoll = queue.schedule(1_000L) {
            scheduledPoll = null
            listOf(true, false).forEach { refresh(it) }
            book.snapshot().forEach { entry -> connectionFor(entry)?.let { query(entry, it) } }
            schedulePoll()
        }
    }

    private fun emit(event: Event) {
        // A detached/broken UI must not turn an accepted remote request into lost ownership.
        runCatching { diagnostic(event) }
        runCatching { owners[event.owner]?.get()?.invoke(event) }
    }
}
