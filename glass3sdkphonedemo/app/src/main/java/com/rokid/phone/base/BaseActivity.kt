package com.rokid.phone.base

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.viewbinding.ViewBinding


abstract class BaseActivity<VB : ViewBinding> : FragmentActivity() {

    protected lateinit var binding: VB
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = initViewBinding()
        setContentView(binding.root)
        onInit(savedInstanceState)
    }

    abstract fun onInit(savedInstanceState: Bundle?)

    abstract fun initViewBinding(): VB

}