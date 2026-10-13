package com.rokid.glass.camera

import android.os.Handler
import android.os.Looper
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.camera.CameraZoomSession
import com.rokid.security.glass3.open.sdk.uitls.log.L
import com.rokid.security.glass3.sdk.base.data.media.CameraZoomState
import com.rokid.security.glass3.sdk.base.data.media.PhotoResolution
import com.rokid.security.glass3.sdk.base.data.media.RecordConfig
import com.rokid.security.system.server.media.ICameraPhotoCallback
import com.rokid.security.system.server.media.MediaCapabilityProbe
import com.rokid.security.system.server.media.callback.VideoCallback
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.RejectedExecutionException

/** Serial media operations owned by the existing photo/video page. */
internal class CameraMediaController(private val show: (String) -> Unit) {
    enum class Api { LEGACY, OPTIONS_V2 }
    private val worker = Executors.newSingleThreadExecutor()
    private val epoch = AtomicLong()
    private val photoEpoch = AtomicLong()
    private val main = Handler(Looper.getMainLooper())
    private val submissionLock = Any()
    private var apiChangePending = false
    @Volatile private var active = false
    @Volatile private var record: CameraZoomSession? = null
    private var legacyRecording = false
    private class RecordingRequest(val pageEpoch: Long)
    private var recordRequest: RecordingRequest? = null
    private val stoppingRequests = linkedSetOf<RecordingRequest>()
    private var photoPath: String? = null
    private var photoApi: Api? = null
    private var pendingPhotoToken = 0L
    private var photoTimeout: Runnable? = null
    private var lastLegacyPhotoPath: String? = null

    fun resume() { active = true }
    fun pause() {
        active = false; epoch.incrementAndGet(); photoEpoch.incrementAndGet()
        worker.execute {
            try { stopOwnedRecord() } catch (error: Exception) {
                L.w("CameraDemo", "media cleanup error=${error.javaClass.simpleName}")
            } finally {
                photoPath = null
                photoApi = null
                lastLegacyPhotoPath = null
                pendingPhotoToken = 0L
                photoTimeout?.let(main::removeCallbacks)
                photoTimeout = null
                L.i("CameraDemo", "media page cleanup complete")
            }
        }
    }
    fun close() { worker.shutdown() }
    /** Reserve the mode change until its main-thread callback has applied the new UI mode. */
    fun changeApi(onReady: () -> Unit) {
        synchronized(submissionLock) {
            if (!active || apiChangePending) return
            apiChangePending = true
            val token = epoch.get()
            try {
                worker.execute {
                    if (!active || token != epoch.get()) {
                        synchronized(submissionLock) { apiChangePending = false }
                        return@execute
                    }
                    val busy = when {
                        photoPath != null -> "wait for the photo result"
                        record != null || legacyRecording || recordRequest != null -> "stop recording first"
                        stoppingRequests.isNotEmpty() -> "wait for the recording's final callback"
                        else -> null
                    }
                    if (busy != null) {
                        emit("API mode unchanged: $busy")
                        synchronized(submissionLock) { apiChangePending = false }
                    } else {
                        main.post {
                            synchronized(submissionLock) {
                                try { if (active && token == epoch.get()) onReady() }
                                finally { apiChangePending = false }
                            }
                        }
                    }
                }
            } catch (_: RejectedExecutionException) { apiChangePending = false }
        }
    }
    private fun submit(block: () -> Unit) {
        synchronized(submissionLock) {
            if (!active) return
            if (apiChangePending) {
                emit("Media request ignored: wait for the API mode change")
                return
            }
            val token = epoch.get()
            try { worker.execute { if (active && token == epoch.get()) runCatching(block).onFailure { emit("Media error: ${it.javaClass.simpleName}; ${it.message.orEmpty().take(120)}") } } }
            catch (_: RejectedExecutionException) { /* Page has been destroyed. */ }
        }
    }
    private fun emit(text: String) {
        L.i("CameraDemo", text)
        if (active) main.post { if (active) show(text) }
    }
    private fun service() = checkNotNull(GlassSdk.getGlassMediaService()) { "Media service not connected" }

    /** Optional diagnostics must never turn a supported legacy operation into a failed request. */
    private fun diagnose(operation: String, phase: String) {
        runCatching {
            val media = runCatching { service() }.getOrElse {
                emit("Media diagnostic operation=$operation phase=$phase unavailable=${it.javaClass.simpleName}")
                return
            }
            val capabilities = runCatching { MediaCapabilityProbe.read(media).toString() }
                .getOrElse { "unavailable(${it.javaClass.simpleName})" }
            val state = runCatching { cameraApiState(record?.refreshState() ?: media.cameraZoomState) }
                .getOrElse { "unavailable(${it.javaClass.simpleName})" }
            emit("Media diagnostic operation=$operation phase=$phase capabilities=$capabilities currentSharedState=[$state]")
        }.onFailure { L.w("CameraDemo", "optional media diagnostic failed type=${it.javaClass.simpleName}") }
    }

