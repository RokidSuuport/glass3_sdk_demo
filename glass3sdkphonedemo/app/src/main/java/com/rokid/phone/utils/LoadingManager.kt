package com.rokid.phone.utils

import android.app.Activity
import android.content.Context
import com.qmuiteam.qmui.widget.dialog.QMUITipDialog
import java.lang.ref.WeakReference
import kotlin.let

/**
 * Author: zhangshengwei
 * Date: 2025/6/24
 */
/**
 * 全局加载动画管理类（Kotlin单例实现）
 */
object LoadingManager {

    private var dialogRef: WeakReference<QMUITipDialog>? = null
    private var activityRef: WeakReference<Activity>? = null

    /**
     * 在Application中初始化（可选）
     */
    fun init(context: Context) {
        // 如果你有别的用处可以保留，否则可以删掉
    }

    /**
     * 显示加载动画 （无泄漏）
     */
    @Synchronized
    fun showLoading(activity: Activity, message: String? = null) {
        hideLoading() // 保证先释放旧的 Dialog

        if (activity.isFinishing || activity.isDestroyed) {
            return
        }

        val dialog = QMUITipDialog.Builder(activity)
            .setIconType(QMUITipDialog.Builder.ICON_TYPE_LOADING)
            .setTipWord(message)
            .create()

        dialogRef = WeakReference(dialog)
        activityRef = WeakReference(activity)

        dialog.show()
    }

    /**
     * 隐藏加载动画（无泄漏）
     */
    @Synchronized
    fun hideLoading() {
        dialogRef?.get()?.let { dialog ->
            try {
                val activity = activityRef?.get()
                if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                    dialog.dismiss()
                } else {
                    // Activity 已经被回收，不再调用 dismiss
                }
            } catch (_: Exception) {
            }
        }

        dialogRef = null
        activityRef = null
    }
}
