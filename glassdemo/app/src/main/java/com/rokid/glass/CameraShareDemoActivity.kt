package com.rokid.glass

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.rokid.glass.base.BaseGlassActivity
import com.rokid.glass.utils.Nv21VideoRecorder
import com.rokid.glass.view.glsurface.view.BackgroundGLSurfaceView
import com.rokid.glesse.R
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.camera.CameraShareHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 跨进程Camera共享Demo
 *
 * 普通模式（叠加OFF）：
 *   上方：通过Surface纹理共享实时渲染camera画面
 *   下方：通过BackgroundGLSurfaceView渲染NV21帧数据
 *
 * 叠加模式（叠加ON）：
 *   隐藏两个预览视图，仅通过叠加NV21回调录制MP4
 *   显示录制状态面板（时长、帧率等信息）
 */
class CameraShareDemoActivity : BaseGlassActivity() {

    companion object {
        private const val TAG = "CameraShareDemo"
        const val EXTRA_ENABLE_MIX = "enable_mix"
    }

    private lateinit var cameraPreviewContainer: LinearLayout
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var nv21SurfaceView: BackgroundGLSurfaceView
    private lateinit var tvNv21Info: TextView

    private lateinit var tvMixLabel: TextView
    private lateinit var switchMix: Switch
    private lateinit var mixRecordPanel: LinearLayout
    private lateinit var tvRecordTime: TextView
    private lateinit var tvRecordInfo: TextView
    private lateinit var tvMixLabel2: TextView
    private lateinit var switchMix2: Switch
    private lateinit var overlayContainer: LinearLayout
    private lateinit var tvOverlayTime: TextView

    private var surfaceHelper: CameraShareHelper? = null
    private var nv21Helper: CameraShareHelper? = null
    private var renderer: CameraShareRenderer? = null
    private var nv21Recorder: Nv21VideoRecorder? = null
    private var frameCount = 0L
    private var lastFpsTime = 0L
    private var enableMix = false
    @Volatile
    private var glReady = false
    private var recordStartTime = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val overlayTimeUpdater = object : Runnable {
        override fun run() {
            tvOverlayTime.text = timeFormat.format(Date())
            mainHandler.postDelayed(this, 1000)
        }
    }

    private val recordTimeUpdater = object : Runnable {
        override fun run() {
            val elapsed = (System.currentTimeMillis() - recordStartTime) / 1000
            val min = elapsed / 60
            val sec = elapsed % 60
            tvRecordTime.text = "%02d:%02d".format(min, sec)
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_share_demo)

        enableMix = intent.getBooleanExtra(EXTRA_ENABLE_MIX, false)

        cameraPreviewContainer = findViewById(R.id.camera_preview_container)
        glSurfaceView = findViewById(R.id.gl_surface_view)
        nv21SurfaceView = findViewById(R.id.nv21_surface_view)
        tvNv21Info = findViewById(R.id.tv_nv21_info)
        tvMixLabel = findViewById(R.id.tv_mix_label)
        switchMix = findViewById(R.id.switch_mix)
        mixRecordPanel = findViewById(R.id.mix_record_panel)
        tvRecordTime = findViewById(R.id.tv_record_time)
        tvRecordInfo = findViewById(R.id.tv_record_info)
        tvMixLabel2 = findViewById(R.id.tv_mix_label2)
        switchMix2 = findViewById(R.id.switch_mix2)
        overlayContainer = findViewById(R.id.overlay_container)
        tvOverlayTime = findViewById(R.id.tv_overlay_time)

        switchMix.isChecked = enableMix
        switchMix2.isChecked = enableMix
        updateMixLabels()

        switchMix.setOnCheckedChangeListener { _, isChecked -> toggleMix(isChecked) }
        switchMix2.setOnCheckedChangeListener { _, isChecked -> toggleMix(isChecked) }

        setupGLSurfaceView()
    }

    private fun updateMixLabels() {
        val label = if (enableMix) "叠加: ON" else "叠加: OFF"
        tvMixLabel.text = label
        tvMixLabel2.text = label
    }

    private fun updateViewVisibility() {
        if (enableMix) {
            cameraPreviewContainer.visibility = View.GONE
            mixRecordPanel.visibility = View.VISIBLE
            overlayContainer.visibility = View.VISIBLE
            tvOverlayTime.text = timeFormat.format(Date())
            mainHandler.post(overlayTimeUpdater)
        } else {
            cameraPreviewContainer.visibility = View.VISIBLE
            mixRecordPanel.visibility = View.GONE
            overlayContainer.visibility = View.GONE
            mainHandler.removeCallbacks(overlayTimeUpdater)
            mainHandler.removeCallbacks(recordTimeUpdater)
        }
    }

    private fun toggleMix(newMix: Boolean) {
        if (enableMix == newMix) return
        enableMix = newMix

        switchMix.setOnCheckedChangeListener(null)
        switchMix2.setOnCheckedChangeListener(null)
        switchMix.isChecked = enableMix
        switchMix2.isChecked = enableMix
        switchMix.setOnCheckedChangeListener { _, isChecked -> toggleMix(isChecked) }
        switchMix2.setOnCheckedChangeListener { _, isChecked -> toggleMix(isChecked) }

        updateMixLabels()
        updateViewVisibility()

        if (!glReady && !enableMix) return

        if (enableMix) {
            stopNormalMode()
            startMixMode()
        } else {
            stopMixMode()
            glSurfaceView.queueEvent {
                renderer?.startSurfaceAndNv21()
            }
        }
        Log.d(TAG, "Mix toggled: enableMix=$enableMix")
    }

