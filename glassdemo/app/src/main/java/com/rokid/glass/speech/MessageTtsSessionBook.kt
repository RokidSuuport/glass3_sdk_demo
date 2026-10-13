package com.rokid.glass.speech

/** Caller-thread stop snapshots and worker-thread state updates share this small ownership ledger. */
internal class MessageTtsSessionBook {
    data class Ticket(val owner: Long, val online: Boolean, val serial: Long)
    data class Entry(
        val ticket: Ticket,
        val epoch: Long? = null,
        val id: Long? = null,
        val state: Int = UNKNOWN,
        val stopRequested: Boolean = false
    )
    private var serial = 0L
    private val entries = LinkedHashMap<Ticket, Entry>()

    @Synchronized fun reserve(owner: Long, online: Boolean): Ticket? {
        if (entries.size >= 128 || entries.keys.count { it.owner == owner } >= 24) return null
        val ticket = Ticket(owner, online, ++serial)
        entries[ticket] = Entry(ticket)
        return ticket
    }

    /** Later speak clicks are absent from this snapshot and must not be canceled by this stop. */
    @Synchronized fun requestStop(owner: Long, online: Boolean? = null): List<Ticket> {
        val tickets = entries.keys.filter { it.owner == owner && (online == null || it.online == online) }
        tickets.forEach { entries[it] = entries.getValue(it).copy(stopRequested = true) }
        return tickets
    }

    @Synchronized fun get(ticket: Ticket) = entries[ticket]
    @Synchronized fun snapshot() = entries.values.toList()
    @Synchronized fun remove(ticket: Ticket) = entries.remove(ticket)

    @Synchronized fun attach(ticket: Ticket, epoch: Long, id: Long): Entry? {
        require(id > 0L)
        val old = entries[ticket] ?: return null
        check(old.id == null)
        return old.copy(epoch = epoch, id = id).also { entries[ticket] = it }
    }

    @Synchronized fun observe(ticket: Ticket, state: Int): Entry? {
        val old = entries[ticket] ?: return null
        // UNKNOWN is not completion; delayed QUEUED must not regress a known STARTED state.
        val known = if (state == UNKNOWN || old.state == 1 && state == 0) old.state else state
        val updated = old.copy(state = known)
        if (terminal(known)) entries.remove(ticket) else entries[ticket] = updated
        return updated
    }

    @Synchronized fun matching(online: Boolean, epoch: Long, id: Long) = entries.values.filter {
        it.ticket.online == online && it.epoch == epoch && it.id == id
    }

    @Synchronized fun invalidate(online: Boolean, epoch: Long): List<Entry> {
        val invalid = entries.values.filter { it.ticket.online == online && it.epoch == epoch }
        invalid.forEach { entries.remove(it.ticket) }
        return invalid
    }

    companion object {
        const val UNKNOWN = -1
        fun terminal(state: Int) = state in 2..4
    }
}
