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
        private const val FIRST_SWITCH_DELAY_MS = 15_000L   // 第一次切换：15秒
        private const val SECOND_SWITCH_DELAY_MS = 15_000L  // 第二次切换：再过15秒

        fun newInstance(): SurfaceShareFragment {
            return SurfaceShareFragment()
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var firstSwitchRunnable: Runnable? = null
    private var secondSwitchRunnable: Runnable? = null

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var tvInfo: TextView
    private val surfaceHelper = CameraShareHelper()
    private var renderer: SurfaceShareRenderer? = null
    private var frameCount = 0L
    private var lastFpsTime = 0L
    private var currentConfigText = "" // 当前配置文本
    private var currentZoomLevel = 1 // 当前 zoom 级别
    private var configSwitchInFlight = false

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
        frameCount = 0
        lastFpsTime = 0L
        glSurfaceView.onResume()
        // 确保渲染线程恢复后 Surface 共享被正确拉起。
        // onSurfaceCreated 在 EGL 重建时触发，但 teardownGlResourcesBlocking 只销毁了
        // GL 资源（shader/texture）而没有破坏 EGL Surface，onResume 恢复后可能不会
        // 重新触发 onSurfaceCreated → startSurfaceShare()。
        // 此外，即使 EGL 上下文被保留（未重建），shader program 已被 rend.release()
        // 删除，需要通过 queueEvent 一并恢复 shader + 纹理 + Surface 共享。
        glSurfaceView.queueEvent {
            if (!surfaceHelper.isSurfaceActive()) {
                renderer?.initShaderProgram()
                renderer?.initBuffers()
                startSurfaceShare()
            }
        }
    }

    /**
     * 必须在 [GLSurfaceView.onPause] **之前**、且渲染线程仍在处理队列时执行完 GL 侧释放。
     * 若在 [onDestroyView] 才异步 [queueEvent] 又立刻 [onPause]，易出现上下文销毁与 BufferQueue/fence 交错，
     * 系统 HWUI [RenderThread] 上可能触发 fdsan（duplicate close）。
     */
    private fun teardownGlResourcesBlocking() {
        firstSwitchRunnable?.let { mainHandler.removeCallbacks(it) }
        secondSwitchRunnable?.let { mainHandler.removeCallbacks(it) }
        firstSwitchRunnable = null
        secondSwitchRunnable = null
        if (!::glSurfaceView.isInitialized) return

        val rend = renderer

        Log.d(TAG, "teardownGlResourcesBlocking: releasing resources, surfaceActive=${surfaceHelper.isSurfaceActive()}")

        val latch = CountDownLatch(1)
        glSurfaceView.queueEvent {
            try {
                if (surfaceHelper.isSurfaceActive()) {
                    surfaceHelper.releaseSurface()
                }
                Log.d(TAG, "teardownGlResourcesBlocking: releaseSurface completed on GL thread")
            } catch (e: Exception) {
                Log.e(TAG, "releaseSurface on GL thread failed", e)
            }
            try {
                rend?.release()
                Log.d(TAG, "teardownGlResourcesBlocking: renderer.release completed")
            } catch (e: Exception) {
                Log.e(TAG, "renderer.release failed", e)
            } finally {
                latch.countDown()
            }
        }
        try {
            if (!latch.await(500, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "GL teardown timed out - potential resource leak!")
            } else {
                Log.d(TAG, "teardownGlResourcesBlocking: all resources released successfully")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.e(TAG, "teardownGlResourcesBlocking interrupted", e)
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

        if (surfaceHelper.isSurfaceActive()) {
            Log.w(TAG, "startSurfaceShare: already active, skip duplicate start")
            return
        }

        val initialConfig = CameraShareConfig(
            previewWidth = 1280,
            previewHeight = 720,
            previewTargetFps = 15,
            enableVideoStabilization = false,
            zoomLevel = 1,
        )

        currentConfigText =
            "1280x720@15fps, EIS=OFF, zoom=1\n演示提示: 15秒后自动切换到1920x1080@15fps, zoom=2"
        currentZoomLevel = 1

        Log.d(TAG, "Starting surface share: ${initialConfig.previewWidth}x${initialConfig.previewHeight}@${initialConfig.previewTargetFps}fps")
        surfaceHelper.initSurfaceWithConfig(initialConfig, createSurfaceCallback(scheduleConfigSwitchOnOpen = true))
    }

    private fun createSurfaceCallback(scheduleConfigSwitchOnOpen: Boolean = false): CameraShareHelper.SurfaceCallback {
        return object : CameraShareHelper.SurfaceCallback {
            override fun onCameraOpened(width: Int, height: Int) {
                Log.d(TAG, "Surface camera opened: ${width}x${height}")
                notifyRendererCameraSizeChanged(width, height)
                activity?.runOnUiThread {
                    updateInfoDisplay(width, height)
                }
                if (scheduleConfigSwitchOnOpen) {
                    scheduleFirstConfigSwitch()
                }
            }

            override fun onFrameAvailable() {
                frameCount++
                val now = System.currentTimeMillis()
                if (lastFpsTime == 0L) lastFpsTime = now
                val elapsed = now - lastFpsTime
                if (elapsed >= 1000) {
                    val w = surfaceHelper.getCameraWidth()
                    val h = surfaceHelper.getCameraHeight()
                    activity?.runOnUiThread {
                        updateInfoDisplay(w, h)
                    }
                    frameCount = 0
                    lastFpsTime = now
                }
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

            override fun onSurfaceShareConfigChanged(
                width: Int,
                height: Int,
                appliedPreviewFps: Int,
                videoStabilizationEnabled: Boolean,
            ) {
                Log.d(TAG, "Config changed: ${width}x${height}, fps=$appliedPreviewFps, eis=$videoStabilizationEnabled")
                val changedByLocalSwitch = configSwitchInFlight
                configSwitchInFlight = false
                if (!changedByLocalSwitch) {
                    cancelScheduledConfigSwitches()
                }
                notifyRendererCameraSizeChanged(width, height)
                val (displayWidth, displayHeight) = toDisplayResolution(width, height)
                currentConfigText = "${displayWidth}x${displayHeight}@${appliedPreviewFps}fps, EIS=${if (videoStabilizationEnabled) "ON" else "OFF"}, zoom=$currentZoomLevel"
                activity?.runOnUiThread {
                    updateInfoDisplay(width, height)
                }
            }
        }
    }

    private fun toDisplayResolution(cameraWidth: Int, cameraHeight: Int): Pair<Int, Int> {
        return cameraHeight to cameraWidth
    }

    private fun notifyRendererCameraSizeChanged(width: Int, height: Int) {
        if (!::glSurfaceView.isInitialized) return
        glSurfaceView.queueEvent {
            renderer?.onCameraSizeChanged(width, height)
            glSurfaceView.requestRender()
        }
    }

    private fun cancelScheduledConfigSwitches() {
        firstSwitchRunnable?.let { mainHandler.removeCallbacks(it) }
        secondSwitchRunnable?.let { mainHandler.removeCallbacks(it) }
        firstSwitchRunnable = null
        secondSwitchRunnable = null
    }
    
    /**
     * 更新信息显示
     */
    private fun updateInfoDisplay(width: Int, height: Int) {
        val (displayWidth, displayHeight) = toDisplayResolution(width, height)
        val fpsText = if (lastFpsTime > 0) {
            val elapsed = System.currentTimeMillis() - lastFpsTime
            if (elapsed >= 1000 && frameCount > 0) {
                "\n实时帧率: %.1f fps".format(frameCount * 1000f / elapsed)
            } else {
                ""
            }
        } else {
            ""
        }
        tvInfo.text = "已连接: ${displayWidth}x${displayHeight}\n配置: $currentConfigText$fpsText"
    }
    
    /**
     * 第一次配置切换：15秒后切换到 1080P@15fps, zoom=2
     */
    private fun scheduleFirstConfigSwitch() {
        firstSwitchRunnable?.let { mainHandler.removeCallbacks(it) }
        val switchRunnable = Runnable {
            if (!isAdded || view == null) return@Runnable
            Log.d(TAG, "First config switch: 1080P@15fps, zoom=2")
            val config = CameraShareConfig(
                previewWidth = 1920,
                previewHeight = 1080,
                previewTargetFps = 15,
                enableVideoStabilization = false,
                zoomLevel = 2,
            )
            currentConfigText =
                "1920x1080@15fps, EIS=OFF, zoom=2\n演示提示: 已自动切换，15秒后切换到24fps, zoom=1"
            currentZoomLevel = 2
            switchToNewConfig(config)
            
            // 再过15秒进行第二次切换
            scheduleSecondConfigSwitch()
        }
        firstSwitchRunnable = switchRunnable
        mainHandler.postDelayed(switchRunnable, FIRST_SWITCH_DELAY_MS)
    }
    
    /**
     * 第二次配置切换：再过15秒后切换到 1080P@24fps, zoom=1
     */
    private fun scheduleSecondConfigSwitch() {
        secondSwitchRunnable?.let { mainHandler.removeCallbacks(it) }
        val switchRunnable = Runnable {
            if (!isAdded || view == null) return@Runnable
            Log.d(TAG, "Second config switch: 1080P@24fps, zoom=1")
            val config = CameraShareConfig(
                previewWidth = 1920,
                previewHeight = 1080,
                previewTargetFps = 24,
                enableVideoStabilization = false,
                zoomLevel = 1,
            )
            currentConfigText =
                "1920x1080@24fps, EIS=OFF, zoom=1\n演示提示: 自动切换演示完成"
            currentZoomLevel = 1
            switchToNewConfig(config)
        }
        secondSwitchRunnable = switchRunnable
        mainHandler.postDelayed(switchRunnable, SECOND_SWITCH_DELAY_MS)
    }
    
    /**
     * 切换到新配置
     */
    private fun switchToNewConfig(config: CameraShareConfig) {
        if (!::glSurfaceView.isInitialized) return
        glSurfaceView.queueEvent {
            if (!isAdded) return@queueEvent
            Log.d(TAG, "switchToNewConfig: ${config.previewWidth}x${config.previewHeight}@${config.previewTargetFps}fps, zoom=${config.zoomLevel}")
            configSwitchInFlight = true
            surfaceHelper.restartSurfaceWithConfig(config, createSurfaceCallback())
            glSurfaceView.requestRender()
        }
    }

    private inner class SurfaceShareRenderer : GLSurfaceView.Renderer {

        private var program = 0
        private var positionHandle = 0
        private var texCoordHandle = 0
        private var textureHandle = 0
        private var matrixHandle = 0
        private lateinit var vertexBuffer: FloatBuffer
        private lateinit var texCoordBuffer: FloatBuffer
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var cameraWidth = 0
        private var cameraHeight = 0

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
            surfaceWidth = width
            surfaceHeight = height
            GLES20.glViewport(0, 0, width, height)
            updateCenterCropVertices()
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (!surfaceHelper.isSurfaceActive()) return
            val texId = surfaceHelper.getTextureId()
            if (texId == -1) return

            surfaceHelper.updateTexture()

            GLES20.glUseProgram(program)

            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glEnableVertexAttribArray(positionHandle)

            texCoordBuffer.position(0)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
            GLES20.glEnableVertexAttribArray(texCoordHandle)

            GLES20.glUniformMatrix4fv(matrixHandle, 1, false, surfaceHelper.getTransformMatrix(), 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniform1i(textureHandle, 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        }

        fun onCameraSizeChanged(width: Int, height: Int) {
            Log.d(TAG, "Renderer camera size changed: ${width}x${height}, surface=${surfaceWidth}x${surfaceHeight}")
            cameraWidth = width
            cameraHeight = height
            if (surfaceWidth > 0 && surfaceHeight > 0) {
                GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
                updateCenterCropVertices()
            }
        }

        /**
         * SDK 的 SurfaceTexture 变换矩阵负责相机纹理的 90° 旋转；这里按旋转后的
         * 宽高比缩放顶点做 FIT_CENTER，完整显示预览帧并避免非等比拉伸。
         */
        private fun updateCenterCropVertices() {
            if (!::vertexBuffer.isInitialized ||
                surfaceWidth <= 0 || surfaceHeight <= 0 ||
                cameraWidth <= 0 || cameraHeight <= 0
            ) {
                return
            }

            val rotatedCameraWidth = cameraHeight.toFloat()
            val rotatedCameraHeight = cameraWidth.toFloat()
            val cameraAspect = rotatedCameraWidth / rotatedCameraHeight
            val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()

            var scaleX = 1f
            var scaleY = 1f
            if (cameraAspect > surfaceAspect) {
                scaleY = surfaceAspect / cameraAspect
            } else {
                scaleX = cameraAspect / surfaceAspect
            }

            vertexData[0] = -scaleX
            vertexData[1] = -scaleY
            vertexData[2] = scaleX
            vertexData[3] = -scaleY
            vertexData[4] = -scaleX
            vertexData[5] = scaleY
            vertexData[6] = scaleX
            vertexData[7] = scaleY
            vertexBuffer.clear()
            vertexBuffer.put(vertexData)
            vertexBuffer.position(0)

            Log.d(
                TAG,
                "FIT_CENTER camera=${cameraWidth}x${cameraHeight}, " +
                    "rotated=${cameraHeight}x${cameraWidth}, surface=${surfaceWidth}x${surfaceHeight}, " +
                    "scale=${scaleX}x${scaleY}",
            )
        }

        fun initShaderProgram() {
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

        fun initBuffers() {
            vertexBuffer = ByteBuffer.allocateDirect(vertexData.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(vertexData)
            texCoordBuffer = ByteBuffer.allocateDirect(texCoordData.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(texCoordData)
            updateCenterCropVertices()
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
