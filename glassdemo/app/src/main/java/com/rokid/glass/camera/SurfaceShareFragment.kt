package com.rokid.glass.camera

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.rokid.glesse.R
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.camera.CameraShareHelper
import com.rokid.security.glass3.sdk.base.data.media.CameraShareConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Surface 共享模式 Fragment
 * 用于渲染相机画面到 GLSurfaceView，实现 AR 眼镜的实时视频显示功能。
 * 该组件负责管理 OpenGL ES 渲染上下文、相机数据流以及动态配置切换。
 */
class SurfaceShareFragment : Fragment() {

    companion object {
        // 日志标签，用于过滤和识别此类的日志输出
        private const val TAG = "SurfaceShareFragment"

        // 配置切换延迟时间（毫秒），在初始配置启动后等待指定时间再切换到高分辨率配置
        private const val CONFIG_SWITCH_DELAY_MS = 15_000L

        /**
         * 工厂方法创建新的 SurfaceShareFragment 实例
         * @return 新创建的 Fragment 实例
         */
        fun newInstance(): SurfaceShareFragment {
            return SurfaceShareFragment()
        }
    }

    // 主线程 Handler，用于在主线程执行 UI 更新操作
    private val mainHandler = Handler(Looper.getMainLooper())

    // 配置切换任务的可引用对象，便于取消之前的定时任务
    private var configSwitchRunnable: Runnable? = null

    // GLSurfaceView 组件，用于 OpenGL ES 渲染显示
    private lateinit var glSurfaceView: GLSurfaceView

    // 信息展示 TextView，显示当前连接状态、分辨率、帧率等信息
    private lateinit var tvInfo: TextView

    // 相机共享助手类，负责与 Rokid Glass SDK 交互获取相机数据
    private var surfaceHelper: CameraShareHelper? = null

    // 自定义 OpenGL 渲染器，处理具体的图形渲染逻辑
    private var renderer: SurfaceShareRenderer? = null

    // 帧计数器，用于计算实时帧率
    private var frameCount = 0L

    // 上次 FPS 计算的时间戳，用于间隔统计
    private var lastFpsTime = 0L

    // 标记是否已完成配置切换，避免重复切换
    private var configSwitched = false

