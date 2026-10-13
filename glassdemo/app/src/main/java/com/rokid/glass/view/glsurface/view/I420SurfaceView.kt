package com.rokid.glass.view.glsurface.view

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.rokid.glass.view.glsurface.opengl.GLShaderUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 将导出的 I420 三平面上传到三个 GL 纹理，由 shader 转 RGB 显示；没有另开 Surface 相机输出。
 * 导出接口本身不会显示画面。本组件与 NV21 页的 BackgroundGLSurfaceView 都是 Demo 额外的消费者。
 * submit 在调用线程完成 stride 去填充与复制后返回，不保留 SDK 的共享内存/租约引用。
 * 调用方此后可以 close 租约；不要将借用的 ByteBuffer 直接排队给稍后执行的 GL 任务。
 */
class I420SurfaceView(context: Context) : GLSurfaceView(context) {
    private val mailbox = I420FrameMailbox()
    private val draws = AtomicLong()
    val renderedFrameCount get() = draws.get()
    val receivedFrameCount get() = mailbox.receivedFrameCount
    val droppedFrameCount get() = mailbox.droppedFrameCount

    init {
        setEGLContextClientVersion(2)
        setRenderer(PreviewRenderer())
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun submit(y: ByteBuffer, u: ByteBuffer, v: ByteBuffer, width: Int, height: Int,
        strideY: Int, strideU: Int, strideV: Int) {
        mailbox.submit(y, u, v, width, height, strideY, strideU, strideV)
        requestRender()
    }

    fun clear() {
        mailbox.clear()
        requestRender()
    }

    private inner class PreviewRenderer : Renderer {
        private var program = 0
        private val textures = IntArray(3)
        private val samplers = IntArray(3)
        private var position = 0
        private var coordinates = 0
        private var viewWidth = 0
        private var viewHeight = 0
        private var textureWidth = 0
        private var textureHeight = 0
        private val vertices = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val texCoords = floats(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            // Context loss destroys the old textures/program. Recreate them before drawing again.
            program = GLShaderUtil.createProgram(VERTEX, FRAGMENT)
            check(program != 0) { "Cannot create I420 preview shader" }
            position = GLES20.glGetAttribLocation(program, "position")
            coordinates = GLES20.glGetAttribLocation(program, "coordinates")
            listOf("yPlane", "uPlane", "vPlane").forEachIndexed { index, name ->
                samplers[index] = GLES20.glGetUniformLocation(program, name)
            }
            GLES20.glGenTextures(3, textures, 0)
            textures.forEach { texture ->
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
            textureWidth = 0; textureHeight = 0
            // Chroma row widths need not be a multiple of GL's default four-byte alignment.
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewWidth = width; viewHeight = height
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val frame = mailbox.frameForDraw() ?: return
            if (viewWidth <= 0 || viewHeight <= 0) return
            // Fit the whole exported image; letterbox instead of cropping or stretching.
            val scale = minOf(viewWidth.toFloat() / frame.width, viewHeight.toFloat() / frame.height)
            val width = (frame.width * scale).toInt().coerceAtLeast(1)
            val height = (frame.height * scale).toInt().coerceAtLeast(1)
            GLES20.glViewport((viewWidth - width) / 2, (viewHeight - height) / 2, width, height)
            GLES20.glUseProgram(program)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glEnableVertexAttribArray(coordinates)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices)
            GLES20.glVertexAttribPointer(coordinates, 2, GLES20.GL_FLOAT, false, 0, texCoords)
            val resized = textureWidth != frame.width || textureHeight != frame.height
            listOf(frame.y, frame.u, frame.v).forEachIndexed { index, data ->
                val planeWidth = if (index == 0) frame.width else frame.width / 2
                val planeHeight = if (index == 0) frame.height else frame.height / 2
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + index)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[index])
                if (resized) {
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
                        planeWidth, planeHeight, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, data)
                } else {
                    GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, planeWidth, planeHeight,
                        GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, data)
                }
                GLES20.glUniform1i(samplers[index], index)
            }
            textureWidth = frame.width; textureHeight = frame.height
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(position)
            GLES20.glDisableVertexAttribArray(coordinates)
            GLShaderUtil.checkGlError("I420 preview")
            draws.incrementAndGet()
        }
    }

    private fun floats(vararg values: Float) = ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }

    private companion object {
        const val VERTEX = """
            attribute vec4 position;
            attribute vec2 coordinates;
            varying vec2 uv;
            void main() { gl_Position = position; uv = coordinates; }
        """
        // Use the existing NV21 demo's color conversion for a comparable preview. The current
        // lease has no color-space/range metadata; production consumers must agree on that separately.
        const val FRAGMENT = """
            precision mediump float;
            uniform sampler2D yPlane;
            uniform sampler2D uPlane;
            uniform sampler2D vPlane;
            varying vec2 uv;
            void main() {
                float y = texture2D(yPlane, uv).r;
                float u = texture2D(uPlane, uv).r - 0.5;
                float v = texture2D(vPlane, uv).r - 0.5;
                gl_FragColor = vec4(y + 1.57481 * v,
                    y - 0.18732 * u - 0.46813 * v, y + 1.8556 * u, 1.0);
            }
        """
    }
}
