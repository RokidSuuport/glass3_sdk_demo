package com.rokid.glass.camera

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
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
import com.rokid.glass.MyApplication
import com.rokid.glass.utils.DeviceUtil
import com.rokid.glass.utils.Scopes.mainScope
import com.rokid.glass.utils.call
import com.rokid.security.glass3.open.sdk.uitls.log.L
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException


object QuickCameraManager {
    private const val TAG = "QuickCameraManager"
    private const val VIDEO_FRAME_RATE = 24
    private const val VIDEO_BIT_RATE = 5_000_000

    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null

    @Volatile
    private var captureSession: CameraCaptureSession? = null
    private val sessionLock = Any()

    private var imageReader: ImageReader? = null
    private var mediaRecorder: MediaRecorder? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    private var cameraId: String? = null
    private var isInitialized = false
    private var isRecording = false
    private var videoFile: File? = null

    private var previewSurface: Surface? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var imgCallback: WeakReference<((File?) -> Unit)>? = null
    private var isQuickCapture = false

    @Volatile
    private var isCameraClosed = true // 初始状态为关闭

    val availabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            L.d(TAG, "相机可用: $cameraId")
            // 相机可用
        }

        override fun onCameraUnavailable(cameraId: String) {
            // 相机不可用（可能已被关闭或占用）
            isCameraClosed = true
            L.d(TAG, "相机不可用: $cameraId")
        }
    }

    @SuppressLint("MissingPermission")
    fun initialize(size: Size? = null, quickCapture: Boolean = false, onInitialized: (Boolean) -> Unit) {

        val weakCallback = WeakReference(onInitialized)
        releaseCamera()
        this.isQuickCapture = quickCapture
        if (isInitialized) {
//            onInitialized(true)
            weakCallback.get()?.invoke(true)
            return
        }

        if (!hasCameraPermission()) {
            L.e(TAG, "没有相机权限")
//            onInitialized(false)
            weakCallback.get()?.invoke(false)
            return
        }

        try {
            cameraManager = MyApplication.getContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraId = cameraManager?.cameraIdList?.firstOrNull()

            if (cameraId == null) {
                L.e(TAG, "没有可用相机")
//                onInitialized(false)
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
                        isCameraClosed = false // 标记为已打开
                        cameraDevice = camera
                        isInitialized = true
                        setupImageReader(size)
                        setupPreviewSurface()
                        createPreviewSession()
//                        onInitialized(true)
                        weakCallback.get()?.invoke(true)

                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        releaseCamera()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        L.e(TAG, "相机打开错误: $error")
                        releaseCamera()
//                        onInitialized(false)
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            releaseCamera()
            L.e(TAG, "初始化失败: ${e.message}", e)
//            onInitialized(false)
        }
    }

    private fun createPreviewSession() {
        val previewSurface = this.previewSurface ?: return
        val imageReaderSurface = imageReader?.surface ?: return

        try {
            if (isCameraClosed) {
                return
            }
            cameraDevice?.createCaptureSession(
                listOf(previewSurface, imageReaderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        synchronized(sessionLock) {
                            if (cameraDevice == null) {
                                setProcessingCaptureState(false)
                                Log.d(TAG, "TARGET 1")
                                return
                            }
                            captureSession = session
                            isSessionClosed = false
                            try {
                                val builder = cameraDevice!!.createCaptureRequest(
                                    CameraDevice.TEMPLATE_PREVIEW
                                ).apply {

                                    // 添加自动曝光模式
                                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    // 设置曝光补偿（根据需要调整）
                                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
                                    // 启用自动曝光锁定（可选）
                                    set(CaptureRequest.CONTROL_AE_LOCK, false)
                                    addTarget(previewSurface)
                                }
                                session.setRepeatingRequest(builder.build(), null, backgroundHandler)
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

    private var isSessionClosed = false
    fun isCameraDoing(): Boolean = isProcessingCapture.value

    @Volatile
    var isProcessingCapture = MutableStateFlow(false)

    fun setProcessingCaptureState(state: Boolean) {
        isProcessingCapture.call(state)
    }


    fun takePicture(callback: (File?) -> Unit) {
        val weakCallback = WeakReference(callback)
        imgCallback = weakCallback
        if (isCameraDoing()) {
            Log.d(TAG, "takePicture 正在处理中")
            imgCallback?.get()?.invoke(null)
            return
        }

        setProcessingCaptureState(true)


        if (!isInitialized || cameraDevice == null) {
            Log.d(TAG, "相机未初始化")
            imgCallback?.get()?.invoke(null)
            setProcessingCaptureState(false)
            return
        }


        CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "延迟900后开始拍照")
            delay(700)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: run {
                    Log.d(TAG, "拍照失败")
                    imgCallback?.get()?.invoke(null)
                    setProcessingCaptureState(false)
                    return@setOnImageAvailableListener
                }

                try {
                    val buffer = image.planes[0].buffer
//                val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
//                saveImage(bytes)
                    saveImageFromBuffer(buffer)
                } catch (e: Exception) {
                    L.e(TAG, "保存图片失败", e)
                    imgCallback?.get()?.invoke(null)
                } finally {
                    image.close()
                    if (isQuickCapture) {
                        releaseCamera()
                    } else {
                        setProcessingCaptureState(false)
                    }
//                createPreviewSession()
                }
            }, backgroundHandler)

            try {
                val surface = imageReader?.surface ?: throw IllegalStateException("ImageReader surface is null")
                val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(surface)
                    set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation(270))
                }

                DeviceUtil.setSystemProp("vendor.rkd.camera.sensormode", "5")
                cameraDevice!!.createCaptureSession(
                    listOf(surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            try {
                                session.capture(builder.build(), null, backgroundHandler)
                            } catch (e: Exception) {
                                L.e(TAG, "拍照失败", e)
                                imgCallback?.get()?.invoke(null)
                                setProcessingCaptureState(false)
//                            createPreviewSession()
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            L.e(TAG, "拍照失败->")
                            imgCallback?.get()?.invoke(null)
                            setProcessingCaptureState(false)
                            createPreviewSession()
                        }
                    },
                    backgroundHandler
                )
            } catch (e: Exception) {
                L.e(TAG, "拍照异常", e)
                imgCallback?.get()?.invoke(null)
                setProcessingCaptureState(false)
            }
        }

    }

    fun startRecording(isAudioMute: Boolean = false, callback: (File?) -> Unit) {
        val weakCallback = WeakReference(callback)
        if (!isInitialized || cameraDevice == null || !hasAudioPermission() || isRecording) {
            weakCallback.get()?.invoke(null)
            return
        }

//        try {
//            captureSession?.stopRepeating()
//        } catch (e: Exception) {
//            Log.d(TAG, "停止预览失败", e)
//        }

        try {
            resetRecordingState()
            videoFile = createVideoFile()
            setupPreviewSurface()

            mediaRecorder = MediaRecorder().apply {
                if (!isAudioMute) {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                }
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                val size = getBestVideoSize() ?: Size(1080, 1920)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                if (!isAudioMute) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                }
                setVideoSize(size.width, size.height)
                setVideoFrameRate(VIDEO_FRAME_RATE)
                setVideoEncodingBitRate(VIDEO_BIT_RATE)
                setOutputFile(videoFile?.absolutePath)
                setOrientationHint(270)
                prepare()
            }

            DeviceUtil.setSystemProp("vendor.rkd.camera.sensormode", "5")
            val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            val recorderSurface = mediaRecorder!!.surface
            builder?.addTarget(previewSurface!!)
            builder?.addTarget(recorderSurface)

            setProcessingCaptureState(true)
            cameraDevice?.createCaptureSession(
                listOf(previewSurface!!, recorderSurface),
                object : CameraCaptureSession.StateCallback() {
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
                                mediaRecorder?.start()
                                isRecording = true
                                weakCallback.get()?.invoke(videoFile)
                                Handler(backgroundHandler!!.looper).post {
                                    try {
                                        builder?.build()?.let {
                                            synchronized(sessionLock) {
                                                captureSession?.setRepeatingRequest(it, null, backgroundHandler)
                                            }
                                        }
                                    } catch (e: IllegalStateException) {
                                        setProcessingCaptureState(false)
                                        L.e(TAG, "录像时 session 已关闭", e)
                                    } catch (e: Exception) {
                                        setProcessingCaptureState(false)
                                        L.e(TAG, "录像 setRepeatingRequest 异常", e)
                                    }
                                }
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
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            setProcessingCaptureState(false)
            resetRecordingState()
            weakCallback.get()?.invoke(null)
        }
    }

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
    }

    private fun setupImageReader(mSize: Size? = null) {
        L.d(TAG, "setupImageReader->1")
        val characteristics = cameraManager?.getCameraCharacteristics(cameraId!!)
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val outputSizes: Array<Size>? = map?.getOutputSizes(ImageFormat.JPEG)
//        outputSizes?.forEach {
//            Log.d(TAG, "--setupImageReader.size----width->" + it.width + ",height=" + it.height)
//        }
        // 设置拍照为横屏
//        var size: Size = outputSizes?.firstOrNull { it.width == 2268 && it.height == 3024 } ?: Size(1080, 1920)
        var size: Size = outputSizes?.firstOrNull { it.width == 1080 && it.height == 1920 } ?: Size(2268, 3024)
//         设置拍照为竖屏
//        var size: Size = outputSizes?.firstOrNull { it.width == 3024 && it.height == 2268 } ?: Size(1920, 1080)
        if (mSize != null) {
            outputSizes?.forEach { sz ->
                L.d(TAG, "setupImageReader->" + sz.width + " " + sz.height)
                if (mSize.width == sz.width && mSize.height == sz.height) {
                    size = mSize
                }
            }
        }
        Log.d(TAG, "setupImageReader->size.width=" + size.width + ",size.height=" + size.height)
        imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        // 不设置监听器，改为在 takePicture() 时临时设置
    }


    private fun saveImageFromBuffer(buffer: ByteBuffer) {
        val photoFile = createImageFile()
        var rotatedBitmap: Bitmap? = null
        try {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inScaled = false
            }
            // 直接从buffer流解码
            val bitmap = BitmapFactory.decodeStream(ByteBufferInputStream(buffer), null, options) ?: return
            // 旋转处理（同之前的优化）
            val matrix = Matrix().apply { postRotate(270f) }
            rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
            bitmap.recycle()

            // 保存图片
            FileOutputStream(photoFile).use { out ->
                rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }
            imgCallback?.get()?.invoke(photoFile)
        } catch (e: Exception) {
            L.e(TAG, "保存图像失败: ${e.message}", e)
        } finally {
            rotatedBitmap?.recycle()
        }
    }


    private fun setupPreviewSurface() {
        if (surfaceTexture == null) {
            surfaceTexture = SurfaceTexture(0)
            surfaceTexture?.setDefaultBufferSize(1920, 1080)
        }
        if (previewSurface == null) {
            previewSurface = Surface(surfaceTexture)
        }
    }

    private fun resetRecordingState() {
        isRecording = false
        mediaRecorder?.release()
        mediaRecorder = null
        previewSurface?.release()
        surfaceTexture?.release()
        previewSurface = null
        surfaceTexture = null
        videoFile = null
    }

    private fun getBestVideoSize(): Size? {
        return try {
            val characteristics = cameraManager?.getCameraCharacteristics(cameraId!!)
            val map: StreamConfigurationMap? = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes: Array<Size>? = map?.getOutputSizes(MediaRecorder::class.java)
            // 设置摄像头录像为横屏
            sizes?.firstOrNull { it.width == 1080 && it.height == 1920 }
            // 设置摄像头录像为竖屏
//            sizes?.firstOrNull { it.width == 1920 && it.height == 1080 }
                ?: sizes?.firstOrNull { it.width == 720 && it.height == 1280 }
                ?: sizes?.getOrNull(0)
        } catch (e: Exception) {
            null
        }
    }

    private fun getJpegOrientation(rotation: Int): Int {
        val characteristics = cameraManager?.getCameraCharacteristics(cameraId!!)
        val sensorOrientation = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        Log.d(TAG,"-----------sensorOrientation=${sensorOrientation}")
        return when (rotation) {
            Surface.ROTATION_0 -> (sensorOrientation + 0) % 360
            Surface.ROTATION_90 -> (sensorOrientation + 270) % 360
            Surface.ROTATION_180 -> (sensorOrientation + 180) % 360
            Surface.ROTATION_270 -> (sensorOrientation + 90) % 360
            else -> sensorOrientation
        }
    }

