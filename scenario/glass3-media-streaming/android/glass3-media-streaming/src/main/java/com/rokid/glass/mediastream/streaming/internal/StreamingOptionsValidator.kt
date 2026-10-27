package com.rokid.glass.mediastream.streaming.internal

import com.rokid.glass.mediastream.streaming.StreamingOptions
import com.rokid.glass.mediastream.transport.signaling.validateSignalingUrl

internal object StreamingOptionsValidator {
    private val roomIdPattern = Regex("[A-Za-z0-9_-]{1,32}")

    fun validate(options: StreamingOptions): StreamingOptions {
        require(options.videoEnabled || options.audioEnabled) {
            "At least one media source must be enabled"
        }
        require(roomIdPattern.matches(options.roomId)) {
            "roomId must contain 1-32 letters, digits, underscores, or hyphens"
        }
        require(options.maxVideoBitrateBps == null || options.maxVideoBitrateBps > 0) {
            "maxVideoBitrateBps must be positive"
        }
        return options.copy(serverUrl = validateSignalingUrl(options.serverUrl))
    }
}
