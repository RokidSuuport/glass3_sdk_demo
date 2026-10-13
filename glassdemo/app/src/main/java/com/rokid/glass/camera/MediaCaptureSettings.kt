package com.rokid.glass.camera

import android.content.Context
import com.rokid.security.glass3.sdk.base.data.media.PhotoResolution
import kotlin.math.roundToInt

internal data class CaptureSize(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) }
    override fun toString() = "$width×$height"
}

internal enum class MediaSettingsTarget { PHOTO, VIDEO }

/**
 * 拍照和录像分别保存请求参数，编辑/重置一方不会覆盖另一方。
 * Photo and video requests have independent settings; actual output still comes from SDK callbacks.
 * 旧 zoomCamera 仍是 SDK 的共享相机控制，独立保存不意味着旧接口具有独立硬件会话。
 * Legacy zoomCamera still controls the shared camera, not an isolated hardware session.
 */
internal data class MediaCaptureSettings(
    val photoApi: CameraMediaController.Api = CameraMediaController.Api.OPTIONS_V2,
    val videoApi: CameraMediaController.Api = CameraMediaController.Api.OPTIONS_V2,
    val photo: CaptureSize = CaptureSize(1280, 720),
    val video: CaptureSize = CaptureSize(1280, 720),
    val photoZoom: Float = 1f,
    val videoZoom: Float = 1f,
    val quality: Int = 90,
    val reusePhotoResolution: Boolean = false,
    val recordWatermark: Boolean = false,
) {
    init {
        require(photoZoom.isFinite() && photoZoom >= 1f)
        require(videoZoom.isFinite() && videoZoom >= 1f)
        require(quality in 1..100)
    }

    fun api(target: MediaSettingsTarget) = if (target == MediaSettingsTarget.PHOTO) photoApi else videoApi
    fun zoom(target: MediaSettingsTarget) = if (target == MediaSettingsTarget.PHOTO) photoZoom else videoZoom
    fun withZoom(target: MediaSettingsTarget, value: Float) =
        if (target == MediaSettingsTarget.PHOTO) copy(photoZoom = value) else copy(videoZoom = value)

    fun withApi(target: MediaSettingsTarget, value: CameraMediaController.Api): MediaCaptureSettings {
        val legacy = value == CameraMediaController.Api.LEGACY
        return if (target == MediaSettingsTarget.PHOTO) copy(
            photoApi = value,
            photo = if (legacy && legacyPhotoType(photo) == null) CaptureSize(1280, 720) else photo,
            photoZoom = if (legacy) photoZoom.roundToInt().toFloat() else photoZoom,
        ) else copy(videoApi = value, videoZoom = if (legacy) videoZoom.roundToInt().toFloat() else videoZoom)
    }

    fun reset(target: MediaSettingsTarget): MediaCaptureSettings {
        val defaults = MediaCaptureSettings()
        return if (target == MediaSettingsTarget.PHOTO) copy(
            photoApi = defaults.photoApi, photo = defaults.photo, photoZoom = defaults.photoZoom,
            quality = defaults.quality, reusePhotoResolution = defaults.reusePhotoResolution,
        ) else copy(videoApi = defaults.videoApi, video = defaults.video, videoZoom = defaults.videoZoom,
            recordWatermark = defaults.recordWatermark)
    }

    companion object {
        // 保留原 takePhoto(resolutionType) 的尺寸映射，旧模式不偷偷调用 V2/Config。
        val legacyPhotoSizes = listOf(PhotoResolution.RESOLUTION_480P, PhotoResolution.RESOLUTION_720P,
            PhotoResolution.RESOLUTION_1080P, PhotoResolution.RESOLUTION_1080P_Land, PhotoResolution.RESOLUTION_4K)
            .associateWith { type ->
                val source = checkNotNull(PhotoResolution.getResolutionInfo(type))
                CaptureSize(source.height, source.width)
            }
        fun legacyPhotoType(size: CaptureSize): Int? = legacyPhotoSizes.entries.firstOrNull { it.value == size }?.key
    }
}

internal class MediaCaptureSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("media_capture_settings", Context.MODE_PRIVATE)
    fun load(): MediaCaptureSettings = runCatching {
        MediaCaptureSettings(
            // 迁移原共用参数，首次拆分后保留用户原来的选择。
            // Seed each scope from the previous shared settings on upgrade.
            photoApi = readApi("photo_api"), videoApi = readApi("video_api"),
            photo = CaptureSize(preferences.getInt("photo_width", 1280), preferences.getInt("photo_height", 720)),
            video = CaptureSize(preferences.getInt("video_width", 1280), preferences.getInt("video_height", 720)),
            photoZoom = preferences.getFloat("photo_zoom", preferences.getFloat("zoom", 1f)),
            videoZoom = preferences.getFloat("video_zoom", preferences.getFloat("zoom", 1f)),
            quality = preferences.getInt("quality", 90),
            reusePhotoResolution = preferences.getBoolean("reuse", false),
            recordWatermark = preferences.getBoolean("watermark", false),
        ).let { it.withApi(MediaSettingsTarget.PHOTO, it.photoApi).withApi(MediaSettingsTarget.VIDEO, it.videoApi) }
    }.getOrElse { MediaCaptureSettings() }

    private fun readApi(key: String) = CameraMediaController.Api.valueOf(
        preferences.getString(key, preferences.getString("api", "OPTIONS_V2"))!!)

    fun save(value: MediaCaptureSettings) {
        preferences.edit().putString("photo_api", value.photoApi.name).putString("video_api", value.videoApi.name)
            .putInt("photo_width", value.photo.width).putInt("photo_height", value.photo.height)
            .putInt("video_width", value.video.width).putInt("video_height", value.video.height)
            .putFloat("photo_zoom", value.photoZoom).putFloat("video_zoom", value.videoZoom).putInt("quality", value.quality)
            .putBoolean("reuse", value.reusePhotoResolution).putBoolean("watermark", value.recordWatermark).apply()
    }
}
