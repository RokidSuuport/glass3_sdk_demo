package com.rokid.glass.media

fun and(vararg values: Boolean?): Boolean {
    var ret: Boolean? = null
    values.forEach {
        ret = if (it != null) ret?.and(it) ?: it else ret
    }
    return ret ?: false
}

fun or(vararg flags: Boolean?): Boolean {
    var ret: Boolean? = null
    flags.forEach {
        ret = if (it != null) ret?.or(it) ?: it else ret
    }
    return ret ?: false
}