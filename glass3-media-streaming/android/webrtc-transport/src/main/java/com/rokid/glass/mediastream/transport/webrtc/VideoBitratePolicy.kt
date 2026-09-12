package com.rokid.glass.mediastream.transport.webrtc

import livekit.org.webrtc.RtpParameters

internal fun configureVideoBitrate(
    maxVideoBitrateBps: Int?,
    readParameters: () -> RtpParameters,
    writeParameters: (RtpParameters) -> Boolean,
) {
    if (maxVideoBitrateBps == null) return
    require(maxVideoBitrateBps > 0) { "maxVideoBitrateBps must be positive" }
    val parameters = readParameters()
    check(parameters.encodings.isNotEmpty()) {
        "Video sender has no encodings for maxVideoBitrateBps=$maxVideoBitrateBps"
    }
    parameters.encodings.forEach { it.maxBitrateBps = maxVideoBitrateBps }
    check(writeParameters(parameters)) {
        "WebRTC rejected maxVideoBitrateBps=$maxVideoBitrateBps"
    }
}
