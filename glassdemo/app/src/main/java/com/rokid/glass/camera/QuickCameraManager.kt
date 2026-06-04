package com.rokid.glass.camera

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.rokid.glass.MyApplication
import com.rokid.glass.utils.DeviceUtil
import com.rokid.glass.utils.call
import com.rokid.security.glass3.open.sdk.uitls.log.L
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 快速相机管理器（低内存优化版）
 *
 * 核心优化策略：
 * 1. 预览时使用 SurfaceTexture（不占用 JPEG 缓冲）
 * 2. ImageReader 使用低分辨率（1280x720），节省内存
 * 3. 拍照时动态切换数据流到 ImageReader
 * 4. 及时释放资源，避免内存累积
 */
object QuickCameraManager {

    private val TAG = "QuickCameraManager"
    private val VIDEO_FRAME_RATE = 30
    private val VIDEO_BIT_RATE = 10_000_000

    // ===== 相机相关 =====
    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraId: String? = null

    // ===== 预览相关 =====
    private var surfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null

    // ===== 录像相关 =====
    private var mediaRecorder: MediaRecorder? = null
    private var videoFile: File? = null
    private var isRecording = false

    // ===== 线程相关 =====
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val mainScope = CoroutineScope(Dispatchers.Main)

    // ===== 状态标记 =====
    private var isInitialized = false
    private var isCameraClosed = true
    private var isSessionClosed = false
    private var isQuickCapture = false

    @Volatile
    var isProcessingCapture = MutableStateFlow(false)

    // ===== 回调 =====
    private var imgCallback: WeakReference<((File?) -> Unit)>? = null

    // ===== 会话锁 =====
    private val sessionLock = Any()

    /**
     * EIS（电子防抖）开关，默认关闭。
     * true 时录像请求会打开 Camera2 标准视频防抖，并使用平台侧实时 EIS 的 stream config mode。
     */
    private var eisOn: Boolean = false

    // CameraCaptureSession 的 operation mode。不开 EIS 时使用 0，也就是普通 session 模式。
    private var mStreamConfigOptMode = 0

    // Rokid/平台侧定义的实时 EIS session mode，需要在创建 SessionConfiguration 时传入。
    // 仅设置 CONTROL_VIDEO_STABILIZATION_MODE_ON 不一定会走到平台实时防抖链路。
    private val STREAM_CONFIG_MODE_QTIEIS_REALTIME = 0xF004


