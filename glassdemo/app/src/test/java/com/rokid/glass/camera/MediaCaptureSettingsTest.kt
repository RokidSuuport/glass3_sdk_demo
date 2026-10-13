package com.rokid.glass.camera

import org.junit.Assert.*
import org.junit.Test

class MediaCaptureSettingsTest {
    @Test fun switchingToLegacyUsesARealPresetAndIntegerZoom() {
        val settings = MediaCaptureSettings(photo = CaptureSize(960, 540), video = CaptureSize(800, 600), photoZoom = 1.575f, videoZoom = 1.25f)
        val legacy = settings.withApi(MediaSettingsTarget.PHOTO, CameraMediaController.Api.LEGACY)
        assertEquals(CaptureSize(1280, 720), legacy.photo)
        assertEquals(2, MediaCaptureSettings.legacyPhotoType(legacy.photo))
        assertEquals(2f, legacy.photoZoom)
        assertEquals(CaptureSize(800, 600), legacy.video)
        assertEquals(1.25f, legacy.videoZoom)
        assertEquals(CameraMediaController.Api.OPTIONS_V2, legacy.videoApi)
    }

    @Test fun legacyVideoModeDoesNotChangeCustomPhotoOrPhotoZoom() {
        val settings = MediaCaptureSettings(photo = CaptureSize(960, 540), photoZoom = 1.25f, videoZoom = 1.575f)
        val legacy = settings.withApi(MediaSettingsTarget.VIDEO, CameraMediaController.Api.LEGACY)
        assertEquals(2f, legacy.videoZoom)
        assertEquals(settings.photo, legacy.photo)
        assertEquals(settings.photoZoom, legacy.photoZoom)
        assertEquals(settings.photoApi, legacy.photoApi)
    }

    @Test fun zoomAndResetOnlyChangeTheSelectedCaptureType() {
        val settings = MediaCaptureSettings(photo = CaptureSize(960, 540), video = CaptureSize(800, 600),
            photoZoom = 1.25f, videoZoom = 1.575f, quality = 80, reusePhotoResolution = true, recordWatermark = true)
        val changed = settings.withZoom(MediaSettingsTarget.PHOTO, 4f)
        assertEquals(4f, changed.photoZoom)
        assertEquals(settings.videoZoom, changed.videoZoom)
        val photoReset = settings.reset(MediaSettingsTarget.PHOTO)
        assertEquals(MediaCaptureSettings().photo, photoReset.photo)
        assertEquals(1f, photoReset.photoZoom)
        assertEquals(90, photoReset.quality)
        assertFalse(photoReset.reusePhotoResolution)
        assertEquals(settings.video, photoReset.video)
        assertEquals(settings.videoZoom, photoReset.videoZoom)
        assertTrue(photoReset.recordWatermark)
        val videoReset = settings.reset(MediaSettingsTarget.VIDEO)
        assertEquals(MediaCaptureSettings().video, videoReset.video)
        assertEquals(1f, videoReset.videoZoom)
        assertFalse(videoReset.recordWatermark)
        assertEquals(settings.photo, videoReset.photo)
        assertEquals(settings.photoZoom, videoReset.photoZoom)
        assertEquals(settings.quality, videoReset.quality)
        assertTrue(videoReset.reusePhotoResolution)
    }
}
