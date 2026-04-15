package com.rokid.phone.notification.adapter

import android.graphics.drawable.Drawable

data class AppInfo(val name: String, val packageName: String, val icon: Drawable, var isCheck: Boolean, val firstLetter: Char)


data class SectionItem(
    val letter: Char
)

sealed class ListItem {
    data class App(val info: AppInfo) : ListItem()
    data class Section(val item: SectionItem) : ListItem()
}