    /** 普通模式：Surface共享 + NV21导出（纯camera） */
    private fun startNormalMode() {
        if (!GlassSdk.isReady()) {
            Log.e(TAG, "GlassSdk not ready")
            return
        }

        Log.d(TAG,"--------------startNormalMode()----")

        surfaceHelper = CameraShareHelper().apply {
            initSurface(object : CameraShareHelper.SurfaceCallback {
                override fun onCameraOpened(width: Int, height: Int) {
                    Log.d(TAG, "Surface camera opened: ${width}x${height}")
                }

                override fun onFrameAvailable() {
                    glSurfaceView.requestRender()
                }

                override fun onCameraClosed() {
                    Log.d(TAG, "Surface camera closed")
                }

                override fun onError(code: Int, msg: String) {
                    Log.e(TAG, "Surface error: code=$code, msg=$msg")
                }
            })
        }

        nv21Helper = CameraShareHelper().apply {
            initNv21Export(enableMix = false, callback = object : CameraShareHelper.Nv21Callback {
                override fun onCameraOpened(width: Int, height: Int) {
                    Log.d(TAG, "NV21 camera opened: ${width}x${height}")
                }

                override fun onNv21Frame(nv21: ByteArray, width: Int, height: Int, timestamp: Long) {
                    nv21SurfaceView.setPreviewData(nv21, width, height)
                    updateNv21Info(width, height)
                }

                override fun onCameraClosed() {
                    Log.d(TAG, "NV21 camera closed")
                }

                override fun onError(code: Int, msg: String) {
                    Log.e(TAG, "NV21 error: code=$code, msg=$msg")
                }
            })
        }
    }

    private fun stopNormalMode() {
        surfaceHelper?.releaseSurface()
        surfaceHelper = null
        nv21Helper?.releaseNv21Export()
        nv21Helper = null
        frameCount = 0L
        lastFpsTime = 0L
    }

    /** 叠加模式：NV21导出（mix=true）+ 录制MP4 */
    private fun startMixMode() {
        if (!GlassSdk.isReady()) {
            Log.e(TAG, "GlassSdk not ready for mix mode")
            return
        }
        Log.e(TAG,"--------------startMixMode()----")
        nv21Helper = CameraShareHelper().apply {
            initNv21Export(enableMix = true, callback = object : CameraShareHelper.Nv21Callback {
                override fun onCameraOpened(width: Int, height: Int) {
                    Log.d(TAG, "Mix camera opened: ${width}x${height}")
                    nv21Recorder = Nv21VideoRecorder(this@CameraShareDemoActivity, width, height)
                    val path = nv21Recorder!!.start()
                    recordStartTime = System.currentTimeMillis()
                    runOnUiThread {
                        tvRecordInfo.text = "录制: ${width}x${height}  文件: $path"
                        mainHandler.post(recordTimeUpdater)
                    }
                }

                override fun onNv21Frame(nv21: ByteArray, width: Int, height: Int, timestamp: Long) {
                    nv21Recorder?.encodeFrame(nv21)
                    updateNv21Info(width, height)
                }

                override fun onCameraClosed() {
                    Log.d(TAG, "Mix camera closed")
                }

                override fun onError(code: Int, msg: String) {
                    Log.e(TAG, "Mix error: code=$code, msg=$msg")
                }
            })
        }
    }

    private fun stopMixMode() {
        mainHandler.removeCallbacks(recordTimeUpdater)
        nv21Recorder?.stop { path ->
            Log.d(TAG, "Recording saved: $path")
            runOnUiThread {
                tvRecordInfo.text = "已保存: $path"
            }
        }
        nv21Recorder = null
        nv21Helper?.releaseNv21Export()
        nv21Helper = null
        frameCount = 0L
        lastFpsTime = 0L
    }

    private fun setupGLSurfaceView() {
        glSurfaceView.setEGLContextClientVersion(2)
        renderer = CameraShareRenderer()
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(overlayTimeUpdater)
        mainHandler.removeCallbacks(recordTimeUpdater)

        if (enableMix) {
            stopMixMode()
        } else {
            stopNormalMode()
        }

        nv21SurfaceView.releasePreview()
        glSurfaceView.queueEvent {
            renderer?.release()
        }
        super.onDestroy()
    }

    private fun updateNv21Info(width: Int, height: Int) {
        frameCount++
        val now = System.currentTimeMillis()
        if (lastFpsTime == 0L) lastFpsTime = now
        val elapsed = now - lastFpsTime
        if (elapsed >= 1000) {
            val fps = frameCount * 1000f / elapsed
            val info = "${width}x${height}  NV21  %.1f fps".format(fps)
            runOnUiThread {
                tvNv21Info.text = info
                if (enableMix) {
                    tvRecordInfo.text = "录制中 ${width}x${height}  %.1f fps".format(fps)
                }
            }
            frameCount = 0
            lastFpsTime = now
        }
    }

    private inner class CameraShareRenderer : GLSurfaceView.Renderer {

        private var program = 0
        private var positionHandle = 0
        private var texCoordHandle = 0
        private var textureHandle = 0
        private var matrixHandle = 0
        private lateinit var vertexBuffer: FloatBuffer
        private lateinit var texCoordBuffer: FloatBuffer

        private val vertexData = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f,  1f,
            1f,  1f
        )

        private val texCoordData = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )

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
            glReady = true

            if (enableMix) {
                mainHandler.post {
                    updateViewVisibility()
                    startMixMode()
                }
            } else {
                startSurfaceAndNv21()
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val helper = surfaceHelper ?: return
            if (enableMix) return
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
        }

        fun startSurfaceAndNv21() {
            startNormalMode()
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