package com.rokid.glass

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import com.rokid.glass.base.BaseGlassActivity
import com.rokid.glass.base.GlassKeyEvent
import com.rokid.glass.media.TAG
import com.rokid.glesse.R

/**
 * Camera Share Demo 选择页面
 * Selection page for the Camera Share Demo.
 *
 * 输出选型（按下游需要的格式和生命周期选择，不按“是否能看到画面”选择）：
 * - Surface：只需显示、GPU 处理/合成或传给支持 Surface 的链路，不需要 CPU 访问 YUV。
 *   新功能使用直接 Surface 页，原始旧接口页保留 Helper/GL 接法。
 * - NV21：下游识别/图像算法已有 NV21（Y + 交错 VU）输入，优先沿用此接口。
 *   buffer 在回调期间借用、ByteArray 也可能复用；异步使用必须先复制。本 Demo 自己渲染副本。
 * - I420：WebRTC/I420 原生处理等需要独立 Y/U/V 平面、stride 或跨线程帧租约的消费者。
 *   最终消费完成才 close；队满丢帧也须 close。本 Demo 的三纹理预览额外复制数据，可关闭。
 *
 * 共享内存不是 I420 独有：NV21 Buffer 入口同样借用共享内存。两者主要按下游数据布局和
 * 持有时长选择：NV21 Buffer 仅回调内有效；I420 租约允许跨线程持有到最终 close。
 * 导出数据不会自动显示；NV21 和 I420 页都有独立的预览消费者，画面来自各自的导出帧。
 * 单纯显示通常选 Surface，避免为显示而导出 CPU 数据再上传；具体处理成本仍依实际链路而定。
 * Surface/录像文字水印不意味着 NV21/I420 自动带同样文字。各输出独立提交倍率意图、释放自身资源；
 * 实际物理倍率共享并受仲裁，须以各会话的 ready/appliedZoom 状态为准。
 * 旧 Surface 入口直接保留远端原页面：显式 Config + 原 GL FIT_CENTER 渲染器，
 * 与 Options 新接口入口分开，避免用新增的默认参数调用替代旧 Demo 兼容基线。
 */
class CameraShareSelectActivity : BaseGlassActivity() {

    // Buttons handle pointer clicks themselves. Translating the same tap into a delayed
    // glass CLICK would open the selected mode again, covering the page the user just chose.
    override val translateTouchToGlassGesture: Boolean = false

    private lateinit var modeButtons: List<Button>
    private lateinit var modeValues: List<String>
    private var selectedModeIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_share_select)

        val surfaceButton = findViewById<Button>(R.id.btn_surface_mode)
        val nv21Button = findViewById<Button>(R.id.btn_nv21_mode)
        val mixButton = findViewById<Button>(R.id.btn_mix_mode)
        val i420Button = findViewById<Button>(R.id.btn_i420_mode)
        val legacyButton = findViewById<Button>(R.id.btn_surface_legacy_mode)
        // Keep the legacy implementation and route, but skip hidden entries in temple navigation.
        val visibleModes = listOf(
            surfaceButton to "surface", nv21Button to "nv21", mixButton to "mix",
            i420Button to "i420", legacyButton to "surface_legacy",
        ).filter { (button, _) -> button.visibility == View.VISIBLE }
        modeButtons = visibleModes.map { it.first }
        modeValues = visibleModes.map { it.second }
        legacyButton.setOnClickListener { openMode("surface_legacy") }
        i420Button.setOnClickListener { openMode("i420") }

        surfaceButton.setOnClickListener {
            Log.d(TAG,"----surface_mode")
            openMode("surface")
        }

        nv21Button.setOnClickListener {
            Log.d(TAG,"----nv21_mode")
            openMode("nv21")
        }

        mixButton.setOnClickListener {
            Log.d(TAG,"----mix_mode")
            openMode("mix")
        }

        updateModeSelection()
    }

    override fun onResume() {
        super.onResume()
        if (::modeButtons.isInitialized) updateModeSelection()
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        when (keyEvent) {
            GlassKeyEvent.KEYCODE_FRONT -> {
                moveSelection(1)
                return true
            }
            GlassKeyEvent.KEYCODE_BEHIND -> {
                moveSelection(-1)
                return true
            }
            GlassKeyEvent.KEYCODE_CLICK -> {
                openMode(modeValues[selectedModeIndex])
                return true
            }
        }
        return super.onGlassKeyEvent(keyEvent)
    }

    private fun moveSelection(offset: Int) {
        selectedModeIndex = (selectedModeIndex + offset + modeButtons.size) % modeButtons.size
        Log.d(TAG, "mode changed: index=$selectedModeIndex, mode=${modeValues[selectedModeIndex]}")
        updateModeSelection()
    }

    private fun updateModeSelection() {
        modeButtons.forEachIndexed { index, button -> button.isSelected = index == selectedModeIndex }
        val selectedButton = modeButtons[selectedModeIndex]
        selectedButton.post {
            selectedButton.requestRectangleOnScreen(
                Rect(0, 0, selectedButton.width, selectedButton.height), true
            )
        }
    }

    private fun openMode(mode: String) {
        startActivity(Intent(this, CameraShareDemoActivity::class.java).apply {
            putExtra("mode", mode)
        })
    }
}
