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
 * @param State 状态类型
 * @param Intent 意图类型
 * @param Event 一次性事件类型
 */
abstract class MviViewModel<State : UiState, Intent : UiIntent, Event : UiEvent> : ViewModel() {

    // 初始状态
    private val initialState: State by lazy { createInitialState() }

    // 状态流
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<State> = _state

    // 事件流（一次性事件）
    private val _event = MutableSharedFlow<Event>()
    val  event: SharedFlow<Event> = _event

    // 获取当前状态
    fun currentState(): State = _state.value

    // 必须实现：创建初始状态
    protected abstract fun createInitialState(): State

    // 必须实现：处理意图
    protected abstract suspend fun handleIntent(intent: Intent)

    // 发送意图
    fun  sendIntent(intent: Intent) {
        viewModelScope.launch {
            handleIntent(intent)
        }
    }

    // 更新状态
    protected fun setState(reduce: State.() -> State) {
        _state.update { it.reduce() }
    }

    // 发送一次性事件
    protected fun sendEvent(event: Event) {
        viewModelScope.launch {
            _event.emit(event)
        }
    }
}