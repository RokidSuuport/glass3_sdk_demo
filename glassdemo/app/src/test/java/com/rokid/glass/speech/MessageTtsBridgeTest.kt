package com.rokid.glass.speech

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class MessageTtsBridgeTest {
    @Test fun registrationFailurePreventsPlaybackAndCanBeRetried() {
        val f = Fixture()
        f.online.failRegistration = true
        f.speak(true)
        f.queue.drain()
        assertTrue(f.online.spoken.isEmpty())
        assertTrue(f.events.any { it.kind == MessageTtsBridge.Kind.LISTENER_FAILED })
        f.online.failRegistration = false
        f.speak(true)
        f.queue.drain()
        assertEquals(2, f.online.registrations)
        assertEquals(1, f.online.spoken.size)
        assertEquals(listOf("register", "register", "speak"), f.online.operations.take(3))
    }

    @Test fun legacyVersionIsRejectedWithoutRegistrationOrSpeech() {
        val f = Fixture()
        f.offline.apiVersion = 1
        f.speak(false)
        f.queue.drain()
        assertEquals(0, f.offline.registrations)
        assertTrue(f.offline.spoken.isEmpty())
        assertTrue(f.events.any { it.kind == MessageTtsBridge.Kind.API_REQUIRED })
    }

    @Test fun everySpeakClickSubmitsItsSelectedQueueMode() {
        val f = Fixture()
        f.bridge.speak(f.owner, true, "first", 1)
        f.bridge.speak(f.owner, true, "second", 0)
        f.queue.drain()
        assertEquals(listOf("first" to 1, "second" to 0), f.online.spoken)
        assertEquals(1, f.online.registrations)
    }

    @Test fun stopCancelsOnlyRequestsExistingAtClickTimeIncludingPendingOnes() {
        val f = Fixture()
        f.speak(true)
        f.speak(true)
        f.bridge.cancelOwned(f.owner, true)
        f.bridge.speak(f.owner, true, "after stop", 1)
        f.queue.drain()
        assertEquals(listOf("after stop" to 1), f.online.spoken)
        assertTrue(f.online.canceled.isEmpty())
        assertEquals(2, f.events.count { it.kind == MessageTtsBridge.Kind.PENDING_CANCELED })
    }

    @Test fun stopWhileSpeakIsReturningCancelsTheLateIdAndNotTheNextRequest() {
        val f = Fixture()
        f.online.onSpeak = {
            f.bridge.cancelOwned(f.owner, true)
            f.bridge.speak(f.owner, true, "next", 1)
            f.online.onSpeak = null
        }
        f.speak(true)
        f.queue.drain()
        assertEquals(listOf(1L), f.online.canceled)
        assertEquals(2, f.online.spoken.size)
    }

    @Test fun cancelAcceptedAndUnknownWaitForAnAsynchronousTerminalCallback() {
        val f = Fixture()
        f.offline.queryState = -1
        f.speak(false)
        f.queue.drain()
        f.bridge.cancelOwned(f.owner, false)
        f.queue.drain()
        assertTrue(f.events.any { it.kind == MessageTtsBridge.Kind.CANCEL && it.accepted })
        assertFalse(f.events.any { it.kind == MessageTtsBridge.Kind.STATE && it.state in 2..4 })
        f.offline.listener!!(1, 3, 0)
        f.queue.drain()
        assertTrue(f.events.any { it.kind == MessageTtsBridge.Kind.STATE && it.state == 3 })
        f.bridge.cancelOwned(f.owner, false)
        f.queue.drain()
        assertEquals(listOf(1L), f.offline.canceled)
        assertEquals(MessageTtsBridge.Kind.NO_SESSIONS, f.events.last().kind)
    }

    @Test fun rejectedCancellationAndUnknownAllowRetryOfTheSameSession() {
        val f = Fixture()
        f.online.cancelAccepted = false
        f.online.queryState = -1
        f.speak(true)
        f.queue.drain()
        repeat(2) { f.bridge.cancelOwned(f.owner, true); f.queue.drain() }
        assertEquals(listOf(1L, 1L), f.online.canceled)
        assertFalse(f.events.any { it.kind == MessageTtsBridge.Kind.INVALIDATED })
    }

    @Test fun synchronousTerminalBeforeSpeakReturnsIsNotLostToAnUnknownQuery() {
        val f = Fixture()
        f.offline.queryState = -1
        f.offline.onSpeak = { id -> f.offline.listener!!(id, 2, 0) }
        f.speak(false)
        f.queue.drain()
        assertTrue(f.events.any { it.kind == MessageTtsBridge.Kind.STATE && it.state == 2 })
        f.bridge.cancelOwned(f.owner, false)
        f.queue.drain()
        assertTrue(f.offline.canceled.isEmpty())
    }

    @Test fun binderReplacementInvalidatesOldIdsAndRejectsOldCallbacks() {
        val f = Fixture()
        f.speak(false)
        f.queue.drain()
        val oldListener = f.offline.listener!!
        val replacement = FakeEndpoint()
        f.currentOffline = replacement
        f.bridge.cancelOwned(f.owner, false)
        f.queue.drain()
        assertTrue(f.events.any { it.kind == MessageTtsBridge.Kind.INVALIDATED && it.id == 1L })
        assertTrue(f.offline.canceled.isEmpty())
        assertTrue(replacement.canceled.isEmpty())
        f.speak(false)
        f.queue.drain()
        oldListener(1, 2, 0)
        f.queue.drain()
        f.bridge.cancelOwned(f.owner, false)
        f.queue.drain()
        assertEquals(listOf(1L), replacement.canceled)
        assertEquals(1, replacement.registrations)
    }

    @Test fun deathIsInvalidationAndALateOldDeathCannotClearANewEpoch() {
        val f = Fixture()
        f.speak(true)
        f.queue.drain()
        val oldDeath = f.online.onDeath!!
        f.online.alive = false
        oldDeath()
        f.queue.drain()
        assertTrue(f.events.any { it.kind == MessageTtsBridge.Kind.INVALIDATED && it.detail == "binder_died" })
        assertFalse(f.events.any { it.kind == MessageTtsBridge.Kind.STATE && it.state == 2 })
        val replacement = FakeEndpoint()
        f.currentOnline = replacement
        f.speak(true)
        f.queue.drain()
        oldDeath()
        f.queue.drain()
        f.bridge.cancelOwned(f.owner, true)
        f.queue.drain()
        assertEquals(listOf(1L), replacement.canceled)
    }

    @Test fun temporarilyMissingGetterDoesNotInvalidateALivePinnedService() {
        val f = Fixture()
        f.speak(true)
        f.queue.drain()
        f.currentOnline = null
        f.bridge.reconcile(f.owner)
        f.bridge.cancelOwned(f.owner, true)
        f.queue.drain()
        assertFalse(f.events.any { it.kind == MessageTtsBridge.Kind.INVALIDATED })
        assertEquals(listOf(1L), f.online.canceled)
    }

    @Test fun twoOwnersShareRegistrationAndClosingOneDoesNotUnregisterTheOther() {
        val f = Fixture()
        val otherEvents = mutableListOf<MessageTtsBridge.Event>()
        val otherSink: (MessageTtsBridge.Event) -> Unit = { otherEvents.add(it) }
        val other = f.bridge.openOwner(otherSink)
        f.speak(false)
        f.bridge.speak(other, false, "other page", 1)
        f.queue.drain()
        assertEquals(1, f.offline.registrations)
        f.bridge.closeOwner(f.owner)
        f.queue.drain()
        assertEquals(listOf(1L), f.offline.canceled)
        assertEquals(0, f.offline.unregistrations)
        f.offline.listener!!(2, 3, 0)
        f.queue.drain()
        assertTrue(otherEvents.any { it.kind == MessageTtsBridge.Kind.STATE && it.id == 2L && it.state == 3 })
        assertFalse(f.events.any { it.kind == MessageTtsBridge.Kind.STATE && it.id == 2L })
        // Keep the weakly held observer live until the assertions have completed.
        assertNotNull(otherSink)
    }

    @Test fun ordinaryQueryFailureKeepsOwnershipButDeadTransportInvalidatesIt() {
        val f = Fixture()
        f.speak(true)
        f.queue.drain()
        f.online.queryError = IllegalStateException()
        f.bridge.reconcile(f.owner)
        f.queue.drain()
        assertFalse(f.events.any { it.kind == MessageTtsBridge.Kind.INVALIDATED })
        f.online.queryError = DeadTransport()
        f.bridge.reconcile(f.owner)
        f.queue.drain()
        assertTrue(f.events.any { it.kind == MessageTtsBridge.Kind.INVALIDATED })
        f.bridge.cancelOwned(f.owner, true)
        f.queue.drain()
        assertTrue(f.online.canceled.isEmpty())
    }

    @Test fun pollingObservesRetainedTerminalWithoutManualStatusButton() {
        val f = Fixture()
        f.speak(true)
        f.queue.drain()
        f.online.queryState = 2
        f.queue.tick()
        assertTrue(f.events.any { it.kind == MessageTtsBridge.Kind.QUERY && it.state == 2 })
        f.bridge.cancelOwned(f.owner, true)
        f.queue.drain()
        assertTrue(f.online.canceled.isEmpty())
    }

    @Test fun uiObserverFailureDoesNotDiscardTheRemoteSession() {
        val f = Fixture()
        val brokenSink: (MessageTtsBridge.Event) -> Unit = { throw IllegalStateException("detached UI") }
        val owner = f.bridge.openOwner(brokenSink)
        f.bridge.speak(owner, true, "speech", 1)
        f.queue.drain()
        f.bridge.cancelOwned(owner, true)
        f.queue.drain()
        assertEquals(listOf(1L), f.online.canceled)
        assertNotNull(brokenSink)
    }

    private class Fixture {
        val queue = ManualQueue()
        val online = FakeEndpoint()
        val offline = FakeEndpoint()
        var currentOnline: FakeEndpoint? = online
        var currentOffline: FakeEndpoint? = offline
        val events = mutableListOf<MessageTtsBridge.Event>()
        val sink: (MessageTtsBridge.Event) -> Unit = { events.add(it) }
        val bridge = MessageTtsBridge({ if (it) currentOnline else currentOffline }, queue)
        val owner = bridge.openOwner(sink)
        fun speak(online: Boolean) = bridge.speak(owner, online, "test speech", 1)
    }

    private class ManualQueue : MessageTtsQueue {
        private val tasks = ArrayDeque<() -> Unit>()
        private val timers = mutableListOf<Timer>()
        private class Timer(val task: () -> Unit, var canceled: Boolean = false)
        override fun execute(task: () -> Unit) { tasks.addLast(task) }
        override fun schedule(delayMillis: Long, task: () -> Unit): () -> Unit {
            val timer = Timer(task)
            timers.add(timer)
            return { timer.canceled = true }
        }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().invoke() }
        fun tick() {
            val current = timers.toList()
            timers.clear()
            current.filterNot { it.canceled }.forEach { execute(it.task) }
            drain()
        }
    }

    private class DeadTransport : RuntimeException()
    private class FakeEndpoint : MessageTtsEndpoint {
        override val identity = Any()
        var apiVersion = 2
        var alive = true
        var failRegistration = false
        var registrations = 0
        var unregistrations = 0
        var queryState = 0
        var queryError: Exception? = null
        var cancelAccepted = true
        var listener: ((Long, Int, Int) -> Unit)? = null
        var onDeath: (() -> Unit)? = null
        var onSpeak: ((Long) -> Unit)? = null
        val operations = mutableListOf<String>()
        val spoken = mutableListOf<Pair<String, Int>>()
        val canceled = mutableListOf<Long>()
        override fun version() = apiVersion
        override fun isAlive() = alive
        override fun isDead(error: Throwable) = error is DeadTransport || !alive
        override fun watchDeath(onDeath: () -> Unit): () -> Unit {
            this.onDeath = onDeath
            return { this.onDeath = null }
        }
        override fun register(onState: (Long, Int, Int) -> Unit): () -> Unit {
            registrations++
            operations.add("register")
            if (failRegistration) throw IllegalStateException("registration rejected")
            listener = onState
            return { unregistrations++; listener = null }
        }
        override fun speak(text: String, queueMode: Int): Long {
            check(listener != null)
            operations.add("speak")
            spoken.add(text to queueMode)
            val id = spoken.size.toLong()
            onSpeak?.invoke(id)
            return id
        }
        override fun cancel(id: Long): Boolean { canceled.add(id); return cancelAccepted }
        override fun query(id: Long): Int { queryError?.let { throw it }; return queryState }
    }
}
