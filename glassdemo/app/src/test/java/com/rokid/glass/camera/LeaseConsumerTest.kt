package com.rokid.glass.camera

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LeaseConsumerTest {
    private class Lease : AutoCloseable {
        val releases = AtomicInteger()
        val returned = CountDownLatch(1)
        override fun close() { releases.incrementAndGet(); returned.countDown() }
    }

    @Test fun synchronousConsumptionReturnsBeforeSubmitAndAlsoReturnsLateFrames() {
        val reads = AtomicInteger()
        val consumer = LeaseConsumer<Lease>(consume = { reads.incrementAndGet() }, asynchronous = false)
        val first = Lease(); val late = Lease()
        assertTrue(consumer.submit(first))
        assertEquals(1, first.releases.get())
        assertEquals(1, consumer.returned.get())
        consumer.close()
        assertFalse(consumer.submit(late))
        assertEquals(1, late.releases.get())
        assertEquals(1, reads.get())
        assertEquals(2, consumer.received.get())
        assertEquals(2, consumer.returned.get())
        assertEquals(1, consumer.dropped.get())
    }

    @Test fun synchronousConsumerFailureStillReturnsTheFrame() {
        val errors = AtomicInteger()
        val consumer = LeaseConsumer<Lease>(consume = { error("consumer failed") },
            onError = { errors.incrementAndGet() }, asynchronous = false)
        val frame = Lease()
        try {
            consumer.submit(frame)
            assertEquals(1, frame.releases.get())
            assertEquals(1, consumer.returned.get())
            assertEquals(1, errors.get())
            assertEquals(0, consumer.returnFailures.get())
        } finally { consumer.close() }
    }

    @Test fun failedCloseMustNotBeReportedAsAReturnedLease() {
        val failure = CountDownLatch(1)
        val nextEntered = CountDownLatch(1)
        val finishNext = CountDownLatch(1)
        val reads = AtomicInteger()
        val consumer = LeaseConsumer<AutoCloseable>(consume = {
            if (reads.incrementAndGet() == 2) {
                nextEntered.countDown()
                check(finishNext.await(3, TimeUnit.SECONDS))
            }
        }, onError = { failure.countDown() })
        try {
            consumer.submit(AutoCloseable { throw IllegalStateException("return failed") })
            assertTrue(failure.await(3, TimeUnit.SECONDS))
            consumer.submit(AutoCloseable {})
            // The next consume starts only after the first release has finished updating counters.
            assertTrue(nextEntered.await(3, TimeUnit.SECONDS))
            assertEquals(2, consumer.received.get())
            assertEquals("A failed close is not a successful return", 0, consumer.returned.get())
        } finally { finishNext.countDown(); consumer.close() }
    }

    @Test fun fullQueueReturnsDroppedLeaseAndStopDrainsOutstandingLeases() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val reads = AtomicInteger()
        val consumer = LeaseConsumer<Lease>(consume = {
            reads.incrementAndGet(); entered.countDown()
            check(finish.await(3, TimeUnit.SECONDS))
        })
        val running = Lease(); val queued = Lease(); val dropped = Lease(); val late = Lease()
        try {
            assertTrue(consumer.submit(running))
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertTrue(consumer.submit(queued))
            assertFalse(consumer.submit(dropped))
            assertEquals(1, dropped.releases.get())
            consumer.close()
            assertEquals(0, running.releases.get()) // Final consumer still owns it after stop.
            assertFalse(consumer.submit(late))
            finish.countDown()
            listOf(running, queued, dropped, late).forEach {
                assertTrue(it.returned.await(3, TimeUnit.SECONDS))
                assertEquals(1, it.releases.get())
            }
            assertEquals(1, reads.get()) // Stopped queued frames must not be read.
        } finally { finish.countDown(); consumer.close() }
    }

    @Test fun consumerFailureStillReturnsLeaseAndReportsError() {
        val error = CountDownLatch(1)
        val consumer = LeaseConsumer<Lease>(consume = { throw IllegalStateException("decode failed") },
            onError = { error.countDown() })
        val frame = Lease()
        try {
            consumer.submit(frame)
            assertTrue(frame.returned.await(3, TimeUnit.SECONDS))
            assertTrue(error.await(3, TimeUnit.SECONDS))
            assertEquals(1, frame.releases.get())
        } finally { consumer.close() }
    }
}
