package com.rokid.phone.base.viewmodel

import androidx.lifecycle.ViewModel
import com.rokid.phone.base.viewmodel.interfaces.UiEvent
import com.rokid.phone.base.viewmodel.interfaces.UiIntent
import com.rokid.phone.base.viewmodel.interfaces.UiState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/**
 * 基础 MVI ViewModel
 * Base MVI ViewModel.
 * @param State 状态类型
 * State: the state type.
 * @param Intent 意图类型
 * Intent: the intent type.
 * @param Event 一次性事件类型
 * Event: the one-time event type.
 */
abstract class MviViewModel<State : UiState, Intent : UiIntent, Event : UiEvent> : ViewModel() {

    // 初始状态
    // Initial state.
    private val initialState: State by lazy { createInitialState() }

    // 状态流
    // State flow.
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<State> = _state

    // 事件流（一次性事件）
    // Event flow for one-time events.
    private val _event = MutableSharedFlow<Event>()
    val  event: SharedFlow<Event> = _event

    // 获取当前状态
    // Get the current state.
    fun currentState(): State = _state.value

    // 必须实现：创建初始状态
    // Required implementation: create the initial state.
    protected abstract fun createInitialState(): State

    // 必须实现：处理意图
    // Required implementation: handle intents.
    protected abstract suspend fun handleIntent(intent: Intent)

    // 发送意图
    // Send an intent.
    fun  sendIntent(intent: Intent) {
        viewModelScope.launch {
            handleIntent(intent)
        }
    }

    // 更新状态
    // Update the state.
    protected fun setState(reduce: State.() -> State) {
        _state.update { it.reduce() }
    }

    // 发送一次性事件
    // Send a one-time event.
    protected fun sendEvent(event: Event) {
        viewModelScope.launch {
            _event.emit(event)
        }
    }
}