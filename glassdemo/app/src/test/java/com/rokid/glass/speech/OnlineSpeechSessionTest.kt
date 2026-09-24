package com.rokid.glass.speech

import org.junit.Assert.*
import org.junit.Test

class OnlineSpeechSessionTest {
    @Test fun duplicateStartIsRejected() {
        val s = OnlineSpeechSession()
        val token = s.begin()
        assertTrue(token > 0)
        assertEquals(0L, s.begin())
        assertTrue(s.started(token))
        assertFalse(s.starting)
    }
    @Test fun stoppedCallbacksCannotAffectNextSession() {
        val s = OnlineSpeechSession()
        val old = s.begin()
        assertTrue(s.cancel { true })
        val next = s.begin()
        assertFalse(s.started(old))
        assertFalse(s.complete(old))
        assertTrue(s.starting)
        assertTrue(s.started(next))
    }
    @Test fun failedCancelBlocksStartAndLateCompletionUntilRetry() {
        val s = OnlineSpeechSession()
        val old = s.begin()
        assertFalse(s.cancel { false })
        assertFalse(s.accepts(old))
        assertFalse(s.complete(old))
        assertEquals(old, s.id)
        assertEquals(0L, s.begin())
        assertTrue(s.cancel { true })
        assertTrue(s.begin() > old)
    }
    @Test fun idleCleanupNeverCallsSdk() {
        assertTrue(OnlineSpeechSession().cancel { error("idle") })
    }
    @Test fun clicksAreBoundedAndDispatchedSerially() {
        val s = OnlineSpeechSession()
        repeat(10) { assertTrue(s.enqueue()) }
        assertFalse(s.enqueue())
        val token = s.beginNext()
        assertTrue(token > 0)
        assertEquals(9, s.waiting)
        assertEquals(0L, s.beginNext())
        assertFalse(s.enqueue())
        assertTrue(s.complete(token))
        assertTrue(s.beginNext() > token)
        assertEquals(8, s.waiting)
    }
    @Test fun cancelFailureCannotAdvanceQueue() {
        val s = OnlineSpeechSession()
        repeat(3) { s.enqueue() }
        val old = s.beginNext()
        assertFalse(s.cancel { false })
        assertEquals(0L, s.beginNext())
        assertEquals(2, s.waiting)
        assertTrue(s.cancel { true })
        assertTrue(s.beginNext() > old)
        assertFalse(s.complete(old))
    }
    @Test fun leavingClearsQueueAndCancelsActive() {
        val s = OnlineSpeechSession()
        repeat(3) { s.enqueue() }
        s.beginNext()
        assertEquals(2, s.clearQueue())
        assertTrue(s.cancel { true })
        assertEquals(0L, s.beginNext())
        assertEquals(0, s.waiting)
    }
}
