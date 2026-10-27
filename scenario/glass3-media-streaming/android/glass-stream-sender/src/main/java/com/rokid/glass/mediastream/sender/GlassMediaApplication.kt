package com.rokid.glass.mediastream.sender

import android.app.Application

/** 应用层不初始化 SDK 或 WebRTC；两个媒体库会在首次使用时自行完成初始化。 */
class GlassMediaApplication : Application()
