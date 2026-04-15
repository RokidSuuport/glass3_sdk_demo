//package com.rokid.glass.camera
//
//import android.annotation.SuppressLint
//import android.content.Context
//import android.graphics.ImageFormat
//import android.graphics.Matrix
//import android.graphics.SurfaceTexture
//import android.hardware.camera2.CameraAccessException
//import android.hardware.camera2.CameraCaptureSession
//import android.hardware.camera2.CameraCharacteristics
//import android.hardware.camera2.CameraDevice
//import android.hardware.camera2.CameraManager
//import android.hardware.camera2.CaptureRequest
//import android.media.ImageReader
//import android.os.Handler
//import android.os.HandlerThread
//import android.util.Size
//import android.view.Surface
//import mylab.droid.utils.android.SystemServiceUtil
//
//import java.io.File
//import java.io.FileOutputStream
//
//@SuppressLint("MissingPermission")
//class RokidCameraManager(private val context: Context) {
//
//    private var cameraDevice: CameraDevice? = null
//    private var captureSession: CameraCaptureSession? = null
//    private var previewSurface: Surface? = null
//    private var imageReader: ImageReader? = null
//
//    private var backgroundThread: HandlerThread? = null
//    private var backgroundHandler: Handler? = null
//
//    private var isInitialized = false
//    private var sensorOrientation = 0 // 相机传感器方向
//
//    /**
//     * 初始化相机，并把预览画面输出到外部传入的 SurfaceTexture
//     */
//    fun initialize(
//        surfaceTexture: SurfaceTexture,
//        width: Int,
//        height: Int,
//        callback: (Boolean) -> Unit
//    ) {
//        if (isInitialized) {
//            callback(true)
//            return
//        }
//
//        // 检查线程是否已存在，避免重复创建
//        if (backgroundThread == null) {
//            backgroundThread = HandlerThread("CameraBackground").apply { start() }
//            backgroundHandler = Handler(backgroundThread!!.looper)
//        }
//
//        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
//        try {
//            val cameraId = manager.cameraIdList.firstOrNull { id ->
//                val characteristics = manager.getCameraCharacteristics(id)
//                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
//                facing == CameraCharacteristics.LENS_FACING_BACK
//            } ?: run {
//
//                callback(false)
//                return
//            }
//
//            // 在这里检查权限
//            if (androidx.core.content.ContextCompat.checkSelfPermission(
//                    context,
//                    android.Manifest.permission.CAMERA
//                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
//            ) {
//                Timber.e("没有 CAMERA 权限")
//                callback(false)
//                return
//            }
//
//
//            val stateCallback: CameraDevice.StateCallback = object : CameraDevice.StateCallback() {
//                override fun onOpened(device: CameraDevice) {
//                    cameraDevice = device
//                    try {
//                        val useDefault = true
//                        val previewSize =
//                            if (useDefault) Size(width, height) else getBestPreviewSize(
//                                manager,
//                                cameraId,
//                                width,
//                                height
//                            )
//                        Timber.i("相机预览分辨率： ${previewSize.width} * ${previewSize.height}")
//                        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
//
//                        // 在 initialize 方法中，获取相机特性后添加：
//                        val characteristics = manager.getCameraCharacteristics(cameraId)
//                        // 获取相机传感器方向
//                        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
//                        // 应用旋转变换
//                        applyRotation(surfaceTexture, previewSize.width, previewSize.height)
//                        previewSurface = Surface(surfaceTexture)
//
//                        // 通过 SCALER_STREAM_CONFIGURATION_MAP 获取 JPEG 输出尺寸, 使用相机最大拍照能力
//                        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
//                        val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG) ?: return
//                        val bestJpegSize = jpegSizes.maxByOrNull { it.width * it.height } ?: Size(width, height)
//                        imageReader = ImageReader.newInstance(bestJpegSize.width, bestJpegSize.height, ImageFormat.JPEG, 2)
//                        //imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2)
//                        val surfaces = listOf(previewSurface!!, imageReader!!.surface)
//                        device.createCaptureSession(
//                            surfaces,
//                            object : CameraCaptureSession.StateCallback() {
//                                override fun onConfigured(session: CameraCaptureSession) {
//                                    captureSession = session
//                                    try {
//                                        val previewRequest =
//                                            device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
//                                                .apply {
//                                                    addTarget(previewSurface!!)
//                                                    // 设置 JPEG 方向（主要影响拍照，但某些设备上也会影响预览）
//                                                    set(
//                                                        CaptureRequest.JPEG_ORIENTATION,
//                                                        calculateJpegOrientation(
//                                                            characteristics,
//                                                            getDeviceRotation()
//                                                        )
//                                                    )
//                                                }
//                                        session.setRepeatingRequest(
//                                            previewRequest.build(),
//                                            null,
//                                            backgroundHandler
//                                        )
//                                        isInitialized = true
//                                        callback(true)
//                                    } catch (e: Exception) {
//                                        Timber.e(e, "启动预览失败")
//                                        callback(false)
//                                    }
//                                }
//
//                                override fun onConfigureFailed(session: CameraCaptureSession) {
//                                    callback(false)
//                                }
//                            },
//                            backgroundHandler
//                        )
//                    } catch (e: Exception) {
//                        Timber.e(e, "初始化预览失败")
//                        callback(false)
//                    }
//                }
//
//                override fun onDisconnected(device: CameraDevice) {
//                    release()
//                    callback(false)
//                }
//
//                override fun onError(device: CameraDevice, error: Int) {
//                    release()
//                    callback(false)
//                }
//            }
//
//            manager.openCamera(cameraId, stateCallback, backgroundHandler)
//        } catch (e: SecurityException) {
//            Timber.e(e, "没有相机权限: ${e.message}")
//            callback(false)
//        } catch (e: Exception) {
//            Timber.e(e, "打开相机失败")
//            callback(false)
//        }
//    }
//
//
//    /**
//     * 拍照并保存到文件
//     */
//    fun takePicture(outputFile: File, callback: (Boolean) -> Unit) {
//        val device = cameraDevice ?: run {
//            Timber.e("相机未初始化")
//            callback(false)
//            return
//        }
//        val session = captureSession ?: run {
//            Timber.e("捕获会话未初始化")
//            callback(false)
//            return
//        }
//        val reader = imageReader ?: run {
//
//            callback(false)
//            return
//        }
//
//        try {
//            val characteristics = SystemServiceUtil.cameraManager.getCameraCharacteristics(cameraDevice!!.id)
//            val deviceRotation = getDeviceRotation()
//
//            val captureRequest =
//                device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
//                    addTarget(reader.surface)
//                    // 设置拍照方向
////                    set(CaptureRequest.JPEG_ORIENTATION, -90)
//                    set(
//                        CaptureRequest.JPEG_ORIENTATION,
//                        calculateJpegOrientation(characteristics, deviceRotation)
//                    )
//                }
//
//            // 移除之前的监听，避免冲突
//            reader.setOnImageAvailableListener(null, null)
//            reader.setOnImageAvailableListener({ reader ->
//                reader.acquireLatestImage()?.use { image ->
//                    val buffer = image.planes[0].buffer
//                    val bytes = ByteArray(buffer.remaining())
//                    buffer.get(bytes)
//                    FileOutputStream(outputFile).use { it.write(bytes) }
//
//                    callback(true)
//                }
//            }, backgroundHandler)
//
//            session.capture(captureRequest.build(), null, backgroundHandler)
//        } catch (e: Exception) {
//
//            callback(false)
//        }
//    }
//
//    /**
//     * 释放资源
//     */
//    fun release() {
//        // 安全地停止重复请求
//        safeSessionOperation { it.stopRepeating() }
//
//        // 安全地中止捕获
//        safeSessionOperation { it.abortCaptures() }
//
//        // 关闭会话
//        safeSessionOperation { it.close() }
//        captureSession = null
//
//        // 清理其他资源
//        imageReader?.setOnImageAvailableListener(null, null)
//        safeClose { imageReader?.close() }
//        imageReader = null
//
//        safeClose { previewSurface?.release() }
//        previewSurface = null
//
//        safeClose { cameraDevice?.close() }
//        cameraDevice = null
//
//        backgroundThread?.quitSafely()
//        backgroundThread = null
//        backgroundHandler = null
//        isInitialized = false
//    }
//
//    private inline fun safeSessionOperation(operation: (CameraCaptureSession) -> Unit) {
//        captureSession?.let { session ->
//            try {
//                operation(session)
//            } catch (e: CameraAccessException) {
//                when (e.reason) {
//                    CameraAccessException.CAMERA_DISCONNECTED,
//                    CameraAccessException.CAMERA_ERROR -> {
//                        Timber.w("相机已断开或错误，跳过操作")
//                    }
//                    else -> Timber.e(e, "会话操作失败")
//                }
//            } catch (e: IllegalStateException) {
//
//            }
//        }
//    }
//
//    private inline fun safeClose(closeOperation: () -> Unit) {
//        try {
//            closeOperation()
//        } catch (e: Exception) {
//
//        }
//    }
//
//    /**
//     * 应用旋转变换到 SurfaceTexture（通过 TextureView）
//     */
//    private fun applyRotation(
//        surfaceTexture: SurfaceTexture,
//        previewWidth: Int,
//        previewHeight: Int
//    ) {
//        // 这个方法通常需要在关联的 TextureView 上调用 setTransform
//        // 由于你直接操作 SurfaceTexture，这里提供基础实现
//        val matrix = Matrix()
//        val viewRect =
//            android.graphics.RectF(0f, 0f, previewWidth.toFloat(), previewHeight.toFloat())
//        val bufferRect =
//            android.graphics.RectF(0f, 0f, previewHeight.toFloat(), previewWidth.toFloat())
//
//        val centerX = viewRect.centerX()
//        val centerY = viewRect.centerY()
//
//        if (sensorOrientation == 90 || sensorOrientation == 270) {
//            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
//            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
//            val scale = Math.max(
//                viewRect.height() / previewHeight.toFloat(),
//                viewRect.width() / previewWidth.toFloat()
//            )
//            matrix.postScale(scale, scale, centerX, centerY)
//            matrix.postRotate(sensorOrientation.toFloat(), centerX, centerY)
//        }
//
//        // 如果 surfaceTexture 关联到 TextureView，需要在 TextureView 上调用 setTransform
//        // surfaceTexture 本身不支持直接设置矩阵变换
//    }
//
//    /**
//     * 选择最佳预览尺寸（优先 1920x1080 -> 1280x720 -> 最大可用分辨率）
//     */
//    private fun getBestPreviewSize(
//        manager: CameraManager,
//        cameraId: String,
//        targetWidth: Int,
//        targetHeight: Int
//    ): Size {
//        val characteristics = manager.getCameraCharacteristics(cameraId)
//        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
//        val previewSizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: return Size(
//            targetWidth,
//            targetHeight
//        )
//
//        return previewSizes.firstOrNull { it.width == 1920 && it.height == 1080 }
//            ?: previewSizes.firstOrNull { it.width == 1280 && it.height == 720 }
//            ?: previewSizes.maxByOrNull { it.width * it.height }
//            ?: Size(targetWidth, targetHeight)
//    }
//
//    // 添加以下常量和方法到你的 CameraTestManager 类中
//
//    /**
//     * 计算用于 JPEG 图像捕获的正确旋转角度
//     * @param characteristics 相机特性
//     * @param deviceRotation 当前设备自然方向（0, 90, 180, 270）
//     */
//    private fun calculateJpegOrientation(
//        characteristics: CameraCharacteristics,
//        deviceRotation: Int
//    ): Int {
//        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
//        val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
//
//        var jpegOrientation: Int
//        if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
//            // 前置摄像头需要镜像处理
//            jpegOrientation = (sensorOrientation + deviceRotation) % 360
//            jpegOrientation = (360 - jpegOrientation) % 360  // 补偿镜像
//        } else {
//            // 后置摄像头
//            //jpegOrientation = (sensorOrientation - deviceRotation + 360) % 360
//            jpegOrientation = when (deviceRotation) {
//                0 -> sensorOrientation                      // 设备自然方向，使用传感器方向
//                90 -> (sensorOrientation + 270) % 360       // 设备顺时针旋转90度
//                180 -> (sensorOrientation + 180) % 360      // 设备旋转180度
//                270 -> (sensorOrientation + 90) % 360       // 设备顺时针旋转270度（逆时针90度）
//                else -> sensorOrientation
//            }
//        }
//
//        return jpegOrientation
//    }
//
//    /**
//     * 获取当前设备屏幕旋转角度
//     */
//    private fun getDeviceRotation(): Int {
//        val windowManager = SystemServiceUtil.windowManager
//        return when (windowManager.defaultDisplay.rotation) {
//            Surface.ROTATION_0 -> 0
//            Surface.ROTATION_90 -> 90
//            Surface.ROTATION_180 -> 180
//            Surface.ROTATION_270 -> 270
//            else -> 0
//        }
//    }
//
//}
