package com.rokid.glass.media

import android.media.MediaFormat

enum class MediaType(val ext: String, val mimeType: String) {

    AUDIO("m4a", MediaFormat.MIMETYPE_AUDIO_AAC),

    VIDEO("mp4", MediaFormat.MIMETYPE_VIDEO_AVC),
}