package com.rokid.glass.camera

/** 接收相机共享页面由 Activity 统一分发的眼镜腿手势。 */
interface CameraShareGestureHandler {
    fun handleGlassKeyEvent(keyEvent: Int): Boolean
}
