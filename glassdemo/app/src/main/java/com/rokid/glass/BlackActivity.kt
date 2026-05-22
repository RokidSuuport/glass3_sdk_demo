package com.rokid.glass

import android.os.Bundle
import com.rokid.glass.base.BaseGlassActivity
import com.rokid.glesse.databinding.ActivityBlackBinding

class BlackActivity : BaseGlassActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityBlackBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }


}
