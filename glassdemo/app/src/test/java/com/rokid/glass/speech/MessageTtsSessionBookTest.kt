package com.rokid.glass.speech

import org.junit.Assert.*
import org.junit.Test

class MessageTtsSessionBookTest {
    @Test fun stopSnapshotExcludesOtherOwnerOtherEngineAndLaterRequests() {
        val book = MessageTtsSessionBook()
        val first = requireNotNull(book.reserve(1, true))
        val second = requireNotNull(book.reserve(1, true))
        val offline = requireNotNull(book.reserve(1, false))
        val otherPage = requireNotNull(book.reserve(2, true))
        assertEquals(listOf(first, second), book.requestStop(1, true))
        val afterStop = requireNotNull(book.reserve(1, true))
        assertTrue(book.get(first)?.stopRequested == true)
        listOf(offline, otherPage, afterStop).forEach { assertFalse(book.get(it)?.stopRequested == true) }
    }

    @Test fun unknownAndDelayedQueuedDoNotEraseStartedEvidence() {
        val book = MessageTtsSessionBook()
        val ticket = requireNotNull(book.reserve(1, false))
        book.attach(ticket, 1, 7)
        book.observe(ticket, 1)
        assertEquals(1, book.observe(ticket, -1)?.state)
        assertEquals(1, book.observe(ticket, 0)?.state)
        book.observe(ticket, 3)
        assertNull(book.get(ticket))
    }

    @Test fun sameIdOnNewEpochDoesNotMatchOldSession() {
        val book = MessageTtsSessionBook()
        val old = requireNotNull(book.reserve(1, true))
        book.attach(old, 1, 7)
        book.invalidate(true, 1)
        val current = requireNotNull(book.reserve(1, true))
        book.attach(current, 2, 7)
        assertTrue(book.matching(true, 1, 7).isEmpty())
        assertEquals(listOf(current), book.matching(true, 2, 7).map { it.ticket })
    }

    @Test fun pendingRequestsHaveABoundedOwnerAndProcessBudget() {
        val book = MessageTtsSessionBook()
        val tickets = (1..24).map { requireNotNull(book.reserve(1, false)) }
        assertNull(book.reserve(1, false))
        book.remove(tickets.first())
        assertNotNull(book.reserve(1, true))
        (2L..10L).forEach { owner -> repeat(24) { book.reserve(owner, true) } }
        assertEquals(128, book.snapshot().size)
    }
}
