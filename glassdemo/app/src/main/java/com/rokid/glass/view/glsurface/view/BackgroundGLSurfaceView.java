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

    //顶点坐标 Buffer
    private FloatBuffer mVertexBuffer;
    private int mVertexBufferId;

    //纹理坐标 Buffer
    private FloatBuffer mTextureBuffer;
    private int mTextureBufferId;

    private int[] mTextureID = new int[2];

    private float vertexData[] = {
            -1f, -1f,// 左下角
            1f, -1f, // 右下角
            -1f, 1f, // 左上角
            1f, 1f,  // 右上角
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
//        this.setDebugFlags(DEBUG_CHECK_GL_ERROR | DEBUG_LOG_GL_CALLS);
        mRenderer = new PreviewRenderer();
        this.setEGLContextClientVersion(2);
        this.setRenderer(mRenderer);
        this.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    /**
     * 初始化EGL环境
     */
    private void initGLEnv() {
        mProgram = GLShaderUtil.createProgram(vertexSource, fragmentSourceNV21);
        initVertexBufferObjects();
        av_Position = GLES20.glGetAttribLocation(mProgram, "av_Position");
        af_Position = GLES20.glGetAttribLocation(mProgram, "af_Position");
        myTextureLoc = GLES20.glGetUniformLocation(mProgram,  "yTexture");
        muvTextureLoc = GLES20.glGetUniformLocation(mProgram,  "uvTexture");
//        Logger.d( "BackgroundGLSurfaceView: initEGL : mProgram="+mProgram
//                +", av_Position="+av_Position+", af_Position="+af_Position
//                +", myTextureLoc="+myTextureLoc+", muvTextureLoc="+muvTextureLoc); // 这里有可能为空
    }

    // 预览数据进行渲染
    public synchronized void setPreviewData(byte[] data, int width, int height) {
        if (mRenderer != null && data != null && data.length > 0) {
            mRenderer.setPreviewData(data, width, height);
            this.requestRender();
        }
    }

    public synchronized void releasePreview(){
        if (mRenderer != null){
            mRenderer.releaseBuf();
//            mRenderer = null;
        }
    }

    class PreviewRenderer implements Renderer {
        private volatile ByteBuffer yFloatBuffer;
        private volatile ByteBuffer uvFloatBuffer;

        private int width;
        private int height;

        @Override
        public void onDrawFrame(GL10 gl) {
            if(mTextureID[0] == 0 || mTextureID[1] == 0) {
                mTextureID[0] = GlUtil.GenImageTexture();
                mTextureID[1] = GlUtil.GenImageTexture();
            }

            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);
            if (yFloatBuffer == null || uvFloatBuffer == null) {
                return;
            }

            //Logger.d( "BackgroundGLSurfaceView: onDrawFrame() mProgram="+mProgram+", width="+width+", height="+height+", mTextureID[0]="+mTextureID[0]+", mTextureID[1]="+mTextureID[1]);
            GLES20.glUseProgram(mProgram);

            // 绑定顶点和纹理坐标
            GLES20.glEnableVertexAttribArray(av_Position);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mVertexBufferId);
            GLES20.glVertexAttribPointer(av_Position, CoordsPerVertexCount, GLES20.GL_FLOAT, false, 0, 0);

            GLES20.glEnableVertexAttribArray(af_Position);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mTextureBufferId);
            GLES20.glVertexAttribPointer(af_Position, CoordsPerTextureCount, GLES20.GL_FLOAT, false, 0, 0);

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

            // 绑定Y和UV纹理
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureID[0]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, width, height, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, yFloatBuffer);
            GLES20.glUniform1i(myTextureLoc, 0);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureID[1]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE_ALPHA, width / 2, height / 2, 0, GLES20.GL_LUMINANCE_ALPHA, GLES20.GL_UNSIGNED_BYTE, uvFloatBuffer);
            GLES20.glUniform1i(muvTextureLoc, 1);

            // 绘制 GLES20.GL_TRIANGLE_STRIP:复用坐标
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, VertexCount);

            GLES20.glDisableVertexAttribArray(av_Position);
            GLES20.glDisableVertexAttribArray(af_Position);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        }

        @Override
        public void onSurfaceChanged(GL10 gl, int w, int h) {
            //当surface的尺寸发生改变时，该方法被调用，。往往在这里设置ViewPort。或者Camara等。
//            Logger.d( "BackgroundGLSurfaceView: onSurfaceChanged() w="+w+", h="+h);
            gl.glViewport(0, 0, w, h);
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            // 该方法在渲染开始前调用，OpenGL ES的绘制上下文被重建时也会调用。
            //当Activity暂停时，绘制上下文会丢失，当Activity恢复时，绘制上下文会重建。
//            Logger.d( "BackgroundGLSurfaceView: onSurfaceCreated()");
            initGLEnv();
        }

        /**
         * 设置preview数据
         * @param data
         * @param width
         * @param height
         */
        public synchronized void setPreviewData(byte[] data, int width, int height) {
            if (yFloatBuffer == null || uvFloatBuffer == null) {
                yFloatBuffer = ByteBuffer.allocate(width * height);
                uvFloatBuffer = ByteBuffer.allocate(width * height / 2);
            }
            this.width = width;
            this.height = height;
            //Logger.d( "BackgroundGLSurfaceView: data.length="+data.length+", width="+width+", height="+height);
            yFloatBuffer.position(0);
            yFloatBuffer.put(data, 0, width * height);
            yFloatBuffer.position(0);

            uvFloatBuffer.position(0);
            uvFloatBuffer.put(data, width * height, width * height / 2);
            uvFloatBuffer.position(0);
        }


        public synchronized void releaseBuf(){
            if (yFloatBuffer != null){
                yFloatBuffer.clear();
                yFloatBuffer = null;
            }

            if (uvFloatBuffer != null){
                uvFloatBuffer.clear();
                uvFloatBuffer = null;
            }
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
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mVertexBufferId);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertexData.length * 4, mVertexBuffer, GLES20.GL_STATIC_DRAW);

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


    private final String vertexSource = "attribute vec4 av_Position; " +
            "attribute vec2 af_Position; " +
            "varying vec2 v_texPo; " +
            "void main() { " +
            "    v_texPo = af_Position; " +
            "    gl_Position = av_Position; " +
            "}";

    // 这里把Y的值调暗-0.2
    private final String fragmentSourceNV21 = "precision highp float;" +
            "uniform sampler2D yTexture;" +
            "uniform sampler2D uvTexture;" +
            "varying highp vec2 v_texPo;" +
            "void main()" +
            "{" +
            "   float r, g, b, y, u, v;\n" +
            "   y = texture2D(yTexture, v_texPo).r - 0.2;\n" +
            "   u = texture2D(uvTexture, v_texPo).a - 0.5;\n" +
            "   v = texture2D(uvTexture, v_texPo).r - 0.5;\n" +
            "   r = y + 1.57481*v;\n" +
            "   g = y - 0.18732*u - 0.46813*v;\n" +
            "   b = y + 1.8556*u;\n" +
            "   gl_FragColor = vec4(r, g, b, 1.0);\n" +
            "}";

}
