package com.rokid.glass

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import com.rokid.glass.base.BaseActivity
import com.rokid.glass.base.BaseGlassActivity
import com.rokid.glass.media.TAG
import com.rokid.glesse.R

/**
 * Camera Share Demo 选择页面
 */
class CameraShareSelectActivity : BaseGlassActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_share_select)

        findViewById<Button>(R.id.btn_surface_mode).setOnClickListener {
            Log.d(TAG,"----surface_mode")
            startActivity(Intent(this, CameraShareDemoActivity::class.java).apply {
                putExtra("mode", "surface")
            })
        }

        findViewById<Button>(R.id.btn_nv21_mode).setOnClickListener {
            Log.d(TAG,"----nv21_mode")
            startActivity(Intent(this, CameraShareDemoActivity::class.java).apply {
                putExtra("mode", "nv21")
            })
        }

        findViewById<Button>(R.id.btn_mix_mode).setOnClickListener {
            Log.d(TAG,"----mix_mode")
            startActivity(Intent(this, CameraShareDemoActivity::class.java).apply {
                putExtra("mode", "mix")
            })
        }
    }
}