    /**
     * 创建 Fragment 视图布局
     * @param inflater 布局填充器
     * @param container 父容器
     * @param savedInstanceState 保存的状态包
     * @return 填充后的视图对象
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_surface_share, container, false)
    }

    /**
     * 视图创建完成后的初始化操作
     * @param view 当前 Fragment 的根视图
     * @param savedInstanceState 保存的状态包
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 查找并初始化界面控件
        glSurfaceView = view.findViewById(R.id.gl_surface_view)
        tvInfo = view.findViewById(R.id.tv_info)

        // 设置 GLSurfaceView 的渲染参数
        setupGLSurfaceView()
    }

    /**
     * Fragment 恢复时调用，启动 GLSurfaceView 的渲染循环
     */
    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
    }

    /**
     * 阻塞式释放 OpenGL 资源
     * 必须在 GLSurfaceView.onPause() 之前执行，确保渲染线程仍在运行时清理资源
     * 防止因上下文销毁导致的 BufferQueue/fence 交错问题和 fdsan（重复关闭）错误
     */
    private fun teardownGlResourcesBlocking() {
        // 移除待执行的配置切换任务，防止内存泄漏
        configSwitchRunnable?.let { mainHandler.removeCallbacks(it) }
        configSwitchRunnable = null

        // 检查 GLSurfaceView 是否已初始化
        if (!::glSurfaceView.isInitialized) return

        // 保存当前 helper 和 renderer 引用以便后续释放
        val helper = surfaceHelper
        surfaceHelper = null
        val rend = renderer

        // 使用 CountDownLatch 实现同步等待，确保资源完全释放后再继续
        val latch = CountDownLatch(1)

        // 在 GL 渲染线程中执行资源释放操作
        glSurfaceView.queueEvent {
            try {
                // 释放相机表面资源
                helper?.releaseSurface()
            } catch (e: Exception) {
                Log.e(TAG, "releaseSurface on GL thread failed", e)
            }
            try {
                // 释放渲染器资源
                rend?.release()
            } catch (e: Exception) {
                Log.e(TAG, "renderer.release failed", e)
            } finally {
                // 无论成功与否都减少计数
                latch.countDown()
            }
        }

        // 等待最多 500ms 让 GL 线程完成资源释放
        try {
            if (!latch.await(500, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "GL teardown timed out")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Fragment 暂停时的清理工作
     */
    override fun onPause() {
        // 先释放 GL 资源
        teardownGlResourcesBlocking()
        // 停止 GLSurfaceView 渲染
        if (::glSurfaceView.isInitialized) {
            glSurfaceView.onPause()
        }
        super.onPause()
    }

    /**
     * 视图销毁时的最终清理
     */
    override fun onDestroyView() {
        teardownGlResourcesBlocking()
        super.onDestroyView()
    }

    /**
     * 配置 GLSurfaceView 的基本属性
     * 设置 OpenGL ES 版本、渲染器和渲染模式
     */
    private fun setupGLSurfaceView() {
        // 设置使用 OpenGL ES 2.0
        glSurfaceView.setEGLContextClientVersion(2)

        // 创建并设置自定义渲染器
        renderer = SurfaceShareRenderer()
        glSurfaceView.setRenderer(renderer)

        // 设置为按需渲染模式（只有在请求时才重新绘制）
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
    }

    /**
     * 启动 Surface 共享功能
     * 初始化相机配置并开始接收视频流数据
     */
    private fun startSurfaceShare() {
        // 检查 Glass SDK 是否准备就绪
        if (!GlassSdk.isReady()) {
            Log.e(TAG, "GlassSdk not ready")
            return
        }

        // 初始相机配置：1920x1080 分辨率，15fps，禁用电子防抖，缩放级别 1
        val initialConfig = CameraShareConfig(
            previewWidth = 1920,
            previewHeight = 1080,
            previewTargetFps = 15,
            enableVideoStabilization = false,
            zoomLevel = 1,
        )

        // 创建并初始化相机共享助手
        surfaceHelper = CameraShareHelper().apply {
            initSurfaceWithConfig(initialConfig, object : CameraShareHelper.SurfaceCallback {
                /**
                 * 相机打开回调
                 * @param width 相机预览宽度
                 * @param height 相机预览高度
                 */
                override fun onCameraOpened(width: Int, height: Int) {
                    Log.d(TAG, "Surface camera opened: ${width}x${height}")
                    activity?.runOnUiThread {
                        tvInfo.text = "已连接: ${width}x${height}\n配置: 1280x720@15fps, EIS=OFF, zoom=1\n15秒后切换配置..."
                    }

                    // 安排 15 秒后的配置切换
                    scheduleConfigSwitch()
                }

                /**
                 * 新帧可用回调
                 * 通知 GLSurfaceView 进行重绘
                 */
                override fun onFrameAvailable() {
                    glSurfaceView.requestRender()
                }

                /**
                 * 相机关闭回调
                 */
                override fun onCameraClosed() {
                    Log.d(TAG, "Surface camera closed")
                }

                /**
                 * 错误回调
                 * @param code 错误码
                 * @param msg 错误消息
                 */
                override fun onError(code: Int, msg: String) {
                    Log.e(TAG, "Surface error: code=$code, msg=$msg")
                    activity?.runOnUiThread {
                        tvInfo.text = "错误: $code, $msg"
                    }
                }

                /**
                 * 配置变更回调
                 * 当相机配置动态调整时触发
                 * @param width 新的预览宽度
                 * @param height 新的预览高度
                 * @param appliedPreviewFps 应用的实际帧率
                 * @param videoStabilizationEnabled 视频防抖是否启用
                 */
                override fun onSurfaceShareConfigChanged(
                    width: Int,
                    height: Int,
                    appliedPreviewFps: Int,
                    videoStabilizationEnabled: Boolean
                ) {
                    Log.d(TAG, "Config changed: ${width}x${height}, fps=$appliedPreviewFps, eis=$videoStabilizationEnabled")
                    configSwitched = true
                    activity?.runOnUiThread {
                        tvInfo.text = "已连接: ${width}x${height}\n配置: 1920x1080@24fps, EIS=ON, zoom=2\n配置已切换！"
                    }
                }
            })
        }
    }

    /**
     * 安排配置切换任务
     * 在指定延迟后将相机配置从低分辨率切换到高分辨率
     */
    private fun scheduleConfigSwitch() {
        // 移除之前可能存在的切换任务
        configSwitchRunnable?.let { mainHandler.removeCallbacks(it) }

        // 创建配置切换任务
        val switchRunnable = Runnable {
            // 检查 Fragment 是否仍附加到 Activity 且视图存在，同时确认尚未切换过配置
            if (!isAdded || view == null || configSwitched) return@Runnable

            Log.d(TAG, "Switching to high-res config (on GL thread)...")

            // 高分辨率配置：1920x1080，24fps，启用电子防抖，缩放级别 1
            val highResConfig = CameraShareConfig(
                previewWidth = 1920,
                previewHeight = 1080,
                previewTargetFps = 24,
                enableVideoStabilization = true,
                zoomLevel = 1,
            )

            // 确保 GLSurfaceView 已初始化
            if (!::glSurfaceView.isInitialized) return@Runnable

            // 在 GL 渲染线程中执行配置切换
            glSurfaceView.queueEvent {
                // 再次检查状态以防万一
                if (!isAdded || configSwitched) return@queueEvent

                try {
                    // 释放当前的表面资源
                    surfaceHelper?.releaseSurface()
                } catch (e: Exception) {
                    Log.e(TAG, "releaseSurface before config switch: ${e.message}")
                }

                // 创建新的相机共享助手并应用高分辨率配置
                surfaceHelper = CameraShareHelper().apply {
                    initSurfaceWithConfig(highResConfig, object : CameraShareHelper.SurfaceCallback {
                        override fun onCameraOpened(width: Int, height: Int) {
                            Log.d(TAG, "High-res camera opened: ${width}x${height}")
                        }

                        override fun onFrameAvailable() {
                            glSurfaceView.requestRender()
                        }

                        override fun onCameraClosed() {
                            Log.d(TAG, "High-res camera closed")
                        }

                        override fun onError(code: Int, msg: String) {
                            Log.e(TAG, "High-res error: code=$code, msg=$msg")
                        }

                        override fun onSurfaceShareConfigChanged(
                            width: Int,
                            height: Int,
                            appliedPreviewFps: Int,
                            videoStabilizationEnabled: Boolean,
                        ) {
                            Log.d(
                                TAG,
                                "High-res config changed: ${width}x${height}, fps=$appliedPreviewFps, eis=$videoStabilizationEnabled",
                            )
                            configSwitched = true
                            activity?.runOnUiThread {
                                tvInfo.text =
                                    "已连接: ${width}x${height}\n配置: 1920x1080@24fps, EIS=ON, zoom=2\n配置已切换！"
                            }
                        }
                    })
                }
                // 请求一次渲染以显示新配置的画面
                glSurfaceView.requestRender()
            }
        }

        // 保存任务引用并延迟执行
        configSwitchRunnable = switchRunnable
        mainHandler.postDelayed(switchRunnable, CONFIG_SWITCH_DELAY_MS)
    }

    /**
     * 内部类：Surface 共享渲染器
     * 负责 OpenGL ES 着色器程序管理、顶点缓冲区处理和每帧渲染逻辑
     */
    private inner class SurfaceShareRenderer : GLSurfaceView.Renderer {

        // OpenGL 着色器程序 ID
        private var program = 0

        // 顶点位置属性的句柄
        private var positionHandle = 0

        // 纹理坐标属性的句柄
        private var texCoordHandle = 0

        // 纹理采样器的句柄
        private var textureHandle = 0

        // 变换矩阵 uniform 的句柄
        private var matrixHandle = 0

        // 顶点坐标数据缓冲区
        private lateinit var vertexBuffer: FloatBuffer

        // 纹理坐标数据缓冲区
        private lateinit var texCoordBuffer: FloatBuffer

        // 定义一个覆盖整个视口的矩形（两个三角形组成）
        private val vertexData = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

        // 对应的纹理坐标（从 0,0 到 1,1）
        private val texCoordData = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        // 顶点着色器代码
        // 负责处理顶点位置和纹理坐标的变换
        private val vertexShaderCode = """
            attribute vec4 aPosition;      // 输入顶点位置
            attribute vec2 aTexCoord;      // 输入纹理坐标
            uniform mat4 uMatrix;          // 变换矩阵（用于旋转/翻转等）
            varying vec2 vTexCoord;        // 输出到片段着色器的纹理坐标
            void main() {
                gl_Position = aPosition;   // 直接设置裁剪空间位置
                vTexCoord = (uMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;  // 应用变换矩阵
            }
        """.trimIndent()

        // 片段着色器代码
        // 使用 OES_EGL_image_external 扩展来渲染外部纹理（相机数据）
        private val fragmentShaderCode = """
            #extension GL_OES_EGL_image_external : require  // 启用外部纹理支持
            precision highp float;                           // 高精度浮点运算
            varying vec2 vTexCoord;                          // 从顶点着色器传入的纹理坐标
            uniform samplerExternalOES uTexture;             // 外部纹理采样器
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);  // 采样纹理颜色
            }
        """.trimIndent()

        /**
         * 表面创建时调用
         * 初始化 OpenGL 环境、着色器程序和缓冲区
         * @param gl GL10 接口（未使用）
         * @param config EGL 配置信息
         */
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            // 设置清屏颜色为黑色
            GLES20.glClearColor(0f, 0f, 0f, 1f)

            // 初始化着色器程序
            initShaderProgram()

            // 初始化顶点缓冲区
            initBuffers()

            // 启动 Surface 共享功能
            startSurfaceShare()
        }

        /**
         * 表面尺寸变化时调用
         * 设置 OpenGL 视口大小
         * @param gl GL10 接口（未使用）
         * @param width 新宽度
         * @param height 新高度
         */
        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }

        /**
         * 每帧绘制回调
         * 执行实际的 OpenGL 渲染操作
         * @param gl GL10 接口（未使用）
         */
        override fun onDrawFrame(gl: GL10?) {
            // 清除颜色缓冲区
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // 获取相机共享助手实例
            val helper = surfaceHelper ?: return

            // 获取相机纹理 ID
            val texId = helper.getTextureId()
            if (texId == -1) return  // 如果纹理无效则跳过

            // 更新纹理内容（将最新的相机帧绑定到纹理）
            helper.updateTexture()

            // 激活着色器程序
            GLES20.glUseProgram(program)

            // 设置顶点位置数据
            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glEnableVertexAttribArray(positionHandle)

            // 设置纹理坐标数据
            texCoordBuffer.position(0)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
            GLES20.glEnableVertexAttribArray(texCoordHandle)

            // 设置变换矩阵（用于校正相机方向）
            GLES20.glUniformMatrix4fv(matrixHandle, 1, false, helper.getTransformMatrix(), 0)

            // 绑定外部纹理到纹理单元 0
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniform1i(textureHandle, 0)

            // 绘制四边形（由两个三角形组成的三角形带）
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // 清理状态
            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

            // 更新帧率显示
            updateFps()
        }

        /**
         * 更新帧率显示
         * 每秒计算一次实时帧率并更新 UI
         */
        private fun updateFps() {
            frameCount++
            val now = System.currentTimeMillis()
            if (lastFpsTime == 0L) lastFpsTime = now
            val elapsed = now - lastFpsTime
            if (elapsed >= 1000) {
                // 计算过去一秒内的平均帧率
                val fps = frameCount * 1000f / elapsed
                val helper = surfaceHelper
                activity?.runOnUiThread {
                    tvInfo.text = "已连接: ${helper?.getCameraWidth()}x${helper?.getCameraHeight()}\n" +
                            "配置: 1280x720@15fps, EIS=OFF, zoom=1\n" +
                            "实时帧率: %.1f fps".format(fps)
                }
                // 重置计数器
                frameCount = 0
                lastFpsTime = now
            }
        }

        /**
         * 初始化着色器程序
         * 编译顶点和片段着色器，链接成完整程序，并获取各变量的句柄
         */
        private fun initShaderProgram() {
            // 加载并编译顶点着色器
            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)

            // 加载并编译片段着色器
            val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

            // 创建程序并附加着色器
            program = GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vertexShader)
                GLES20.glAttachShader(it, fragmentShader)
                GLES20.glLinkProgram(it)
            }

            // 获取各个属性和 uniform 的位置句柄
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            textureHandle = GLES20.glGetUniformLocation(program, "uTexture")
            matrixHandle = GLES20.glGetUniformLocation(program, "uMatrix")
        }

        /**
         * 初始化顶点缓冲区
         * 将顶点和纹理坐标数据转换为原生字节顺序的 FloatBuffer
         */
        private fun initBuffers() {
            // 创建顶点坐标缓冲区
            vertexBuffer = ByteBuffer.allocateDirect(vertexData.size * 4)
                .order(ByteOrder.nativeOrder())  // 使用系统原生字节序以提高性能
                .asFloatBuffer()
                .put(vertexData)

            // 创建纹理坐标缓冲区
            texCoordBuffer = ByteBuffer.allocateDirect(texCoordData.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(texCoordData)
        }

        /**
         * 加载并编译单个着色器
         * @param type 着色器类型（GL_VERTEX_SHADER 或 GL_FRAGMENT_SHADER）
         * @param shaderCode 着色器源代码
         * @return 编译后的着色器 ID
         */
        private fun loadShader(type: Int, shaderCode: String): Int {
            return GLES20.glCreateShader(type).also { shader ->
                GLES20.glShaderSource(shader, shaderCode)
                GLES20.glCompileShader(shader)
            }
        }

        /**
         * 释放渲染器资源
         * 删除着色器程序以避免内存泄漏
         */
        fun release() {
            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
        }
    }
}