    /**
     * 初始化相机
     * @param size 预留参数（当前固定使用低分辨率）
     * @param quickCapture 快速拍照模式（拍照后自动释放相机）
     * @param onInitialized 初始化完成回调
     */
    @SuppressLint("MissingPermission")
    fun initialize(size: Size? = null, quickCapture: Boolean = false, onInitialized: (Boolean) -> Unit) {
        val weakCallback = WeakReference(onInitialized)

        releaseCamera()
        this.isQuickCapture = quickCapture

        if (isInitialized) {
            weakCallback.get()?.invoke(true)
            return
        }

        if (!hasCameraPermission()) {
            L.e(TAG, "没有相机权限")
            weakCallback.get()?.invoke(false)
            return
        }

        try {
            cameraManager = MyApplication.getContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraId = cameraManager?.cameraIdList?.firstOrNull()

            if (cameraId == null) {
                L.e(TAG, "没有可用相机")
                weakCallback.get()?.invoke(false)
                return
            }

            L.d(TAG, "相机ID: $cameraId")
            startBackgroundThread()

            cameraManager?.openCamera(
                cameraId!!,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        L.d(TAG, "->相机已打开")
                        setProcessingCaptureState(false)
                        isCameraClosed = false
                        cameraDevice = camera
                        isInitialized = true

                        // 创建低分辨率 ImageReader（1280x720）
                        setupImageReaderLowRes()

                        // 创建预览会话
                        createPreviewSession()

                        weakCallback.get()?.invoke(true)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        Log.e(TAG, "相机断开连接")
                        weakCallback.get()?.invoke(false)
                        releaseCamera()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.e(TAG, "相机打开错误: $error")
                        weakCallback.get()?.invoke(false)
                        releaseCamera()
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            releaseCamera()
            L.e(TAG, "初始化失败: ${e.message}", e)
            weakCallback.get()?.invoke(false)
        }
    }

    /**
     * 创建预览会话
     *
     * 关键设计：
     * - 会话包含两个 surface（previewSurface + imageReaderSurface）
     * - 但预览请求只发送到 previewSurface
     * - 这样 ImageReader 不会收到数据，不占用 JPEG 缓冲内存
     */
    private fun createPreviewSession() {
        setupPreviewSurface()
        val previewSurf = previewSurface ?: run {
            L.e(TAG, "previewSurface 为空")
            return
        }

        val imageReaderSurface = imageReader?.surface ?: run {
            L.e(TAG, "imageReaderSurface 为空")
            return
        }

        try {
            if (isCameraClosed) {
                return
            }

            Log.d(TAG, "创建预览会话")

            // 会话包含两个 surface，但预览时只用 previewSurface
            cameraDevice?.createCaptureSession(
                listOf(previewSurf, imageReaderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        synchronized(sessionLock) {
                            if (cameraDevice == null) {
                                setProcessingCaptureState(false)
                                Log.d(TAG, "相机设备已关闭")
                                return
                            }

                            captureSession = session
                            isSessionClosed = false

                            try {
                                val builder = cameraDevice!!.createCaptureRequest(
                                    CameraDevice.TEMPLATE_PREVIEW
                                ).apply {
                                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
                                    set(CaptureRequest.CONTROL_AE_LOCK, false)
                                    // ⭐ 只添加到 previewSurface，ImageReader 不会收到数据
                                    addTarget(previewSurf)
                                }

                                session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                                Log.d(TAG, "预览会话配置成功")
                            } catch (e: Exception) {
                                L.e(TAG, "设置预览失败", e)
                                setProcessingCaptureState(false)
                            }
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        L.e(TAG, "预览会话配置失败")
                        setProcessingCaptureState(false)
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            L.e(TAG, "创建预览会话失败", e)
            setProcessingCaptureState(false)
        }
    }

    /**
     * 拍照功能
     *
     * 流程：
     * 1. 停止预览请求
     * 2. 设置 ImageReader 监听器
     * 3. 创建拍照请求（发送到 imageReaderSurface）
     * 4. 执行单次捕获
     * 5. 收到数据后保存并恢复预览
     */
    /**
     * 拍照功能（YUV 格式）
     */
    fun takePicture(callback: (File?) -> Unit) {
        val weakCallback = WeakReference(callback)
        imgCallback = weakCallback

        if (isCameraDoing()) {
            Log.d(TAG, "takePicture 正在处理中")
            imgCallback?.get()?.invoke(null)
            return
        }

        if (!isInitialized || cameraDevice == null) {
            Log.d(TAG, "相机未初始化")
            imgCallback?.get()?.invoke(null)
            setProcessingCaptureState(false)
            return
        }

        setProcessingCaptureState(true)

        // ⭐ 步骤 1: 关闭旧的预览会话
        val oldSession = captureSession
        if (oldSession != null && !isSessionClosed) {
            Log.d(TAG, "关闭旧会话")
            try {
                oldSession.stopRepeating()
                oldSession.close()
            } catch (e: Exception) {
                L.e(TAG, "关闭旧会话异常", e)
            }
            captureSession = null
        }

        // ⭐ 步骤 2: 如果 ImageReader 不存在则创建
        if (imageReader == null) {
            setupImageReaderLowRes()
        }

        // 清理 ImageReader 中的旧帧
        imageReader?.let { reader ->
            while (true) {
                val oldImage = reader.acquireNextImage() ?: break
                oldImage.close()
            }
        }

        val imageReaderSurface = imageReader?.surface ?: run {
            L.e(TAG, "ImageReader surface 为空")
            imgCallback?.get()?.invoke(null)
            setProcessingCaptureState(false)
            return
        }

        var hasCaptured = false

        // ⭐ 步骤 3: 设置监听器
        imageReader?.setOnImageAvailableListener({ reader ->
            if (hasCaptured) {
                Log.d(TAG, "忽略重复回调")
                return@setOnImageAvailableListener
            }

            Log.d(TAG, "-------获取到拍照数据")
            hasCaptured = true

            val image = reader.acquireLatestImage() ?: run {
                Log.d(TAG, "拍照失败：无法获取图像")
                imgCallback?.get()?.invoke(null)
                setProcessingCaptureState(false)
                createPreviewSession()
                return@setOnImageAvailableListener
            }

            try {
                Log.d(TAG, "图片尺寸: ${image.width}x${image.height}")

                // ⭐ 将 YUV 数据转换为 JPEG 并保存
                saveYuvImageAsJpeg(image)

            } catch (e: Exception) {
                L.e(TAG, "保存图片失败", e)
                imgCallback?.get()?.invoke(null)
                setProcessingCaptureState(false)
                createPreviewSession()
            } finally {
                image.close()

                // ⭐ 步骤 4: 拍照完成后恢复预览
                if (isQuickCapture) {
                    releaseCamera()
                } else {
                    setProcessingCaptureState(false)
                    createPreviewSession()
                }
            }
        }, backgroundHandler)

        // ⭐ 步骤 5: 创建新的拍照会话
        try {
            Log.d(TAG, "创建拍照会话")
            cameraDevice?.createCaptureSession(
                listOf(imageReaderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.d(TAG, "拍照会话配置成功")
                        synchronized(sessionLock) {
                            if (cameraDevice == null) {
                                return
                            }
                            captureSession = session
                            isSessionClosed = false

                            try {
                                val captureBuilder = cameraDevice!!.createCaptureRequest(
                                    CameraDevice.TEMPLATE_STILL_CAPTURE
                                ).apply {
                                    addTarget(imageReaderSurface)
                                    set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation(90))
                                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                                }

                                DeviceUtil.setSystemProp("vendor.rkd.camera.sensormode", "5")

                                Log.d(TAG, "执行拍照捕获")
                                session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureStarted(
                                        session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        timestamp: Long,
                                        frameNumber: Long
                                    ) {
                                        Log.d(TAG, "拍照开始: frameNumber=$frameNumber")
                                    }

                                    override fun onCaptureCompleted(
                                        session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        result: android.hardware.camera2.TotalCaptureResult
                                    ) {
                                        Log.d(TAG, "拍照完成: frameNumber=${result.frameNumber}")
                                    }

                                    override fun onCaptureFailed(
                                        session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        failure: android.hardware.camera2.CaptureFailure
                                    ) {
                                        L.e(TAG, "拍照失败: reason=${failure.reason}")
                                        if (!hasCaptured) {
                                            hasCaptured = true
                                            imgCallback?.get()?.invoke(null)
                                            setProcessingCaptureState(false)
                                            createPreviewSession()
                                        }
                                    }
                                }, backgroundHandler)
                            } catch (e: Exception) {
                                L.e(TAG, "拍照异常", e)
                                if (!hasCaptured) {
                                    hasCaptured = true
                                    imgCallback?.get()?.invoke(null)
                                    setProcessingCaptureState(false)
                                    createPreviewSession()
                                }
                            }
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        L.e(TAG, "拍照会话配置失败")
                        imgCallback?.get()?.invoke(null)
                        setProcessingCaptureState(false)
                        createPreviewSession()
                    }
                },
                backgroundHandler
            )

        } catch (e: Exception) {
            L.e(TAG, "拍照异常", e)
            if (!hasCaptured) {
                imgCallback?.get()?.invoke(null)
                setProcessingCaptureState(false)
                createPreviewSession()
            }
        }
    }


    /**
     * 恢复预览
     */
    private fun resumePreview() {
        val session = captureSession ?: run {
            L.e(TAG, "恢复预览失败：会话为空")
            return
        }

        val previewSurf = previewSurface ?: run {
            L.e(TAG, "恢复预览失败：previewSurface 为空")
            return
        }

        if (isSessionClosed || cameraDevice == null) {
            Log.d(TAG, "恢复预览失败：会话已关闭或相机为空")
            return
        }

        try {
            Log.d(TAG, "恢复预览")
            val previewBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
                addTarget(previewSurf)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
                set(CaptureRequest.CONTROL_AE_LOCK, false)
            }

            previewBuilder?.let {
                session.setRepeatingRequest(it.build(), null, backgroundHandler)
            }
        } catch (e: Exception) {
            L.e(TAG, "恢复预览异常", e)
        }
    }

