package com.rokid.industry.hazardrecognition

import android.content.Context
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 用例：XML 放置本控件，相机回调调用 submit(已复制的帧)，停止相机时调用 submit(null)。
 * 宿主 Activity 需转发 onResume/onPause。预览始终取最新帧，不积压历史帧。
 */
class Nv21PreviewView(context: Context, attrs: AttributeSet? = null) : GLSurfaceView(context, attrs) {
    @Volatile private var frame: Nv21Frame? = null
    private val renderer = PreviewRenderer()

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY // 新帧到达才请求绘制，避免静止时持续空转。
    }

    // 可从相机回调线程提交；只交换帧引用，所有 OpenGL 操作仍在 GL 渲染线程执行。
    fun submit(value: Nv21Frame?) {
        frame = value
        requestRender()
    }

    private inner class PreviewRenderer : Renderer {
        private var program = 0
        private var surfaceWidth = 1
        private var surfaceHeight = 1
        private val textures = IntArray(2)
        private var pixels = ByteBuffer.allocateDirect(0)
        private val vertices = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private val coordinates = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)); position(0) }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            // 用例：GL 上下文丢失后会重新进入这里，着色器和纹理 ID 必须重新创建。
            val vertex = shader(GL_VERTEX_SHADER, """
                attribute vec2 position; attribute vec2 coordinate;
                varying vec2 uv;
                void main() { gl_Position = vec4(position, 0.0, 1.0); uv = coordinate; }
            """)
            // NV21 的 V/U 分别放在双通道纹理的 r/a 中，由片元着色器转换成屏幕 RGB。
            val fragment = shader(GL_FRAGMENT_SHADER, """
                precision mediump float;
                uniform sampler2D yTexture; uniform sampler2D vuTexture;
                varying vec2 uv;
                void main() {
                    float y = 1.1643 * (texture2D(yTexture, uv).r - 0.0625);
                    vec4 vu = texture2D(vuTexture, uv);
                    float v = vu.r - 0.5; float u = vu.a - 0.5;
                    gl_FragColor = vec4(y + 1.5958*v, y - 0.39173*u - 0.81290*v, y + 2.017*u, 1.0);
                }
            """)
            program = glCreateProgram()
            glAttachShader(program, vertex)
            glAttachShader(program, fragment)
            glLinkProgram(program)
            val linked = IntArray(1)
            glGetProgramiv(program, GL_LINK_STATUS, linked, 0)
            check(linked[0] != 0) { glGetProgramInfoLog(program) }
            glDeleteShader(vertex)
            glDeleteShader(fragment)
            glGenTextures(2, textures, 0)
            textures.forEach {
                glBindTexture(GL_TEXTURE_2D, it)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            }
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            surfaceWidth = width.coerceAtLeast(1)
            surfaceHeight = height.coerceAtLeast(1)
            glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            glClearColor(0f, 0f, 0f, 1f)
            glClear(GL_COLOR_BUFFER_BIT)
            val current = frame ?: return
            // 用例：小窗宽高比与相机不同就留黑边，保持原始比例，不拉伸现场物体。
            val ratio = current.width.toFloat() / current.height
            val screen = surfaceWidth.toFloat() / surfaceHeight
            val x = if (ratio < screen) ratio / screen else 1f
            val y = if (ratio > screen) screen / ratio else 1f
            vertices.clear()
            vertices.put(floatArrayOf(-x, -y, x, -y, -x, y, x, y)).position(0)
            // 分辨率不变时复用直接缓冲区，减少持续预览时的内存分配。
            if (pixels.capacity() != current.bytes.size) pixels = ByteBuffer.allocateDirect(current.bytes.size)
            pixels.clear()
            pixels.put(current.bytes).position(0)
            glUseProgram(program)
            val position = glGetAttribLocation(program, "position")
            val coordinate = glGetAttribLocation(program, "coordinate")
            glEnableVertexAttribArray(position)
            glEnableVertexAttribArray(coordinate)
            glVertexAttribPointer(position, 2, GL_FLOAT, false, 0, vertices)
            glVertexAttribPointer(coordinate, 2, GL_FLOAT, false, 0, coordinates)
            // 第一张纹理上传完整 Y 平面；第二张上传半宽、半高的 VU 平面。
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_2D, textures[0])
            glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, current.width, current.height,
                0, GL_LUMINANCE, GL_UNSIGNED_BYTE, pixels)
            glUniform1i(glGetUniformLocation(program, "yTexture"), 0)
            pixels.position(current.width * current.height)
            glActiveTexture(GL_TEXTURE1)
            glBindTexture(GL_TEXTURE_2D, textures[1])
            glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE_ALPHA, current.width / 2, current.height / 2,
                0, GL_LUMINANCE_ALPHA, GL_UNSIGNED_BYTE, pixels)
            glUniform1i(glGetUniformLocation(program, "vuTexture"), 1)
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
            glDisableVertexAttribArray(position)
            glDisableVertexAttribArray(coordinate)
        }

        private fun shader(type: Int, source: String): Int {
            val id = glCreateShader(type)
            glShaderSource(id, source)
            glCompileShader(id)
            val compiled = IntArray(1)
            glGetShaderiv(id, GL_COMPILE_STATUS, compiled, 0)
            check(compiled[0] != 0) { glGetShaderInfoLog(id) }
            return id
        }
    }
}
