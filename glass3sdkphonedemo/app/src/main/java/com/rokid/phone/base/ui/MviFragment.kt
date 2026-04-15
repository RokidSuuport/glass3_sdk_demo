package com.rokid.phone.base.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import com.rokid.phone.base.viewmodel.MviViewModel
import com.rokid.phone.base.viewmodel.interfaces.UiEvent
import com.rokid.phone.base.viewmodel.interfaces.UiIntent
import com.rokid.phone.base.viewmodel.interfaces.UiState
import kotlinx.coroutines.launch

/**
 * Author: zhangshengwei
 * Date: 2025/5/26
 */
abstract class MviFragment<VB : ViewBinding,State : UiState, Intent : UiIntent, Event : UiEvent, VM : MviViewModel<State, Intent, Event>> :
    Fragment() {

    private val TAG = "MviFragment"
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
        binding =initViewBinding()
        onInit(savedInstanceState)
        return binding.root
    }



    abstract fun onInit(savedInstanceState: Bundle?)

    abstract fun initViewBinding(): VB


    private fun observeViewModel() {
        // 观察状态变化
        lifecycleScope.launch {
            viewModel.state
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { state ->
                    render(state)
                }
        }

        // 观察一次性事件
        lifecycleScope.launch {
            viewModel.event
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { event ->
                    handleEvent(event)
                }
        }
    }

    // 必须实现：渲染状态
    protected abstract fun render(state: State)

    // 可选实现：处理一次性事件
    protected open fun handleEvent(event: Event) {}

    // 发送意图
    protected fun dispatchIntent(intent: Intent) {
        viewModel.sendIntent(intent)
    }
}