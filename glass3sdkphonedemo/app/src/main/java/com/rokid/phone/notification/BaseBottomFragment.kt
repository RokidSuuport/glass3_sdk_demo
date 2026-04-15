package com.rokid.phone.notification

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.viewbinding.ViewBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.rokid.phone.R


/**
 *
 * @Author: sunchao
 * @CreateDate: 2024/10/10 14:32
 */
abstract class BaseBottomFragment<VB : ViewBinding> : BottomSheetDialogFragment() {

    protected val TAG: String = this.javaClass.simpleName
    protected val mHandler = Handler(Looper.getMainLooper())

    private var _binding: VB? = null
    protected val binding get() = _binding!!
    private var loadingDialog: LoadingDialogFragment? = null
    private var isVisible = false

    override fun getTheme(): Int = R.style.TransparentBottomSheetDialog
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // 初始化 ViewBinding
        _binding = getViewBinding(inflater, container)
        isVisible = true
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews()        // 初始化视图
        observeViewModel() // 观察 ViewModel
    }

    // 用于获取具体 Fragment 的 ViewBinding
    protected abstract fun getViewBinding(inflater: LayoutInflater, container: ViewGroup?): VB

    // 初始化视图，由具体的 Fragment 实现
    protected abstract fun initViews()

    // 可选：观察 ViewModel，具体 Fragment 实现
    protected open fun observeViewModel() {}

    protected open fun removeObservers() {}

    // 懒加载数据，Fragment 可见时调用
    open fun loadData() {}

    override fun onDestroyView() {
        super.onDestroyView()
        isVisible = false
        removeObservers()
        _binding = null
        mHandler.removeCallbacksAndMessages(null)
    }

    override fun onResume() {
        super.onResume()
//        (activity as? BaseMainActivity<*>)?.setTabBarVisibility(activityTabBarVisibility())
    }

    protected open fun activityTabBarVisibility() : Boolean {
        return false
    }


    // 可选：日志输出方法
    protected fun logDebug(message: String) {
       Log.d(TAG, message)
    }

    protected fun showToast(msg: String) {
//        ToastUtils.showShort(requireContext(), msg)
    }

    fun showLoading() {
        if (loadingDialog == null) {
            loadingDialog = LoadingDialogFragment()
        }
        parentFragment?.fragmentManager?.let { loadingDialog?.show(it, "LoadingDialog") }
    }

    fun hideLoading() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }


}
