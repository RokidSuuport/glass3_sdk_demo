package com.rokid.glass.utils

import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext



fun <T> MutableSharedFlow<T>.call(
    scope: CoroutineScope,
    data: T,
    context: CoroutineContext = EmptyCoroutineContext,
) {
    scope.launch(context) {
        emit(data)
    }
}

fun MutableSharedFlow<Unit>.call(
    scope: CoroutineScope,
    context: CoroutineContext = EmptyCoroutineContext,
) {
    scope.launch(context) {
        emit(Unit)
    }
}

fun <T> MutableSharedFlow<T>.callOnCreated(
    scope: LifecycleCoroutineScope,
    data: T
) {
    scope.launchWhenCreated {
        emit(data)
    }
}

fun MutableSharedFlow<Unit>.callOnCreated(scope: LifecycleCoroutineScope) {
    callOnCreated(scope, Unit)
}

fun <T> MutableStateFlow<T>.call(data: T) {
    value = data
}

/**
 * 添加元素
 */
fun <T> MutableStateFlow<Set<T>>.increaseToSet(data: T) {
    value = value + data
}

/**
 * 移除元素
 */
fun <T> MutableStateFlow<Set<T>>.reduceFromSet(data: T) {
    value = value - data
}

/**
 * 清空
 */
fun <T> MutableStateFlow<Set<T>>.recreateSet() {
    value = setOf()
}


/**
 * 添加元素
 */
fun <T> MutableStateFlow<List<T>>.increaseToList(dataList: List<T>) {
    value = value + dataList
}

/**
 * 添加元素
 */
fun <T> MutableStateFlow<List<T>>.increaseToList(data: T) {
    value = value + data
}

/**
 * 移除元素
 */
fun <T> MutableStateFlow<List<T>>.reduceFromList(data: T) {
    value = value - data
}

/**
 * 移除元素
 */
fun <T> MutableStateFlow<List<T>>.reduceFromList(index: Int) {
    value = value - value[index]
}

/**
 * 长度
 */
fun <T> MutableStateFlow<List<T>>.listSize(): Int {
    return value.size
}

/**
 * 判空
 */
fun <T> MutableStateFlow<List<T>>.listIsEmpty(): Boolean {
    return value.isEmpty()
}

/**
 * 清空
 */
fun <T> MutableStateFlow<List<T>>.recreateList() {
    value = emptyList()
}

fun <T> Flow<T>.collect(
    scope: CoroutineScope,
    context: CoroutineContext = EmptyCoroutineContext,
    collector: FlowCollector<T>
) {
    scope.launch(context) {
        this@collect.collect(collector)
    }
}

fun <T> Flow<T>.collectJob(scope: CoroutineScope, collector: FlowCollector<T>): Job {
    return scope.launch {
        this@collectJob.collect(collector)
    }
}

fun delay(
    scope: CoroutineScope,
    timeMillis: Long,
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend CoroutineScope.() -> Unit
) {
    scope.launch(context) {
        kotlinx.coroutines.delay(timeMillis)
        block.invoke(scope)
    }
}

fun <T> Channel<T>.send(
    scope: CoroutineScope,
    data: T
) {
    scope.launch {
        this@send.send(data)
    }
}

fun <T> Channel<T>.get(scope: CoroutineScope, block: suspend (T) -> Unit) {
    scope.launch {
        for (t in this@get) {
            block.invoke(t)
        }
    }
}