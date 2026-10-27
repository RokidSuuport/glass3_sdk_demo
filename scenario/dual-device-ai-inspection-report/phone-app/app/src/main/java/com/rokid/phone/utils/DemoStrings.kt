package com.rokid.phone.utils

import androidx.annotation.StringRes
import com.rokid.phone.MyApplication

/** Resolve at use time so services and callbacks follow system language changes too. */
internal fun demoString(@StringRes id: Int, vararg args: Any?): String {
    val context = MyApplication.instance
    return if (args.isEmpty()) context.getString(id) else context.getString(id, *args.map { it ?: "" }.toTypedArray())
}
