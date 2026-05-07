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
 * 仅渲染相机画面到 GLSurfaceView
 */
class SurfaceShareFragment : Fragment() {

    companion object {
        private const val TAG = "SurfaceShareFragment"
        private const val CONFIG_SWITCH_DELAY_MS = 15_000L

        fun newInstance(): SurfaceShareFragment {
            return SurfaceShareFragment()
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var configSwitchRunnable: Runnable? = null

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var tvInfo: TextView
    private var surfaceHelper: CameraShareHelper? = null
    private var renderer: SurfaceShareRenderer? = null
    private var frameCount = 0L
    private var lastFpsTime = 0L
    private var configSwitched = false // 标记是否已切换配置

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_surface_share, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        glSurfaceView = view.findViewById(R.id.gl_surface_view)
        tvInfo = view.findViewById(R.id.tv_info)
        
        setupGLSurfaceView()
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
    }

    /**
     * 必须在 [GLSurfaceView.onPause] **之前**、且渲染线程仍在处理队列时执行完 GL 侧释放。
     * 若在 [onDestroyView] 才异步 [queueEvent] 又立刻 [onPause]，易出现上下文销毁与 BufferQueue/fence 交错，
     * 系统 HWUI [RenderThread] 上可能触发 fdsan（duplicate close）。
     */
    private fun teardownGlResourcesBlocking() {
        configSwitchRunnable?.let { mainHandler.removeCallbacks(it) }
        configSwitchRunnable = null
        if (!::glSurfaceView.isInitialized) return

        val helper = surfaceHelper
        surfaceHelper = null
        val rend = renderer

        val latch = CountDownLatch(1)
        glSurfaceView.queueEvent {
            try {
                helper?.releaseSurface()
            } catch (e: Exception) {
                Log.e(TAG, "releaseSurface on GL thread failed", e)
            }
            try {
                rend?.release()
            } catch (e: Exception) {
                Log.e(TAG, "renderer.release failed", e)
            } finally {
                latch.countDown()
            }
        }
        try {
            if (!latch.await(500, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "GL teardown timed out")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun onPause() {
        teardownGlResourcesBlocking()
        if (::glSurfaceView.isInitialized) {
            glSurfaceView.onPause()
        }
        super.onPause()
    }

    override fun onDestroyView() {
        teardownGlResourcesBlocking()
        super.onDestroyView()
    }

    private fun setupGLSurfaceView() {
        glSurfaceView.setEGLContextClientVersion(2)
        renderer = SurfaceShareRenderer()
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
    }

    private fun startSurfaceShare() {
        if (!GlassSdk.isReady()) {
            Log.e(TAG, "GlassSdk not ready")
            return
        }

        // 初始配置：1280x720@15fps, EIS=OFF, zoom=1
        val initialConfig = CameraShareConfig(
            previewWidth = 1280,
            previewHeight = 720,
            previewTargetFps = 15,
            enableVideoStabilization = false,
            zoomLevel = 1,
        )

        surfaceHelper = CameraShareHelper().apply {
            initSurfaceWithConfig(initialConfig, object : CameraShareHelper.SurfaceCallback {
                override fun onCameraOpened(width: Int, height: Int) {
                    Log.d(TAG, "Surface camera opened: ${width}x${height}")
                    activity?.runOnUiThread {
                        tvInfo.text = "已连接: ${width}x${height}\n配置: 1280x720@15fps, EIS=OFF, zoom=1\n15秒后切换配置..."
                    }
                    
                    // 15秒后切换配置
                    scheduleConfigSwitch()
                }

                override fun onFrameAvailable() {
                    glSurfaceView.requestRender()
                }

                override fun onCameraClosed() {
                    Log.d(TAG, "Surface camera closed")
                }

                override fun onError(code: Int, msg: String) {
                    Log.e(TAG, "Surface error: code=$code, msg=$msg")
                    activity?.runOnUiThread {
                        tvInfo.text = "错误: $code, $msg"
                    }
                }
                
                override fun onSurfaceShareConfigChanged(width: Int, height: Int, appliedPreviewFps: Int, videoStabilizationEnabled: Boolean) {
                    Log.d(TAG, "Config changed: ${width}x${height}, fps=$appliedPreviewFps, eis=$videoStabilizationEnabled")
                    configSwitched = true
                    activity?.runOnUiThread {
                        tvInfo.text = "已连接: ${width}x${height}\n配置: 1920x1080@24fps, EIS=ON, zoom=2\n配置已切换！"
                    }
                }
            })
        }
    }
    
    private fun scheduleConfigSwitch() {
        configSwitchRunnable?.let { mainHandler.removeCallbacks(it) }
        val switchRunnable = Runnable {
            if (!isAdded || view == null || configSwitched) return@Runnable
            Log.d(TAG, "Switching to high-res config (on GL thread)...")
            val highResConfig = CameraShareConfig(
                previewWidth = 1920,
                previewHeight = 1080,
                previewTargetFps = 24,
                enableVideoStabilization = true,
                zoomLevel = 2,
            )
            if (!::glSurfaceView.isInitialized) return@Runnable
            glSurfaceView.queueEvent {
                if (!isAdded || configSwitched) return@queueEvent
                try {
                    surfaceHelper?.releaseSurface()
                } catch (e: Exception) {
                    Log.e(TAG, "releaseSurface before config switch: ${e.message}")
                }
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
                glSurfaceView.requestRender()
            }
        }
        configSwitchRunnable = switchRunnable
        mainHandler.postDelayed(switchRunnable, CONFIG_SWITCH_DELAY_MS)
    }

    private inner class SurfaceShareRenderer : GLSurfaceView.Renderer {

        private var program = 0
        private var positionHandle = 0
        private var texCoordHandle = 0
        private var textureHandle = 0
        private var matrixHandle = 0
        private lateinit var vertexBuffer: FloatBuffer
        private lateinit var texCoordBuffer: FloatBuffer

        private val vertexData = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val texCoordData = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        private val vertexShaderCode = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        private val fragmentShaderCode = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            initShaderProgram()
            initBuffers()
            startSurfaceShare()
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val helper = surfaceHelper ?: return
            val texId = helper.getTextureId()
            if (texId == -1) return

            helper.updateTexture()

            GLES20.glUseProgram(program)

            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glEnableVertexAttribArray(positionHandle)

            texCoordBuffer.position(0)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
            GLES20.glEnableVertexAttribArray(texCoordHandle)

            GLES20.glUniformMatrix4fv(matrixHandle, 1, false, helper.getTransformMatrix(), 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniform1i(textureHandle, 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

            updateFps()
        }

        private fun updateFps() {
            frameCount++
            val now = System.currentTimeMillis()
            if (lastFpsTime == 0L) lastFpsTime = now
            val elapsed = now - lastFpsTime
            if (elapsed >= 1000) {
                val fps = frameCount * 1000f / elapsed
                val helper = surfaceHelper
                activity?.runOnUiThread {
                    tvInfo.text = "已连接: ${helper?.getCameraWidth()}x${helper?.getCameraHeight()}\n" +
                        "配置: 1280x720@15fps, EIS=OFF, zoom=1\n" +
                        "实时帧率: %.1f fps".format(fps)
                }
                frameCount = 0
                lastFpsTime = now
            }
        }

        private fun initShaderProgram() {
            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
            val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
            program = GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vertexShader)
                GLES20.glAttachShader(it, fragmentShader)
                GLES20.glLinkProgram(it)
            }
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            textureHandle = GLES20.glGetUniformLocation(program, "uTexture")
            matrixHandle = GLES20.glGetUniformLocation(program, "uMatrix")
        }

        private fun initBuffers() {
            vertexBuffer = ByteBuffer.allocateDirect(vertexData.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(vertexData)
            texCoordBuffer = ByteBuffer.allocateDirect(texCoordData.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(texCoordData)
        }

        private fun loadShader(type: Int, shaderCode: String): Int {
            return GLES20.glCreateShader(type).also { shader ->
                GLES20.glShaderSource(shader, shaderCode)
                GLES20.glCompileShader(shader)
            }
        }

        fun release() {
            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
        }
    }
}
