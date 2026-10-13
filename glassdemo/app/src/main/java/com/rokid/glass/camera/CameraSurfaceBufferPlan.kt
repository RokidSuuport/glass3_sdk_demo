package com.rokid.glass.camera

import com.rokid.security.glass3.sdk.base.data.media.CameraSurfaceContract

/** A negotiated buffer is immutable for that start; callbacks describe content, not a resize request. */
internal data class CameraSurfaceBufferPlan(
    val contract: Long,
    val width: Int,
    val height: Int,
    val path: Int,
    val callbackUsesCameraSourceSize: Boolean,
) {
    val negotiated: Boolean get() = contract != 0L

    fun legacyWatermarkResize(callbackWidth: Int, callbackHeight: Int): Pair<Int, Int>? =
        if (!negotiated && path == CameraSurfaceContract.PATH_COMPOSED && callbackWidth > 0 && callbackHeight > 0) callbackHeight to callbackWidth else null

    companion object {
        fun resolve(contract: Long, requestedWidth: Int, requestedHeight: Int, hasWatermark: Boolean = true): CameraSurfaceBufferPlan {
            if (contract == 0L) {
                require(requestedWidth > 0 && requestedHeight > 0)
                if (!hasWatermark) return CameraSurfaceBufferPlan(0L, requestedHeight, requestedWidth,
                    CameraSurfaceContract.PATH_DIRECT_CAMERA, true)
                return CameraSurfaceBufferPlan(0L, requestedWidth, requestedHeight,
                    CameraSurfaceContract.PATH_COMPOSED, true)
            }
            val value = requireNotNull(CameraSurfaceContract.decode(contract)) {
                "Unsupported or invalid Surface contract; start aborted"
            }
            return CameraSurfaceBufferPlan(contract, value.bufferWidth, value.bufferHeight,
                value.path, value.callbackUsesCameraSourceSize)
        }
    }
}
