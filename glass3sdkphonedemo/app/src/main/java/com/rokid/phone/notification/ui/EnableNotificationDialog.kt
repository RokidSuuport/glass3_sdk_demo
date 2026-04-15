package com.rokid.phone.notification.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.rokid.phone.databinding.DialogEnableNotificationBinding
import com.rokid.phone.notification.BaseBottomFragment


class EnableNotificationDialog(private val cancelListener: () -> Unit, private val openListener: () -> Unit) :
    BaseBottomFragment<DialogEnableNotificationBinding>() {
    override fun getViewBinding(
        inflater: LayoutInflater, container: ViewGroup?
    ): DialogEnableNotificationBinding {
        return DialogEnableNotificationBinding.inflate(inflater, container, false)
    }

    override fun initViews() {
        isCancelable = false
        binding.btnNotificationEnableCancel.setOnClickListener {
            cancelListener()
            dismiss()
        }
        binding.btnNotificationEnableConfirm.setOnClickListener {
            openListener()
            dismiss()
        }
        binding.btnNotificationEnableConfirm.setPressEffect()
        binding.btnNotificationEnableCancel.setPressEffect()
    }

    @SuppressLint("ClickableViewAccessibility")
    fun View.setPressEffect() {
        this.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> this.alpha = 0.5f  // 按下时透明度变为50%
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> this.alpha = 1.0f  // 松开或取消时恢复透明度
            }
            // 返回 false 以确保其他事件（如点击事件）继续传播
            false
        }
    }

}
