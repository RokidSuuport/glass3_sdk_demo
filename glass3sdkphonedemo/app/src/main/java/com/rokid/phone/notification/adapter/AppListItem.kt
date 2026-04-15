package com.rokid.phone.notification.adapter

sealed class AppListItem {
    data class Header(val letter: String) : AppListItem()
    data class App(val info: AppInfo) : AppListItem()
}