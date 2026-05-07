package com.rokid.glass

import android.os.Bundle
import com.rokid.glass.base.BaseGlassActivity
import com.rokid.glass.camera.MixRecordFragment
import com.rokid.glass.camera.Nv21ExportFragment
import com.rokid.glass.camera.SurfaceShareFragment

/**
 * Camera Share Demo 主 Activity
 * 根据传入的模式参数显示对应的 Fragment
 */
class CameraShareDemoActivity : BaseGlassActivity() {

    companion object {
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
            .replace(android.R.id.content, fragment)
            .commit()
    }
}
