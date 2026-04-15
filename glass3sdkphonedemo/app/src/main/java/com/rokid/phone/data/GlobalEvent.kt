package com.rokid.phone.data

import kotlinx.coroutines.flow.MutableSharedFlow

object GlobalEvent {
    val connectionRejectedEvent= MutableSharedFlow<Unit>()

    val autoConnectionEvent= MutableSharedFlow<Unit>()
}