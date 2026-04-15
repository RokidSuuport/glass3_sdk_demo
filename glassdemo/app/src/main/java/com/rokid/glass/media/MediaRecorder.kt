package com.rokid.glass.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import com.rokid.glass.MyApplication
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.system.server.media.callback.AudioCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


/**
 * 音视频录制管理类
 */
class MediaRecorder : MediaEncoder.Callback {

    /**
     * 状态通知回调
     */
    interface Callback {
        /**
         * 开始处理
         */
        @MainThread
        fun onStarted()

        /**
         * 处理完毕
         * @param t 异常终止原因，正常终止为null
         */
        @MainThread
        fun onFinished(t: Throwable?)

        fun onNewFile(startTime: Long, endTime: Long, path: String, isLast: Boolean)

    }

    private var mCallback: Callback? = null
    private var mMainHandler = Handler(Looper.getMainLooper())

    private var mMediaMuxer: MediaMuxer? = null
    private var mAudioEncoder: AudioEncoder? = null
    private var mVideoEncoder: VideoEncoder? = null

    private var isPrepared = false
    private var isStarted = false

    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var surfaceTexture: SurfaceTexture? = null

    @Volatile
    private var captureSession: CameraCaptureSession? = null
    private var cameraId: String? = null
    var isRecording = false
        private set

    // 编码是否可用
    private val isEncodeAvailable: Boolean
        get() =
            and(mAudioEncoder?.isFormatChanged, mVideoEncoder?.isFormatChanged)
    // 是否正在编码
    private val isEncodeRunning: Boolean
        get() =
            or(mAudioEncoder?.isFormatChanged, mVideoEncoder?.isFormatChanged)
    private val mLockMuxer = ReentrantLock()
    private val mHandlerThread = HandlerThread("EncodeManager")
    private val mHandler: Handler
    private val mScope: CoroutineScope
    private lateinit var mRecordData: RecordData
    private var mStartTime = 0L
    private val mByteBufferHelper = ByteBufferHelper()
    private var needRestart = false
    var enableAudio = true
    var fragmentTime = 1

