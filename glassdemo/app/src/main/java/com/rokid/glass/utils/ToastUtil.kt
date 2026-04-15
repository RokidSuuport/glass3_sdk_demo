package com.rokid.glass.utils

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import com.rokid.glesse.R


/**
 * Author: zhangshengwei
 * Date: 2025/5/28
 */
@SuppressLint("StaticFieldLeak")
object ToastUtil {
    private var currentToast: Toast? = null
    private var context: Context? = null

    fun init(context: Context) {
        ToastUtil.context = context.applicationContext
    }

    @SuppressLint("StaticFieldLeak")
    fun show(message: String) {
        val ctx = context ?: throw IllegalStateException("ToastUtil未初始化")
        currentToast?.cancel()
        currentToast = Toast(ctx).apply {
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 0)
            duration = Toast.LENGTH_SHORT
            view = LayoutInflater.from(ctx).inflate(R.layout.layout_toast, null)
            (view?.findViewById<TextView>(R.id.toast_text))?.text = message
            show()
        }
    }





}