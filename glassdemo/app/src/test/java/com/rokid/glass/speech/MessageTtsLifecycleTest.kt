package com.rokid.glass.speech

import org.junit.Assert.*
import org.junit.Test

class MessageTtsLifecycleTest {
    @Test fun playbackUsesSelectedEngineAndQueueModeAndStopCancelsWholeEngine() {
        val f = Fixture()
        f.lifecycle.start(); f.drain()
        f.lifecycle.speak(true, "online", 1) { true }
        f.lifecycle.speak(false, "offline", 0) { true }
        f.lifecycle.cancelAll(true) { f.events.add("cancel requested") }
        f.drain()
        assertEquals(listOf("online" to 1), f.online.spoken)
        assertEquals(listOf("offline" to 0), f.offline.spoken)
        assertEquals(1, f.online.canceled)
        assertEquals(0, f.offline.canceled)
    }

    @Test fun exitCancelsBothEnginesThenRemovesListenersEvenWhenOneCancelFails() {
        val f = Fixture()
        f.lifecycle.start(); f.drain()
        f.online.failCancel = true
        f.lifecycle.stop(); f.lifecycle.close(); f.lifecycle.close(); f.drain()
        assertEquals(listOf("add", "cancel", "remove"), f.online.calls)
        assertEquals(listOf("add", "cancel", "remove"), f.offline.calls)
        assertEquals(1, f.failures.size)
        assertNull(f.online.listener)
        assertNull(f.offline.listener)
    }

    @Test fun queuedPlaybackDoesNotStartAfterExitAndClosedPageCannotRestart() {
        val f = Fixture()
        f.lifecycle.start(); f.drain()
        f.lifecycle.speak(true, "must not play", 0) { true }
        f.lifecycle.close(); f.lifecycle.start(); f.drain()
        assertTrue(f.online.spoken.isEmpty())
        assertEquals(1, f.online.canceled)
        assertNull(f.online.listener)
    }

    @Test fun staleCallbacksAreIgnoredAfterExitAndAfterReentry() {
        val f = Fixture()
        f.lifecycle.start(); f.drain()
        val old = checkNotNull(f.online.listener)
        old(1, 1, 0)
        assertEquals(1, f.events.size)
        f.lifecycle.stop()
        old(1, 2, 0)
        f.lifecycle.start(); f.drain()
        old(1, 2, 0)
        assertEquals(1, f.events.size)
        f.online.listener!!(2, 1, 0)
        assertEquals(2, f.events.size)
    }

    @Test fun repeatedStartDoesNotDuplicateListenersAndInvalidIdsAreReported() {
        val f = Fixture()
        f.lifecycle.start(); f.lifecycle.start(); f.drain()
        f.offline.sessionId = -1
        f.lifecycle.speak(false, "legacy", 1) { true }; f.drain()
        assertEquals(1, f.online.calls.count { it == "add" })
        assertEquals(listOf(false), f.invalid)
    }

    @Test fun startAndImmediateStopStillCleanBothEnginesWithoutAttachingListeners() {
        val f = Fixture()
        f.lifecycle.start(); f.lifecycle.stop(); f.drain()
        assertEquals(listOf("cancel", "remove"), f.online.calls)
        assertEquals(listOf("cancel", "remove"), f.offline.calls)
    }

    private class FakeEngine : MessageTtsLifecycle.Engine {
        val calls = mutableListOf<String>()
        val spoken = mutableListOf<Pair<String, Int>>()
        var listener: ((Long, Int, Int) -> Unit)? = null
        var canceled = 0
        var failCancel = false
        var sessionId = 1L
        override fun addListener(listener: (Long, Int, Int) -> Unit) {
            calls.add("add"); this.listener = listener
        }
        override fun removeListener() { calls.add("remove"); listener = null }
        override fun speak(text: String, queueMode: Int): Long {
            spoken.add(text to queueMode); return sessionId
        }
        override fun cancelAll() {
            calls.add("cancel"); canceled++
            if (failCancel) throw IllegalStateException("cancel failed")
        }
    }

    private class Fixture {
        val online = FakeEngine()
        val offline = FakeEngine()
        val queue = java.util.ArrayDeque<() -> Unit>()
        val events = mutableListOf<String>()
        val failures = mutableListOf<Exception>()
        val invalid = mutableListOf<Boolean>()
        val lifecycle = MessageTtsLifecycle(online, offline, { queue.add(it) },
            { engine, id, state, error -> events.add("$engine:$id:$state:$error") },
            { failures.add(it) }, { invalid.add(it) })
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst()() }
    }
}