    init {
        mHandlerThread.start()
        mHandler = Handler(mHandlerThread.looper)
        mScope = CoroutineScope(mHandler.asCoroutineDispatcher() + SupervisorJob())
        cameraManager = MyApplication.getContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    @WorkerThread
    fun prepare(recordData: RecordData, callback: Callback?) {
        try {
            this.mRecordData = recordData
            mMediaMuxer = MediaMuxer(
                recordData.recFile.canonicalPath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            if (enableAudio && recordData.audioData.isAvailable()) {
                mAudioEncoder = AudioEncoder(recordData.audioData, mByteBufferHelper, this)
            }
            if (recordData.videoData.isAvailable()) {
                mVideoEncoder = VideoEncoder(recordData.videoData, mByteBufferHelper,this)
            }
            isPrepared = true
            this.mCallback = callback
            openCamera()
            isPrepared = true
        } catch (e: Exception) {
            e.printStackTrace()
            logE("Failed to prepare recording.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        cameraId = cameraManager?.cameraIdList?.firstOrNull()
        if (cameraId == null) {
            Log.e(TAG, "没有可用相机")
            return
        }
        Log.d(TAG, "相机ID: $cameraId")
        cameraManager?.openCamera(cameraId!!, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                Log.d(TAG, "->相机已打开")
                cameraDevice = camera
                createSession()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "相机打开错误: $error")
                camera.close();
            }
        }, mHandler)
    }

    private fun createSession() {
        val outputs = listOf(mVideoEncoder!!.surface)
        cameraDevice!!.createCaptureSession(
            outputs, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        startStreaming()
                    } catch (e: CameraAccessException) {
                        e.printStackTrace()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                }
            }, mHandler
        )
    }

    private fun startStreaming() {
        val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(mVideoEncoder!!.surface)
        captureSession?.setRepeatingRequest(builder.build(), null, mHandler)
    }

    fun start() {
        if (!isPrepared) {
            logW("Recording is not prepared.")
            return
        }
        if (isStarted || isRecording) {
            logW("Recording is already started.")
            return
        }
        logI("Start recording.")
        isStarted = true
        isRecording = true
        mAudioEncoder?.start()
        mVideoEncoder?.start()
        if (enableAudio) {
            GlassSdk.getGlassMediaService()?.startAudioRecord(mAudioCallback)
        }
//        postCreateNewFile()
    }

    private val mAudioCallback = object : AudioCallback.Stub() {
        override fun onAudioStream(buffer: ByteArray?, bufferLen: Int) {
//            L.d(TAG, "onAudioStream-->bufferLen: $bufferLen")
            if (buffer != null && bufferLen > 0) {
                inputAudioBytes(buffer)
            }
        }

        override fun getCallbackId(): String? {
            return "10001"
        }
    }

    private fun postCreateNewFile() {
        mMainHandler.postDelayed({
            needRestart = true
        }, FRAGMENT_DURATION * fragmentTime)
    }

    fun stop() {
        if (enableAudio) {
            GlassSdk.getGlassMediaService()?.stopAudioRecord(mAudioCallback)
        }
        if (!isRecording) {
            logW("Recording is already stopped.")
            return
        }
        logI("Stop recording.")
        isRecording = false
        mAudioEncoder?.stop()
        mVideoEncoder?.stop()
        mMainHandler.removeCallbacksAndMessages(null)
        mMainHandler.postDelayed({ mVideoEncoder?.checkForRelease() }, 1000)
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
    }

    /**
     * 输入音频缓冲区
     * @param data 包含音频缓冲区信息的字节数组
     */
    @WorkerThread
    fun inputAudioBytes(data: ByteArray) {
        if (isRecording) {
            mAudioEncoder?.enqueueAudioBytes(data)
        }
    }

    override fun onStarted(mediaType: MediaType, mediaFormat: MediaFormat) {
        mLockMuxer.withLock {
            when (mediaType) {
                MediaType.AUDIO -> {
                    mAudioEncoder?.trackId = mMediaMuxer?.addTrack(mediaFormat) ?: -1
                    logI("Audio trackId: ${mAudioEncoder?.trackId}")
                }

                MediaType.VIDEO -> {
                    mVideoEncoder?.trackId = mMediaMuxer?.addTrack(mediaFormat) ?: -1
                    logI("Video trackId: ${mVideoEncoder?.trackId}")
                }
            }
            // 完成向轨道添加格式后，可以启动 Muxer
            if (isEncodeAvailable) {
                logI("Start mediaMuxer.")
                try {
                    mMediaMuxer?.start()
                    mStartTime = System.currentTimeMillis()
                    mCallback?.let {
                        mMainHandler.post { it.onStarted() }
                    }
                } catch (e: IllegalStateException) {
                    logE("Failed to start mediaMuxer.")
                }
            }
        }
    }

    override fun onEncodedBuffer(
        trackId: Int,
        buffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo
    ) {
        mLockMuxer.withLock {
            if (isEncodeAvailable) {
                // 如果需要重启(重新生成文件)，当前帧是video，并且为关键帧
                if (needRestart && (mVideoEncoder?.trackId == trackId) && bufferInfo.flags == MediaCodec.BUFFER_FLAG_KEY_FRAME) {
                    restart()
                }
                try {
                    logI("Write sample data. trackId: $trackId ${bufferInfo.presentationTimeUs}  isAudio ${mAudioEncoder?.trackId == trackId}")
                    mMediaMuxer?.writeSampleData(trackId, buffer, bufferInfo)
                } catch (e: Exception) {
                    logE("Failed to write sample data.")
                }
            }
        }
    }

    private fun restart() {
        try {
            mMediaMuxer?.stop()
            mMediaMuxer?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val path = mRecordData.recFile.absolutePath
        mMainHandler.post {
            mCallback?.onNewFile(
                mStartTime,
                System.currentTimeMillis(),
                path, false
            )
        }
        mMediaMuxer = MediaMuxer(
            mRecordData.newFile().canonicalPath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        ).also {
            mAudioEncoder?.trackId = it.addTrack(mAudioEncoder!!.mediaFormat())
            mVideoEncoder?.trackId = it.addTrack(mVideoEncoder!!.mediaFormat())
            it.start()
            mStartTime = System.currentTimeMillis()
            postCreateNewFile()
        }
        needRestart = false
    }

    override fun onFinished(t: Throwable?) {
        mLockMuxer.withLock {
            // 检查音频或视频编码过程是否已完成
            if (isEncodeRunning) {
                logI("Encoder is still running.")
                return
            }
            if (mMediaMuxer == null) {
                logI("Muxer is already released.")
                return
            }

            // 停止Muxer
            logI("Stop mediaMuxer.")
            try {
                mMediaMuxer?.stop()
                mVideoEncoder?.surface?.release()
                surfaceTexture?.release()
                mCallback?.onNewFile(
                    mStartTime,
                    System.currentTimeMillis(),
                    mRecordData.recFile.absolutePath, true
                )
            } catch (e: IllegalStateException) {
                e.printStackTrace()
                logE("Failed to stop mediaMuxer.")
            } finally {
                mMainHandler.removeCallbacksAndMessages(null)
                try {
                    mMediaMuxer?.release()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                mMediaMuxer = null
                mAudioEncoder = null
                mVideoEncoder = null
                isPrepared = false
                isStarted = false
                isRecording = false
                mByteBufferHelper.release()
                logI("Recording is completed!")
                mCallback?.let {
                    mMainHandler.post { it.onFinished(t) }
                }
                mHandlerThread.quit()
                mCallback = null
            }
        }
    }

    companion object {
        const val FRAGMENT_DURATION = 60 * 1000L
    }
}