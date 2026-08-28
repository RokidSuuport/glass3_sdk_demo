package com.rokid.phone.base.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import com.rokid.phone.base.viewmodel.MviViewModel
import com.rokid.phone.base.viewmodel.interfaces.UiEvent
import com.rokid.phone.base.viewmodel.interfaces.UiIntent
import com.rokid.phone.base.viewmodel.interfaces.UiState
import kotlinx.coroutines.launch

abstract class MviDialogFragment<VB : ViewBinding, State : UiState, Intent : UiIntent, Event : UiEvent, VM : MviViewModel<State, Intent, Event>> :
    DialogFragment() { // 关键点 1：继承 DialogFragment

    private val TAG = "MviDialogFragment"
    private lateinit var binding: VB
    protected abstract val viewModel: VM

    override fun onStart() {
        super.onStart()
        observeViewModel()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = initViewBinding()
        onInit(savedInstanceState)
        return binding.root
    }

    // 可选：设置对话框宽高/样式（如果需要公共配置）
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT, // 或自定义宽度
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    abstract fun onInit(savedInstanceState: Bundle?)

    abstract fun initViewBinding(): VB

    private fun observeViewModel() {
        // 观察状态变化（与 Fragment 相同）
        lifecycleScope.launch {
            viewModel.state
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { render(it) }
        }

        // 观察一次性事件（与 Fragment 相同）
        lifecycleScope.launch {
            viewModel.event
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { handleEvent(it) }
        }
    }

    protected abstract fun render(state: State)

    protected open fun handleEvent(event: Event) {}

    protected fun dispatchIntent(intent: Intent) {
        viewModel.sendIntent(intent)
    }
}