    /**
     * 开始录像
     * @param eisOn 电子防抖开关，默认关闭。
     */
    fun startRecording(isAudioMute: Boolean = false, eisOn: Boolean = false, callback: (File?) -> Unit) {
        val weakCallback = WeakReference(callback)
        this.eisOn = eisOn
        if (!isInitialized || cameraDevice == null || (!isAudioMute && !hasAudioPermission()) || isRecording) {
            weakCallback.get()?.invoke(null)
            return
        }

        try {
            closeCurrentSession()
            resetRecordingState()
            videoFile = createVideoFile()
            setupPreviewSurface()

            mediaRecorder = MediaRecorder().apply {
                if (!isAudioMute) {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                }
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                val size = getBestVideoSize() ?: Size(1280, 720)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                if (!isAudioMute) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                }
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                Log.d(TAG, "视频分辨率: width=${size.width},height=${size.height}")
                setVideoSize(size.width, size.height)
                setVideoFrameRate(VIDEO_FRAME_RATE)
                setVideoEncodingBitRate(VIDEO_BIT_RATE)
                setOutputFile(videoFile?.absolutePath)
                setOrientationHint(270)
                prepare()
            }

            DeviceUtil.setSystemProp("vendor.rkd.camera.sensormode", "5")

            val outputConfigurations = ArrayList<OutputConfiguration>()
            val recorderSurface = mediaRecorder!!.surface
            outputConfigurations.add(OutputConfiguration(previewSurface!!))
            outputConfigurations.add(OutputConfiguration(recorderSurface))

            val captureRequestBuilder: CaptureRequest.Builder? = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            captureRequestBuilder?.apply {
                addTarget(previewSurface!!)
                addTarget(recorderSurface)

                // Camera2 标准视频防抖开关，会随录像 repeating request 一起下发给相机。
                // eisOn=false 时显式关闭，避免沿用上一次 session 的防抖状态。
                val eisMode = if (eisOn) {
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                } else {
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                }
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, eisMode)
            }

