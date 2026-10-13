package com.rokid.glass.camera

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Optional async queue: one running lease plus one queued. Both modes own and count every lease. */
internal class LeaseConsumer<T : AutoCloseable>(
    private val consume: (T) -> Unit,
    private val onError: (Exception) -> Unit = {},
    private val asynchronous: Boolean = true,
) : AutoCloseable {
    private val accepting = AtomicBoolean(true)
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(1), { task -> Thread(task, "i420-consumer") })
    val received = AtomicInteger()
    val returned = AtomicInteger()
    val dropped = AtomicInteger()
    val returnFailures = AtomicInteger()

    fun submit(frame: T): Boolean {
        received.incrementAndGet()
        if (!asynchronous) {
            val accepted = accepting.get()
            process(frame)
            return accepted
        }
        if (accepting.get()) try {
            executor.execute { process(frame) }
            return true
        } catch (_: RejectedExecutionException) { /* Queue full, or concurrent stop. */ }
        dropped.incrementAndGet()
        giveBack(frame)
        return false
    }

    private fun process(frame: T) {
        try { if (accepting.get()) consume(frame) else dropped.incrementAndGet() }
        catch (error: Exception) { onError(error) }
        finally { giveBack(frame) }
    }

    private fun giveBack(frame: T) {
        try { frame.close(); returned.incrementAndGet() }
        catch (error: Exception) { returnFailures.incrementAndGet(); onError(error) }
    }

    override fun close() {
        accepting.set(false)
        // Do not shutdownNow: queued tasks own leases and must execute their finally blocks.
        executor.shutdown()
    }
}
