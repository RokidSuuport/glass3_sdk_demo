package com.rokid.glass

import android.os.Bundle
import android.view.KeyEvent
import com.rokid.glass.base.BaseGlassActivity
import com.rokid.glass.base.GlassKeyEvent
import com.rokid.glass.camera.CameraShareGestureHandler
import com.rokid.glass.camera.MixRecordFragment
import com.rokid.glass.camera.Nv21ExportFragment
import com.rokid.glass.camera.SurfaceShareFragment

/**
 * Camera Share Demo 主 Activity
 * 根据传入的模式参数显示对应的 Fragment
 */
class CameraShareDemoActivity : BaseGlassActivity() {

    companion object {
        private const val CAMERA_SHARE_FRAGMENT_TAG = "camera_share_fragment"
        private const val EXTRA_MODE = "mode"
        private const val MODE_SURFACE = "surface"
        private const val MODE_NV21 = "nv21"
        private const val MODE_MIX = "mix"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_SURFACE

        val fragment = when (mode) {
            MODE_SURFACE -> SurfaceShareFragment.newInstance()
            MODE_NV21 -> Nv21ExportFragment.newInstance()
            MODE_MIX -> MixRecordFragment.newInstance()
            else -> SurfaceShareFragment.newInstance()
        }

        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, fragment, CAMERA_SHARE_FRAGMENT_TAG)
            .commit()
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        val fragment = supportFragmentManager.findFragmentByTag(CAMERA_SHARE_FRAGMENT_TAG)
        if (fragment is CameraShareGestureHandler && fragment.handleGlassKeyEvent(keyEvent)) {
            return true
        }
        return super.onGlassKeyEvent(keyEvent)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.repeatCount == 0) GlassKeyEvent(GlassKeyEvent.KEYCODE_FRONT)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (event.repeatCount == 0) GlassKeyEvent(GlassKeyEvent.KEYCODE_BEHIND)
                    return true
                }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                    if (event.repeatCount == 0) ringHomeClick()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
