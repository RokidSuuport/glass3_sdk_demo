package com.rokid.glass.utils

/**
 * author: kai.yin
 * email : kai.yin@rokid.com
 * since : 2023/3/20 14:29
 */

fun <T> List<T>?.values(func: (List<T>) -> Unit) {
    if (!isNullOrEmpty()) {
        func.invoke(this)
    }
}

fun <T> Array<T>?.values(func: (Array<T>) -> Unit) {
    if (!isNullOrEmpty()) {
        func.invoke(this)
    }
}

fun Map<String, Any>?.getString(key: String): String? {
    return getObject(key)
}

fun Map<String, Any>?.getBoolean(key: String): Boolean? {
    return getObject(key)
}

fun Map<String, Any>?.getInt(key: String): Int? {
    return getObject(key)
}

@Suppress("UNCHECKED_CAST")
fun <Value> Map<String, Any>?.getObject(key: String): Value? {
    return try {
        this?.get(key) as? Value
    } catch (e: Exception) {
        null
    }
}

fun <T> List<T>?.findIndex(item: T): Int? {
    return this?.indexOf(item)?.let {
        if (it in indices) {
            it
        } else {
            null
        }
    }
}