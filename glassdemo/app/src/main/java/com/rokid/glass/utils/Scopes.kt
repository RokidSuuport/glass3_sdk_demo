package com.rokid.glass.utils

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Created by wjm on 2025/6/21
 */
object Scopes {
    private const val TAG = "Scopes"
    val workScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Demo Caught1 exception: $throwable")
    })

    val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Demo Caught2 exception: $throwable")
    })

    val defaultScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Demo Caught3 exception: $throwable")
    })

}