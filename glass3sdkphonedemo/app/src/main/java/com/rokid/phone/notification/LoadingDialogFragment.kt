package com.rokid.phone.notification
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import androidx.fragment.app.DialogFragment
import com.rokid.phone.R
import com.rokid.phone.databinding.DialogLoadingBinding




class LoadingDialogFragment : DialogFragment() {

    private var _binding: DialogLoadingBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        setStyle(STYLE_NO_FRAME, R.style.TransparentBottomSheetDialog)
        super.onCreate(savedInstanceState)
    }
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogLoadingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 设置图片旋转动画
        val rotateAnimation = RotateAnimation(
            0f, 360f,
            RotateAnimation.RELATIVE_TO_SELF, 0.5f,
            RotateAnimation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 1000 // 动画时长1秒
            repeatCount = RotateAnimation.INFINITE // 无限循环
            interpolator = LinearInterpolator() // 匀速旋转
        }

        // 启动动画
        binding.loadingImage.startAnimation(rotateAnimation)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
