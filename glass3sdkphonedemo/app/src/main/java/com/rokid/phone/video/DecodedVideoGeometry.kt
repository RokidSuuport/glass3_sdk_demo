package com.rokid.phone.video

/** MediaCodec 输出格式对应的实际可见画面尺寸。 */
data class DecodedVideoGeometry(
    val codedWidth: Int,
    val codedHeight: Int,
    val visibleWidth: Int,
    val visibleHeight: Int,
    val rotationDegrees: Int,
) {
    val displayWidth: Int
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) visibleHeight else visibleWidth

    val displayHeight: Int
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) visibleWidth else visibleHeight

    companion object {
        fun create(
            codedWidth: Int,
            codedHeight: Int,
            cropLeft: Int? = null,
            cropTop: Int? = null,
            cropRight: Int? = null,
            cropBottom: Int? = null,
            rotationDegrees: Int = 0,
        ): DecodedVideoGeometry {
            val visibleWidth = if (cropLeft != null && cropRight != null && cropRight >= cropLeft) {
                cropRight - cropLeft + 1
            } else {
                codedWidth
            }
            val visibleHeight = if (cropTop != null && cropBottom != null && cropBottom >= cropTop) {
                cropBottom - cropTop + 1
            } else {
                codedHeight
            }
            return DecodedVideoGeometry(
                codedWidth = codedWidth,
                codedHeight = codedHeight,
                visibleWidth = visibleWidth,
                visibleHeight = visibleHeight,
                rotationDegrees = ((rotationDegrees % 360) + 360) % 360,
            )
        }
    }
}
