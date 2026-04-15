package com.rokid.glass.media

import com.rokid.security.glass3.open.sdk.uitls.log.L


val debug = true

val Any.TAG: String get() = "MyMedia"

fun Any.logV(message: String) {
    if (debug) {
        L.v(TAG, message)
    }

}

fun Any.logD(message: String) {
    if (debug) {
        L.d(TAG, message)
    }

}

fun Any.logI(message: String) {
    if (debug) {
        L.i(TAG, message)
    }

}

fun Any.logW(message: String) {
    if (debug) {
        L.w(TAG, message)
    }

}

fun Any.logE(message: String) {
    if (debug) {
        L.e(TAG, message)
    }

}