            setProcessingCaptureState(true)

            val sessionStateCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    synchronized(sessionLock) {
                        if (cameraDevice == null) {
                            Log.d(TAG, "cameraDevice 已关闭")
                            resetRecordingState()
                            weakCallback.get()?.invoke(null)
                            setProcessingCaptureState(false)
                            return
                        }

                        try {
                            captureSession = session
                            captureRequestBuilder?.build()?.let {
                                session.setRepeatingRequest(it, null, backgroundHandler)
                            }
                            mediaRecorder?.start()
                            isRecording = true
                            weakCallback.get()?.invoke(videoFile)
                        } catch (e: Exception) {
                            L.e(TAG, "录像开始失败", e)
                            setProcessingCaptureState(false)
                            resetRecordingState()
                            weakCallback.get()?.invoke(null)
                        }
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    resetRecordingState()
                    setProcessingCaptureState(false)
                    weakCallback.get()?.invoke(null)
                }
            }

            // 开启 EIS 时使用平台侧实时防抖 operation mode 创建录像 session
            mStreamConfigOptMode = if (eisOn) STREAM_CONFIG_MODE_QTIEIS_REALTIME else 0
            val sessionConfig = SessionConfiguration(
                mStreamConfigOptMode,
                outputConfigurations,
                backgroundHandler!!::post,
                sessionStateCallback
            )
            // 将上面的录像请求参数作为 sessionParameters 传给 HAL，确保防抖等参数在建 session 阶段生效。
            sessionConfig.sessionParameters = captureRequestBuilder?.build()
            cameraDevice?.createCaptureSession(sessionConfig)

