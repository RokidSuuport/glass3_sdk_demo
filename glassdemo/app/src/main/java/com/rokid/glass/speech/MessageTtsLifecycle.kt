package com.rokid.glass.speech

/** 页面只负责监听与退出清理；会话由 SDK Manager 管理。
 * The page owns listeners and exit cleanup; SDK Managers own sessions.
 */
internal class MessageTtsLifecycle(
    private val online: Engine,
    private val offline: Engine,
    private val execute: (() -> Unit) -> Unit,
    private val report: (Boolean, Long, Int, Int) -> Unit,
    private val failure: (Exception) -> Unit,
    private val invalidSession: (Boolean) -> Unit
) {
    interface Engine {
        fun addListener(listener: (Long, Int, Int) -> Unit)
        fun removeListener()
        fun speak(text: String, queueMode: Int): Long
        fun cancelAll()
    }

    @Volatile private var active = false
    @Volatile private var closed = false
    @Volatile private var generation = 0

    fun start() {
        if (active || closed) return
        active = true
        val current = ++generation
        execute {
            if (!isCurrent(current)) return@execute
            engines().forEach { (isOnline, engine) ->
                attempt {
                    engine.addListener { id, state, error ->
                        if (isCurrent(current)) report(isOnline, id, state, error)
                    }
                }
            }
        }
    }

    fun speak(isOnline: Boolean, text: String, queueMode: Int, guard: () -> Boolean) {
        val current = generation
        if (!isCurrent(current)) return
        execute {
            if (!isCurrent(current)) return@execute
            attempt {
                if (guard() && isCurrent(current)) {
                    val id = engine(isOnline).speak(text, queueMode)
                    if (id <= 0L && isCurrent(current)) invalidSession(isOnline)
                }
            }
        }
    }

    fun cancelAll(isOnline: Boolean, requested: () -> Unit) {
        val current = generation
        if (!isCurrent(current)) return
        execute {
            if (isCurrent(current)) attempt {
                engine(isOnline).cancelAll()
                requested()
            }
        }
    }

    fun stop() {
        if (!active) return
        active = false
        generation++
        execute {
            engines().forEach { (_, engine) ->
                // 取消失败也继续移除监听，并清理另一个引擎。
                // Even if cancellation fails, detach listeners and clean up the other engine.
                attempt { engine.cancelAll() }
                attempt { engine.removeListener() }
            }
        }
    }

    fun close() {
        if (closed) return
        stop()
        closed = true
    }

    private fun engine(isOnline: Boolean) = if (isOnline) online else offline
    private fun engines() = listOf(true to online, false to offline)
    private fun isCurrent(current: Int) = active && !closed && current == generation
    private fun attempt(action: () -> Unit) {
        try { action() } catch (error: Exception) { failure(error) }
    }
}
