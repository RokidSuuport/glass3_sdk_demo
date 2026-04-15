package com.rokid.phone.ui

import android.app.Activity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.rokid.phone.R


object BottomPromptDialog {
    fun show(
        activity: Activity,
        title: String,
        msg: String,
        onConfirm: () -> Unit
    ): BottomSheetDialog {
        // 创建BottomSheetDialog
        val dialog = BottomSheetDialog(activity, R.style.BottomSheetDialogStyle)
        if (!activity.isFinishing && !activity.isDestroyed) {
            // 加载布局
            val view = LayoutInflater.from(activity).inflate(R.layout.dialog_bottom_prompt, null)
            dialog.setContentView(view)

            // 初始化控件
            val tvTitle = view.findViewById<TextView>(R.id.dialog_title)
            val tvMsg = view.findViewById<TextView>(R.id.tv_msg)
            val btnConfirm = view.findViewById<Button>(R.id.btn_confirm)

            tvTitle.text = title
            tvMsg.text = msg

            btnConfirm.setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
            // 设置弹窗高度为屏幕的60%
            val window = dialog.window
            window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (activity.resources.displayMetrics.heightPixels * 0.6).toInt()
            )

            // 显示弹窗
            dialog.show()
        }
        return dialog
    }
}