//    private fun createImageFile(): File {
//        // 获取基础图片目录
//        val baseDir = MyApplication.getContext().getExternalFilesDir(Environment.DIRECTORY_DCIM)
//        // 创建包含album子目录的完整路径
//        val albumDir = File(baseDir, "album")
//        // 确保目录存在（若不存在则创建）
//        if (!albumDir.exists()) {
//            albumDir.mkdirs()
//        }
//        // 生成时间戳文件名
//        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
//        // 在album目录下创建图片文件
//        return File(albumDir, "IMG_$timeStamp.jpg")
//    }


    fun createImageFile(): File? { // 改为返回 File?，避免异常时返回无效对象
        return try {
            // 1. 关键修改：获取系统公共 DCIM 目录（替代原私有目录）
            // 路径示例：/storage/emulated/0/DCIM（所有应用可访问）
            val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            baseDir.mkdirs()
            if (baseDir == null || !baseDir.exists()) {
                return null // 极端情况：公共目录不存在（如存储挂载失败）
            }

            // 2. 保持原有逻辑：创建 album 子目录（路径：/Pictures/album）
            val albumDir = File(baseDir, "album")
            if (!albumDir.exists()) {
                albumDir.mkdirs() // 自动创建多级目录（DCIM 已存在，仅创建 album）
            }

            // 3. 保持原有逻辑：生成时间戳文件名
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val imageFile = File(albumDir, "IMG_$timeStamp.jpg")

            // 4. 新增：通知系统扫描文件，确保其他应用（如相册、微信）能识别
            scanPublicFile(imageFile)

            imageFile // 返回公共目录下的 File 对象（后续可直接写入数据）
        } catch (e: Exception) {
            e.printStackTrace()
            null // 异常时返回 null（如权限不足、存储满）
        }
    }

    // 辅助方法：通知系统扫描公共目录的文件（核心，否则其他应用找不到）
    private fun scanPublicFile(file: File) {
        val context = MyApplication.getContext()
        // 发送广播触发系统媒体扫描（兼容所有 Android 版本）
        val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
        val fileUri = Uri.fromFile(file)
        mediaScanIntent.data = fileUri
        context.sendBroadcast(mediaScanIntent)
    }

    private fun createVideoFile(): File {
        // 获取基础视频目录
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        // 创建包含album子目录的完整路径
        val albumDir = File(baseDir, "album")
        // 确保目录存在（若不存在则创建）
        if (!albumDir.exists()) {
            albumDir.mkdirs()
        }
        // 生成时间戳文件名
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        // 在album目录下创建视频文件
        return File(albumDir, "VID_$timeStamp.mp4")
    }

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

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraBackground").apply {
                start()
                backgroundHandler = Handler(looper)
                cameraManager?.registerAvailabilityCallback(availabilityCallback, backgroundHandler)
            }
        }
    }

    private fun stopBackgroundThread(callback: (() -> Unit)? = null) {
        cameraManager?.unregisterAvailabilityCallback(availabilityCallback)
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
        callback?.invoke() // 直接执行回调，无需延迟
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(MyApplication.getContext(), android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(MyApplication.getContext(), android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    fun isRecording(): Boolean = isRecording


    private var captureCollectJob: Job? = null
    private var isListeningProcessing = false

    fun actionDestroyCameraTask() {
        L.d(TAG, "actionDestroyCameraTask->${isCameraDoing()}")
        if (isCameraDoing()) {
            mainScope.launch {
                // 2. 关键：先取消旧协程并等待其完全结束（避免旧协程残留）
                captureCollectJob?.let {
                    it.cancel() // 取消旧协程
                    it.join()   // 等待协程完全终止（解决协作式取消的延迟问题）
                    captureCollectJob = null // 清空引用，避免重复操作
                }
                // 3. 确保当前没有其他监听协程，再启动新协程
                if (!isListeningProcessing) {
                    isListeningProcessing = true
                    captureCollectJob = launch {
                        try {
                            // 新增：5秒超时机制
                            withTimeoutOrNull(5000) {
                                // 监听处理状态流
                                isProcessingCapture.collect { processing ->
                                    L.d(TAG, "actionDestroyCameraTask collect->$processing isCameraClosed：${isCameraClosed}")
                                    if (!processing && !isCameraClosed) {
                                        releaseCamera()
                                        captureCollectJob?.cancel() // 满足条件时取消协程
                                    }
                                }
                            } ?: run {
                                // 超时未满足条件，强制释放
                                Log.d(TAG, "相机释放超时（5秒），强制释放资源")
                                if (!isCameraClosed) {
                                    releaseCamera()
                                }
                            }
                        } catch (e: CancellationException) {
                            // 正常取消，无需处理
                        } finally {
                            isListeningProcessing = false
                            captureCollectJob = null
                        }
                    }
                }
            }
        } else {
            releaseCamera()
        }
    }

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


}



