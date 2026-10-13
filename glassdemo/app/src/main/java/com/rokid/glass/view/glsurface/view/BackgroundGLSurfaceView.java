package com.rokid.glass.view.glsurface.view;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;

import com.rokid.glass.view.glsurface.opengl.GLShaderUtil;
import com.rokid.glass.view.glsurface.opengl.GlUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class BackgroundGLSurfaceView extends GLSurfaceView {

    private int mProgram;

    private int av_Position;
    private int af_Position;

    private int myTextureLoc;
    private int muvTextureLoc;

    // 顶点坐标 Buffer
    // Vertex coordinate buffer.
    private FloatBuffer mVertexBuffer;
    private int mVertexBufferId;

    // 纹理坐标 Buffer
    // Texture coordinate buffer.
    private FloatBuffer mTextureBuffer;
    private int mTextureBufferId;

    private int[] mTextureID = new int[2];
    private volatile int mSurfaceWidth;
    private volatile int mSurfaceHeight;
    private volatile int mFrameWidth;
    private volatile int mFrameHeight;
    private volatile boolean mAspectRatioDirty = true;

    private float vertexData[] = {
            // 左下角
            // Bottom-left corner.
            -1f, -1f,
            // 右下角
            // Bottom-right corner.
            1f, -1f,
            // 左上角
            // Top-left corner.
            -1f, 1f,
            // 右上角
            // Top-right corner.
            1f, 1f,
    };

    private float textureData[] = {
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f,
    };

    private PreviewRenderer mRenderer;

    private final int CoordsPerVertexCount = 2;
    private final int VertexCount = vertexData.length / CoordsPerVertexCount;
    private final int VertexStride = CoordsPerVertexCount * 4;
    private final int CoordsPerTextureCount = 2;
    private final int TextureStride = CoordsPerTextureCount * 4;

    private Context mContext;

    public BackgroundGLSurfaceView(Context context) {
        super(context);
        initRenderer(context);
    }

    public BackgroundGLSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initRenderer(context);
    }

    private void initRenderer(Context context) {
        this.mContext = context;
        mRenderer = new PreviewRenderer();
        this.setEGLContextClientVersion(2);
        this.setRenderer(mRenderer);
        this.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    /**
     * 初始化EGL环境
     * Initialize the EGL environment.
     */
    private void initGLEnv() {
        mProgram = GLShaderUtil.createProgram(vertexSource, fragmentSourceNV21);
        initVertexBufferObjects();
        av_Position = GLES20.glGetAttribLocation(mProgram, "av_Position");
        af_Position = GLES20.glGetAttribLocation(mProgram, "af_Position");
        myTextureLoc = GLES20.glGetUniformLocation(mProgram,  "yTexture");
        muvTextureLoc = GLES20.glGetUniformLocation(mProgram,  "uvTexture");
    }

    // 预览数据进行渲染
    // Render the preview data.
    public void setPreviewData(byte[] data, int width, int height) {
        if (mRenderer != null && data != null && data.length > 0) {
            mRenderer.setPreviewData(data, width, height);
            this.requestRender();
        }
    }

    public void releasePreview(){
        if (mRenderer != null){
            mRenderer.releaseBuf();
            requestRender(); // Clear the old image as well as pending frame copies.
        }
    }

    public long getReceivedFrameCount() {
        return mRenderer == null ? 0L : mRenderer.getReceivedFrameCount();
    }

    public long getRenderedFrameCount() {
        return mRenderer == null ? 0L : mRenderer.getRenderedFrameCount();
    }

    public long getDroppedFrameCount() {
        return mRenderer == null ? 0L : mRenderer.getDroppedFrameCount();
    }

    class PreviewRenderer implements Renderer {
        private final Nv21FrameMailbox frameMailbox = new Nv21FrameMailbox();
        private int textureWidth = -1;
        private int textureHeight = -1;

        @Override
        public void onDrawFrame(GL10 gl) {
            if(mTextureID[0] == 0 || mTextureID[1] == 0) {
                mTextureID[0] = GlUtil.GenImageTexture();
                mTextureID[1] = GlUtil.GenImageTexture();
            }

            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);
            Nv21FrameMailbox.Frame frame = frameMailbox.acquireLatest();
            if (frame == null) {
                return;
            }
            int width = frame.getWidth();
            int height = frame.getHeight();
            ByteBuffer yBuffer = frame.getY();
            ByteBuffer uvBuffer = frame.getUv();
            if (mFrameWidth != width || mFrameHeight != height) {
                mFrameWidth = width;
                mFrameHeight = height;
                mAspectRatioDirty = true;
            }

            GLES20.glUseProgram(mProgram);

            if (mAspectRatioDirty) {
                updateFitCenterVertexBuffer();
            }

            // 绑定顶点和纹理坐标
            // Bind vertex and texture coordinates.
            GLES20.glEnableVertexAttribArray(av_Position);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mVertexBufferId);
            GLES20.glVertexAttribPointer(av_Position, CoordsPerVertexCount, GLES20.GL_FLOAT, false, 0, 0);

            GLES20.glEnableVertexAttribArray(af_Position);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mTextureBufferId);
            GLES20.glVertexAttribPointer(af_Position, CoordsPerTextureCount, GLES20.GL_FLOAT, false, 0, 0);

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

            // 绑定Y和UV纹理
            // Bind the Y and UV textures.
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureID[0]);
            yBuffer.position(0);
            if (textureWidth != width || textureHeight != height) {
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, width, height,
                        0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, yBuffer);
            } else {
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height,
                        GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, yBuffer);
            }
            GLES20.glUniform1i(myTextureLoc, 0);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureID[1]);
            uvBuffer.position(0);
            if (textureWidth != width || textureHeight != height) {
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE_ALPHA,
                        width / 2, height / 2, 0, GLES20.GL_LUMINANCE_ALPHA,
                        GLES20.GL_UNSIGNED_BYTE, uvBuffer);
                textureWidth = width;
                textureHeight = height;
            } else {
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width / 2, height / 2,
                        GLES20.GL_LUMINANCE_ALPHA, GLES20.GL_UNSIGNED_BYTE, uvBuffer);
            }
            GLES20.glUniform1i(muvTextureLoc, 1);

            // 绘制 GLES20.GL_TRIANGLE_STRIP:复用坐标
            // Draw with GLES20.GL_TRIANGLE_STRIP to reuse coordinates.
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, VertexCount);

            GLES20.glDisableVertexAttribArray(av_Position);
            GLES20.glDisableVertexAttribArray(af_Position);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        }

        @Override
        public void onSurfaceChanged(GL10 gl, int w, int h) {
            // 当surface的尺寸发生改变时，该方法被调用，。往往在这里设置ViewPort。或者Camara等。
            // Called when the surface size changes; typically configure the viewport or camera here.
            gl.glViewport(0, 0, w, h);
            mSurfaceWidth = w;
            mSurfaceHeight = h;
            mAspectRatioDirty = true;
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            // 该方法在渲染开始前调用，OpenGL ES的绘制上下文被重建时也会调用。
            // Called before rendering starts and whenever the OpenGL ES rendering context is recreated.
            // 当Activity暂停时，绘制上下文会丢失，当Activity恢复时，绘制上下文会重建。
            // The rendering context is lost when the Activity pauses and recreated when it resumes.
            mTextureID[0] = 0;
            mTextureID[1] = 0;
            textureWidth = -1;
            textureHeight = -1;
            initGLEnv();
        }

        /**
         * 设置preview数据
         * Set the preview data.
         * @param data
         * @param width
         * @param height
         */
        public void setPreviewData(byte[] data, int width, int height) {
            frameMailbox.submit(data, width, height);
        }

        public void releaseBuf(){
            frameMailbox.clear();
        }

        public long getReceivedFrameCount() {
            return frameMailbox.getReceivedFrameCount();
        }

        public long getRenderedFrameCount() {
            return frameMailbox.getRenderedFrameCount();
        }

        public long getDroppedFrameCount() {
            return frameMailbox.getDroppedFrameCount();
        }
    }

    private void initVertexBufferObjects() {
        int[] vbo = new int[2];
        GLES20.glGenBuffers(2, vbo, 0);

        mVertexBuffer = ByteBuffer.allocateDirect(vertexData.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(vertexData);
        mVertexBuffer.position(0);
        mVertexBufferId = vbo[0];
        // ARRAY_BUFFER 将使用 Float*Array 而 ELEMENT_ARRAY_BUFFER 必须使用 Uint*Array
        // ARRAY_BUFFER uses Float*Array, while ELEMENT_ARRAY_BUFFER must use Uint*Array.
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mVertexBufferId);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertexData.length * 4, mVertexBuffer, GLES20.GL_DYNAMIC_DRAW);

        mTextureBuffer = ByteBuffer.allocateDirect(textureData.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(textureData);
        mTextureBuffer.position(0);
        mTextureBufferId = vbo[1];
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mTextureBufferId);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, textureData.length * 4, mTextureBuffer, GLES20.GL_STATIC_DRAW);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,0);
    }

    /**
     * 完整显示 NV21 帧，比例不一致的区域保留黑边，避免强制铺满导致拉伸。
     * Display the entire NV21 frame, retaining black bars for aspect ratio differences to avoid stretching.
     */
    private void updateFitCenterVertexBuffer() {
        if (mVertexBuffer == null || mVertexBufferId == 0 ||
                mSurfaceWidth <= 0 || mSurfaceHeight <= 0 ||
                mFrameWidth <= 0 || mFrameHeight <= 0) {
            return;
        }

        float frameAspect = (float) mFrameWidth / mFrameHeight;
        float surfaceAspect = (float) mSurfaceWidth / mSurfaceHeight;
        float scaleX = 1f;
        float scaleY = 1f;
        if (frameAspect > surfaceAspect) {
            scaleY = surfaceAspect / frameAspect;
        } else {
            scaleX = frameAspect / surfaceAspect;
        }

        vertexData[0] = -scaleX;
        vertexData[1] = -scaleY;
        vertexData[2] = scaleX;
        vertexData[3] = -scaleY;
        vertexData[4] = -scaleX;
        vertexData[5] = scaleY;
        vertexData[6] = scaleX;
        vertexData[7] = scaleY;

        mVertexBuffer.clear();
        mVertexBuffer.put(vertexData);
        mVertexBuffer.position(0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mVertexBufferId);
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, vertexData.length * 4, mVertexBuffer);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        mAspectRatioDirty = false;
    }

    private final String vertexSource = "attribute vec4 av_Position; " +
            "attribute vec2 af_Position; " +
            "varying vec2 v_texPo; " +
            "void main() { " +
            "    v_texPo = af_Position; " +
            "    gl_Position = av_Position; " +
            "}";

    // 这里把Y的值调暗-0.2
    // Darken the Y value by subtracting 0.2 here.
    private final String fragmentSourceNV21 = "precision highp float;" +
            "uniform sampler2D yTexture;" +
            "uniform sampler2D uvTexture;" +
            "varying highp vec2 v_texPo;" +
            "void main()" +
            "{" +
            "   float r, g, b, y, u, v;\n" +
            "   y = texture2D(yTexture, v_texPo).r ;\n" +
            "   u = texture2D(uvTexture, v_texPo).a - 0.5;\n" +
            "   v = texture2D(uvTexture, v_texPo).r - 0.5;\n" +
            "   r = y + 1.57481*v;\n" +
            "   g = y - 0.18732*u - 0.46813*v;\n" +
            "   b = y + 1.8556*u;\n" +
            "   gl_FragColor = vec4(r, g, b, 1.0);\n" +
            "}";

}