//            cameraDevice?.createCaptureSession(
//                listOf(previewSurface!!, recorderSurface),
//                object : CameraCaptureSession.StateCallback() {
//                    override fun onConfigured(session: CameraCaptureSession) {
//                        synchronized(sessionLock) {
//                            if (cameraDevice == null) {
//                                Log.d(TAG, "cameraDevice 已关闭")
//                                resetRecordingState()
//                                weakCallback.get()?.invoke(null)
//                                setProcessingCaptureState(false)
//                                return
//                            }
//
//                            try {
//                                captureSession = session
//                                mediaRecorder?.start()
//                                isRecording = true
//                                weakCallback.get()?.invoke(videoFile)
//                                Handler(backgroundHandler!!.looper).post {
//                                    try {
//                                        builder?.build()?.let {
//                                            synchronized(sessionLock) {
//                                                captureSession?.setRepeatingRequest(it, null, backgroundHandler)
//                                            }
//                                        }
//                                    } catch (e: IllegalStateException) {
//                                        setProcessingCaptureState(false)
//                                        L.e(TAG, "录像时 session 已关闭", e)
//                                    } catch (e: Exception) {
//                                        setProcessingCaptureState(false)
//                                        L.e(TAG, "录像 setRepeatingRequest 异常", e)
//                                    }
//                                }
//                            } catch (e: Exception) {
//                                L.e(TAG, "录像开始失败", e)
//                                setProcessingCaptureState(false)
//                                resetRecordingState()
//                                weakCallback.get()?.invoke(null)
//                            }
//                        }
//                    }
//
//                    override fun onConfigureFailed(session: CameraCaptureSession) {
//                        resetRecordingState()
//                        setProcessingCaptureState(false)
//                        weakCallback.get()?.invoke(null)
//                    }
//                },
//                backgroundHandler
//            )
        } catch (e: Exception) {
            e.printStackTrace()
            setProcessingCaptureState(false)
            resetRecordingState()
            weakCallback.get()?.invoke(null)
        }
    }

    private fun closeCurrentSession() {
        synchronized(sessionLock) {
            try {
                captureSession?.stopRepeating()
                captureSession?.abortCaptures()
                captureSession?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            captureSession = null
            isSessionClosed = true
        }
    }

    fun setProcessingCaptureState(state: Boolean) {
        isProcessingCapture.call(state)
    }

    /**
     * 停止录像
     */
    fun stopRecording(callback: (File?) -> Unit) {
        val weakCallback = WeakReference(callback)
        if (!isRecording) {
            weakCallback.get()?.invoke(null)
            return
        }

        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.d(TAG, "停止录像异常", e)
        }

        mediaRecorder?.release()
        mediaRecorder = null
        isRecording = false

        synchronized(sessionLock) {
            isSessionClosed = true
            captureSession?.close()
            captureSession = null
        }

        L.e(TAG, "stopRecording " + videoFile?.absolutePath)
        weakCallback.get()?.invoke(videoFile)
        setProcessingCaptureState(false)

        // 录像结束后重新创建预览会话
        createPreviewSession()
    }


    private fun setupImageReaderLowRes() {
        L.d(TAG, "setupImageReaderLowRes")
        val characteristics = cameraManager?.getCameraCharacteristics(cameraId!!)
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val outputSizes: Array<Size>? = map?.getOutputSizes(ImageFormat.YUV_420_888)

        // 优先使用 1280x720，最大 1920x1080
//        val size = outputSizes?.firstOrNull { it.width == 1800 && it.height == 2400 }
        val size = outputSizes?.firstOrNull { it.width == 1080 && it.height == 1920 }
            ?: Size(720, 1280)

        Log.d(TAG, "ImageReader 分辨率: ${size.width}x${size.height} (低内存模式)")

        // maxImages = 1，最小化缓冲
        imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 1)
    }


    /**
     * 设置预览 Surface
     */
    private fun setupPreviewSurface() {
        if (surfaceTexture == null) {
            surfaceTexture = SurfaceTexture(0)
            // 使用 1280x720，降低内存占用
            surfaceTexture?.setDefaultBufferSize(640, 480)
        }
        if (previewSurface == null) {
            previewSurface = Surface(surfaceTexture)
        }
    }

    /**
     * 重置录像状态
     */
    private fun resetRecordingState() {
        isRecording = false
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
            L.e(TAG, "释放 MediaRecorder 异常", e)
        }
        mediaRecorder = null
        videoFile = null
    }

    /**
     * 获取最佳视频尺寸
     */
    private fun getBestVideoSize(): Size? {
        return try {
            val characteristics = cameraManager?.getCameraCharacteristics(cameraId!!)
            val map: StreamConfigurationMap? = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes: Array<Size>? = map?.getOutputSizes(MediaRecorder::class.java)
            sizes?.firstOrNull { it.width == 1800 && it.height == 2400 }
                ?: sizes?.firstOrNull { it.width == 1920 && it.height == 1080 }
                ?: sizes?.getOrNull(0)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 计算 JPEG 方向
     */
    private fun getJpegOrientation(rotation: Int): Int {
        val characteristics = cameraManager?.getCameraCharacteristics(cameraId!!)
        val sensorOrientation = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        Log.d(TAG, "sensorOrientation=$sensorOrientation")
        return when (rotation) {
            Surface.ROTATION_0 -> (sensorOrientation + 0) % 360
            Surface.ROTATION_90 -> (sensorOrientation + 270) % 360
            Surface.ROTATION_180 -> (sensorOrientation + 180) % 360
            Surface.ROTATION_270 -> (sensorOrientation + 90) % 360
            else -> sensorOrientation
        }
    }

    /**
     * 创建图片文件
     */
    fun createImageFile(): File? {
        return try {
            val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            baseDir.mkdirs()

            if (baseDir == null || !baseDir.exists()) {
                return null
            }

            val albumDir = File(baseDir, "album")
            if (!albumDir.exists()) {
                albumDir.mkdirs()
            }

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val imageFile = File(albumDir, "IMG_$timeStamp.jpg")

            scanPublicFile(imageFile)
            imageFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 通知系统扫描文件
     */
    private fun scanPublicFile(file: File) {
        val context = MyApplication.getContext()
        val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
        val fileUri = Uri.fromFile(file)
        mediaScanIntent.data = fileUri
        context.sendBroadcast(mediaScanIntent)
    }

    /**
     * 创建视频文件
     */
    private fun createVideoFile(): File {
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val albumDir = File(baseDir, "album")
        if (!albumDir.exists()) {
            albumDir.mkdirs()
        }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File(albumDir, "VID_$timeStamp.mp4")
    }

    /**
     * 释放相机资源
     */
    fun releaseCamera() {
        try {
            if (isCameraClosed) return

            if (isRecording) {
                try {
                    mediaRecorder?.stop()
                } catch (_: Exception) {
                }
                mediaRecorder?.release()
            }

            synchronized(sessionLock) {
                captureSession?.close()
                captureSession = null
            }

            setProcessingCaptureState(false)
            cameraDevice?.close()
            imageReader?.close()
            imgCallback = null
            cameraDevice = null
            imageReader = null
            previewSurface?.release()
            surfaceTexture?.release()
            previewSurface = null
            surfaceTexture = null

            stopBackgroundThread()
            mediaRecorder = null

            L.d(TAG, "释放相机")
        } catch (e: Exception) {
            L.d(TAG, "releaseCamera 异常: ${e.message}")
        } finally {
            isCameraClosed = true
            isRecording = false
            isInitialized = false
            setProcessingCaptureState(false)
        }
    }

    /**
     * 启动后台线程
     */
    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraBackground").apply {
                start()
                backgroundHandler = Handler(looper)
            }
        }
    }

    /**
     * 停止后台线程
     */
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            L.e(TAG, "停止后台线程异常", e)
        }
    }

    /**
     * 检查相机权限
     */
    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            MyApplication.getContext(),
            android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查音频权限
     */
    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            MyApplication.getContext(),
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * 判断相机是否正在工作
     */
    fun isCameraDoing(): Boolean = isProcessingCapture.value

    fun saveImage2(bytes: ByteArray) {
        val photoFile = createImageFile()
        var bitmap: Bitmap? = null
        try {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            FileOutputStream(photoFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存图像失败: ${e.message}", e)
        } finally {
            bitmap?.recycle()
        }
    }


    /**
     * 将 YUV Image 转换为 JPEG 并保存到文件（可靠版本）
     */
    private fun saveYuvImageAsJpeg(image: android.media.Image) {
        val photoFile = createImageFile()
        if (photoFile == null) {
            L.e(TAG, "创建图片文件失败")
            imgCallback?.get()?.invoke(null)
            return
        }

        var jpegBytes: ByteArray? = null

        try {
            val width = image.width
            val height = image.height

            Log.d(TAG, "转换 YUV 到 JPEG: ${width}x${height}")

            // ⭐ 方法 1: 尝试使用 ImageReader 直接获取 JPEG（如果支持）
            // ⭐ 方法 2: 手动转换 YUV_420_888 到 NV21，再压缩为 JPEG

            jpegBytes = convertYuv420ToJpeg(image)

            if (jpegBytes == null || jpegBytes.isEmpty()) {
                L.e(TAG, "JPEG 转换失败，返回空数据")
                imgCallback?.get()?.invoke(null)
                return
            }

            Log.d(TAG, "JPEG 数据大小: ${jpegBytes.size / 1024} KB")

            // 保存 JPEG 数据到文件
            FileOutputStream(photoFile).use { outputStream ->
                outputStream.write(jpegBytes)
                outputStream.flush()
            }

            // 添加 EXIF 旋转信息
            try {
                val exif = ExifInterface(photoFile.absolutePath)
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_270.toString())
                exif.saveAttributes()
                L.d(TAG, "已添加 EXIF 旋转标记: 270度")
            } catch (e: Exception) {
                L.e(TAG, "设置 EXIF 失败", e)
            }

            L.d(TAG, "图片保存成功: ${photoFile.absolutePath}")
            imgCallback?.get()?.invoke(photoFile)

        } catch (e: Exception) {
            L.e(TAG, "保存 YUV 图像失败: ${e.message}", e)
            e.printStackTrace()
            imgCallback?.get()?.invoke(null)
        }
    }

    /**
     * 将 YUV_420_888 Image 转换为 JPEG 字节数组
     */
    private fun convertYuv420ToJpeg(image: android.media.Image): ByteArray? {
        return try {
            val width = image.width
            val height = image.height

            // ⭐ 步骤 1: 提取 YUV 平面数据
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            // 获取行间距和像素间距
            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride
            val uvRowStride = uPlane.rowStride
            val uvPixelStride = uPlane.pixelStride

            Log.d(
                TAG,
                "YUV 参数: width=$width, height=$height, yRowStride=$yRowStride, yPixelStride=$yPixelStride, uvRowStride=$uvRowStride, uvPixelStride=$uvPixelStride"
            )

            // ⭐ 步骤 2: 构建 NV21 数据
            val nv21Size = width * height * 3 / 2
            val nv21 = ByteArray(nv21Size)

            // 复制 Y 分量
            if (yPixelStride == 1) {
                // Y 是连续的
                for (row in 0 until height) {
                    val rowStart = row * yRowStride
                    val nv21Start = row * width
                    yBuffer.position(rowStart)
                    yBuffer.get(nv21, nv21Start, width)
                }
            } else {
                // Y 有像素间距
                for (row in 0 until height) {
                    for (col in 0 until width) {
                        val index = row * yRowStride + col * yPixelStride
                        nv21[row * width + col] = yBuffer.get(index)
                    }
                }
            }

            // 复制 UV 分量（交错为 VU）
            val chromaHeight = height / 2
            val chromaWidth = width / 2
            var nv21Index = width * height

            Log.d(TAG, "开始处理 UV 分量: chromaWidth=$chromaWidth, chromaHeight=$chromaHeight")

            if (uvPixelStride == 2) {
                // ⭐ 常见情况：UV 交错的半平面格式
                // U 和 V 在同一个缓冲区中交错存储 (UVUVUV...)
                for (row in 0 until chromaHeight) {
                    val rowStart = row * uvRowStride

                    for (col in 0 until chromaWidth) {
                        val index = rowStart + col * uvPixelStride

                        // ⭐ 确保不越界
                        if (index + 1 < uBuffer.capacity()) {
                            val u = uBuffer.get(index)      // U 在偶数位置
                            val v = uBuffer.get(index + 1)  // V 在奇数位置
                            nv21[nv21Index++] = v
                            nv21[nv21Index++] = u
                        }
                    }
                }
            } else if (uvPixelStride == 1) {
                // ⭐ U 和 V 是分离的平面
                val vRowStride = vPlane.rowStride
                val vPixelStride = vPlane.pixelStride

                Log.d(TAG, "分离平面: vRowStride=$vRowStride, vPixelStride=$vPixelStride")

                for (row in 0 until chromaHeight) {
                    for (col in 0 until chromaWidth) {
                        val uIndex = row * uvRowStride + col * uvPixelStride
                        val vIndex = row * vRowStride + col * vPixelStride

                        // ⭐ 确保不越界
                        if (uIndex < uBuffer.capacity() && vIndex < vBuffer.capacity()) {
                            nv21[nv21Index++] = vBuffer.get(vIndex)
                            nv21[nv21Index++] = uBuffer.get(uIndex)
                        }
                    }
                }
            } else {
                // 其他情况
                val vRowStride = vPlane.rowStride
                val vPixelStride = vPlane.pixelStride

                for (row in 0 until chromaHeight) {
                    for (col in 0 until chromaWidth) {
                        val uIndex = row * uvRowStride + col * uvPixelStride
                        val vIndex = row * vRowStride + col * vPixelStride

                        if (uIndex < uBuffer.capacity() && vIndex < vBuffer.capacity()) {
                            nv21[nv21Index++] = vBuffer.get(vIndex)
                            nv21[nv21Index++] = uBuffer.get(uIndex)
                        }
                    }
                }
            }

            Log.d(TAG, "NV21 构建完成，大小: ${nv21.size}, nv21Index=$nv21Index")

            // ⭐ 步骤 3: 使用 YuvImage 压缩为 JPEG
            val yuvImage = android.graphics.YuvImage(
                nv21,
                ImageFormat.NV21,
                width,
                height,
                null
            )

            val outputStream = java.io.ByteArrayOutputStream()
            val success = yuvImage.compressToJpeg(
                android.graphics.Rect(0, 0, width, height),
                85,
                outputStream
            )

            if (!success) {
                L.e(TAG, "YuvImage 压缩失败")
                return null
            }

            val jpegData = outputStream.toByteArray()
            outputStream.close()

            Log.d(TAG, "JPEG 压缩成功，大小: ${jpegData.size / 1024} KB")
            jpegData

        } catch (e: Exception) {
            L.e(TAG, "YUV 转 JPEG 异常", e)
            e.printStackTrace()
            null
        }
    }


}
