package com.rokid.glass.speech

/** Main-thread local ownership, not an SDK session ID or remote cancellation acknowledgement. */
internal class OnlineSpeechSession {
    companion object { const val CAPACITY = 10 }
    private var nextToken = 0L
    var id = 0L
        private set
    var starting = false
        private set
    var stopping = false
        private set
    var waiting = 0
        private set

    fun accepts(token: Long) = token != 0L && token == id && !stopping

    fun begin(): Long {
        if (id != 0L) return 0L
        id = ++nextToken
        starting = true
        stopping = false
        return id
    }

    fun started(token: Long): Boolean {
        if (!accepts(token)) return false
        starting = false
        return true
    }

    fun complete(token: Long): Boolean {
        if (!accepts(token)) return false
        id = 0L
        starting = false
        return true
    }

    fun cancel(stop: () -> Boolean): Boolean {
        if (id == 0L) return true
        stopping = true
        starting = false
        if (!stop()) return false
        id = 0L
        stopping = false
        return true
    }

    fun enqueue(): Boolean {
        if (waiting + (if (id != 0L) 1 else 0) >= CAPACITY) return false
        waiting++
        return true
    }

    fun beginNext(): Long {
        if (waiting == 0) return 0L
        val token = begin()
        if (token != 0L) waiting--
        return token
    }

    fun clearQueue(): Int {
        val removed = waiting
        waiting = 0
        return removed
    }
}