    fun photo(api: Api, resolution: Int, path: String, zoom: Float, quality: Int, reuse: Boolean) {
        val info = checkNotNull(PhotoResolution.getResolutionInfo(resolution))
        photo(api, info.height, info.width, path, zoom, quality, reuse)
    }

    /** V2 accepts the requested output width/height directly, including custom sizes. */
    fun photo(api: Api, outputWidth: Int, outputHeight: Int, path: String, zoom: Float, quality: Int, reuse: Boolean) = submit {
        check(photoPath == null) { "Photo request is pending" }
        if (api == Api.OPTIONS_V2) require(quality in 1..100)
        val size = CaptureSize(outputWidth, outputHeight)
        val resolution = if (api == Api.LEGACY) checkNotNull(MediaCaptureSettings.legacyPhotoType(size)) {
            "Legacy takePhoto supports preset resolutions only"
        } else null
        val token = photoEpoch.incrementAndGet()
        pendingPhotoToken = token
        photoPath = path
        photoApi = api
        lastLegacyPhotoPath = null
        val timeout = Runnable { photoComplete(token, "Photo result timed out path=$path; no automatic retry") }
        photoTimeout = timeout; main.postDelayed(timeout, 30_000)
        val parameters = when (api) {
            Api.LEGACY -> "method=IMediaServer.takePhoto resolutionType=$resolution"
            Api.OPTIONS_V2 -> "method=CameraMediaOptions.takePhoto/IMediaServer.takePhotoWithOptions requestedOutput=${outputWidth}x${outputHeight} requestedZoom=$zoom jpegQuality=$quality reuseOpenedResolution=$reuse"
        }
        emit("Photo request api=$api $parameters path=$path")
        diagnose("photo", "before")
        try {
            when (api) {
                Api.LEGACY -> {
                    // 旧模式保存的整数倍率仍通过旧 zoomCamera 接口提交，不引入 V2 控制句柄。
                    service().zoomCamera(zoom.toInt())
                    CameraMediaCalls.takeLegacyPhoto(checkNotNull(resolution), path)
                }
                Api.OPTIONS_V2 -> CameraMediaCalls.takeOptionsPhoto(outputWidth, outputHeight, zoom, reuse, path, quality,
                    object : ICameraPhotoCallback.Stub() {
                        override fun onPhoto(path: String?, width: Int, height: Int, state: CameraZoomState?) {
                            photoComplete(token, "Photo complete path=$path actualOutput=${width}x$height captureState=[${cameraApiState(state)}]")
                        }
                        override fun onError(code: Int, reason: String?) { photoComplete(token, "Photo error=$code path=$path") }
                    })
            }
            emit("Photo call returned; waiting for the file callback")
        } catch (error: Exception) {
            main.removeCallbacks(timeout)
            photoTimeout = null; photoPath = null; photoApi = null; pendingPhotoToken = 0L
            throw error
        } finally { diagnose("photo", "after_call") }
    }

    fun legacyPhotoCompleted(path: String, width: Int? = null, height: Int? = null) {
        // Existing registered PhotoFileCallback owns V1/legacy photo results.
        if (!active) return
        val pageEpoch = epoch.get()
        try { worker.execute {
            if (!active || pageEpoch != epoch.get()) return@execute
            if (photoApi == Api.OPTIONS_V2) return@execute
            val dimensions = if (width != null && height != null && width > 0 && height > 0) "${width}x$height" else null
            if (path == photoPath) {
                lastLegacyPhotoPath = path
                if (dimensions != null) emit("Photo dimensions callback path=$path actualOutput=$dimensions")
                photoComplete(pendingPhotoToken, "Photo complete path=$path actualOutput=${dimensions ?: "not provided by this callback"} captureState=not provided")
            } else if (path == lastLegacyPhotoPath && dimensions != null) {
                // Current services send the old path-only callback before the V2 dimensions callback.
                emit("Photo dimensions callback path=$path actualOutput=$dimensions")
            }
        } }
        catch (_: RejectedExecutionException) { /* Late legacy callback. */ }
    }
    private fun photoComplete(token: Long, text: String) {
        if (!photoEpoch.compareAndSet(token, token + 1)) return
        photoTimeout?.let(main::removeCallbacks)
        try { worker.execute {
            if (pendingPhotoToken != token) return@execute
            photoPath = null; photoApi = null; pendingPhotoToken = 0L; photoTimeout = null; emit(text)
            diagnose("photo", "result")
        } }
        catch (_: RejectedExecutionException) { /* Late request callback. */ }
    }

