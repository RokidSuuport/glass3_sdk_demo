package com.rokid.glass.mediastream.capture

data class MediaFailure @JvmOverloads constructor(
    val code: MediaErrorCode,
    val userMessage: String,
    val suggestedAction: String,
    val technicalMessage: String = "",
    val cause: Throwable? = null,
)