    fun startRecord(api: Api, config: RecordConfig, zoom: Float, watermark: Boolean, callback: VideoCallback) = submit {
        check(record == null && !legacyRecording) { "Recording is already active" }
        val request = RecordingRequest(epoch.get())
        recordRequest = request
        val ownedCallback = object : VideoCallback.Stub() {
            override fun onStart() = recordingEvent(request) { callback.onStart() }
            override fun onNewFile(startTime: Long, endTime: Long, path: String, isLast: Boolean) =
                recordingEvent(request) { callback.onNewFile(startTime, endTime, path, isLast) }
            override fun onFinish() = recordingEvent(request, true) { callback.onFinish() }
            override fun onError() = recordingEvent(request, true) { callback.onError() }
            override fun onErrorWithDetail(code: Int, errorMsg: String) =
                recordingEvent(request, true) { callback.onErrorWithDetail(code, errorMsg) }
        }
        val parameters = when (api) {
            Api.LEGACY -> "method=IMediaServer.startRecord"
            Api.OPTIONS_V2 -> "method=CameraMediaOptions.startRecord/IMediaServer.startRecordWithOptions requestedZoom=$zoom watermark=$watermark"
        }
        emit("Recording request api=$api $parameters config=$config")
        diagnose("record.start", "before")
        when (api) {
            Api.LEGACY -> {
                legacyRecording = true
                try {
                    service().zoomCamera(zoom.toInt())
                    CameraMediaCalls.startLegacyRecord(ownedCallback, config)
                }
                catch (error: Exception) { legacyRecording = false; recordRequest = null; throw error }
                finally { diagnose("record.start", "after_call") }
            }
            Api.OPTIONS_V2 -> {
                val session = CameraZoomSession()
                session.listener = object : CameraZoomSession.Listener {
                    override fun onStateChanged(state: CameraZoomState) { if (record === session) emit("Recording state: ${cameraApiState(state)}") }
                    override fun onError(code: Int, reason: String) { if (record === session) emit("Recording session error=$code") }
                }
                record = session
                try { CameraMediaCalls.startOptionsRecord(ownedCallback, config, zoom, watermark, session) }
                catch (error: Exception) {
                    record = null; recordRequest = null; session.listener = null
                    runCatching { session.close() }
                    throw error
                } finally { diagnose("record.start", "after_call") }
            }
        }
    }
    private fun recordingEvent(request: RecordingRequest, terminal: Boolean = false, callback: () -> Unit) {
        try { worker.execute {
            // A terminal event from an older recording must never clear the next recording.
            val isCurrent = recordRequest === request
            if (!isCurrent && request !in stoppingRequests) return@execute
            if (terminal) {
                stoppingRequests.remove(request)
                if (isCurrent) {
                    recordRequest = null; legacyRecording = false
                    val finished = record; record = null
                    finished?.listener = null
                    runCatching { finished?.close() }
                        .onFailure { L.w("CameraDemo", "finished recording cleanup error=${it.javaClass.simpleName}") }
                }
                diagnose("record", "terminal_callback")
            }
            if (active) main.post { if (active && request.pageEpoch == epoch.get()) callback() }
        } } catch (_: RejectedExecutionException) { /* Callback arrived after page disposal. */ }
    }
    fun stopRecord() = submit {
        if (stopOwnedRecord()) emit("Recording stop requested; waiting for the final file callback")
        else emit("No active recording to stop")
    }
    private fun stopOwnedRecord(): Boolean {
        if (record == null && !legacyRecording && recordRequest == null) return false
        val method = if (record != null) "CameraZoomSession.close" else "IMediaServer.stopRecord"
        emit("Recording stop request method=$method")
        diagnose("record.stop", "before")
        // Keep stopped requests until their terminal callback so manual stop still reports
        // onNewFile(isLast) and onFinish, even when a newer recording is already starting.
        recordRequest?.let { request ->
            stoppingRequests.add(request)
            if (stoppingRequests.size > 4) stoppingRequests.remove(stoppingRequests.first())
        }
        recordRequest = null
        val session = record; record = null
        val stopLegacy = legacyRecording; legacyRecording = false
        session?.listener = null
        try { session?.close() }
        finally {
            try { if (stopLegacy) service().stopRecord() }
            finally { diagnose("record.stop", "after_call") }
        }
        return true
    }
    fun zoom(value: Float, options: Boolean) = submit {
        val session = if (options) checkNotNull(record) { "Start Options recording first" } else null
        emit(if (options) "Zoom request method=CameraZoomSession.setZoom requestedZoom=$value"
            else "Zoom request method=IMediaServer.zoomCamera zoomLevel=${value.toInt()}")
        diagnose("zoom", "before")
        try {
            if (session != null) emit("Recording setZoom result=${session.setZoom(value)}; 0 means accepted, check ready/applied")
            else { service().zoomCamera(value.toInt()); emit("Legacy zoom call returned") }
        } finally { diagnose("zoom", "after_call") }
    }
    fun followShared() = submit {
        val session = checkNotNull(record) { "Start Options recording first" }
        emit("Zoom request method=CameraZoomSession.followShared; withdraw this recording's explicit zoom")
        diagnose("zoom.follow_shared", "before")
        try { emit("Recording followShared result=${session.followShared()}; 0 means accepted, check ready/applied") }
        finally { diagnose("zoom.follow_shared", "after_call") }
    }
}
