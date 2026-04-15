/*
 * Copyright 2014 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.rokid.glass.view.glsurface.opengl;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;

import com.rokid.security.glass3.open.sdk.uitls.log.L;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.opengles.GL10;

/**
 * Some OpenGL utility functions.
 */
public class GlUtil {
    public static final String TAG = "Grafika";

    /**
     * Identity matrix for general use.  Don't modify or life will get weird.
     */
    public static final float[] IDENTITY_MATRIX;

    static {
        IDENTITY_MATRIX = new float[16];
        Matrix.setIdentityM(IDENTITY_MATRIX, 0);
    }

    private static final int SIZEOF_FLOAT = 4;


    private GlUtil() {
    }     // do not instantiate

    private static final int EGL_RECORDABLE_ANDROID = 0x3142;



    public static void initMyEGL(final EGLContext eglContext, int width, int height) {
        // EGL config attributes
        int confAttr[] =
                {
                        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,// very important!
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,//EGL_WINDOW_BIT EGL_PBUFFER_BIT we will create a pixelbuffer surface
                        EGL14.EGL_RED_SIZE, 8,
                        EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_ALPHA_SIZE, 8,// if you need the alpha channel
                        EGL14.EGL_DEPTH_SIZE, 8,// if you need the depth buffer
                        EGL14.EGL_STENCIL_SIZE, 8,
                        EGL14.EGL_NONE
                };
        // EGL context attributes
        int ctxAttr[] = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,// very important!
                EGL14.EGL_NONE
        };
        // surface attributes
        // the surface size is set to the input frame size
        int surfaceAttr[] = {
                EGL14.EGL_WIDTH, width,
                EGL14.EGL_HEIGHT, height,
                EGL14.EGL_NONE
        };

        eglDisp = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);

        if (eglDisp == EGL14.EGL_NO_DISPLAY) {
            //Unable to open connection to local windowing system
            L.d("GLUtil", "Unable to open connection to local windowing system");
        }

        int[] eglMajVers=new int[2];
        if (!EGL14.eglInitialize(eglDisp, eglMajVers,0, eglMajVers,1))
        {
            // Unable to initialize EGL. Handle and recover
            L.d("GLUtil", "Unable to initialize EGL");
        }
        L.d("GLUtil", "EGL init with version "+eglMajVers[0]+"-"+eglMajVers[1]);
        // choose the first config, i.e. best config
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisp, confAttr,0,  configs,0,configs.length, numConfigs,0))
        {
            L.d("GLUtil","some config is wrong");
        }
        else
        {
            L.d("GLUtil","all configs is OK");
        }
        // create a pixelbuffer surface
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisp, configs[0], surfaceAttr,0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            switch (EGL14.eglGetError()) {
                case EGL14.EGL_BAD_ALLOC:
                    // Not enough resources available. Handle and recover
                    L.d("GLUtil","Not enough resources available");
                    break;
                case EGL14.EGL_BAD_CONFIG:
                    // Verify that provided EGLConfig is valid
                    L.d("GLUtil","provided EGLConfig is invalid");
                    break;
                case EGL14.EGL_BAD_PARAMETER:
                    // Verify that the EGL_WIDTH and EGL_HEIGHT are
                    // non-negative values
                    L.d("GLUtil","provided EGL_WIDTH and EGL_HEIGHT is invalid");
                    break;
                case EGL14.EGL_BAD_MATCH:
                    // Check window and EGLConfig attributes to determine
                    // compatibility and pbuffer-texture parameters
                    L.d("GLUtil","Check window and EGLConfig attributes");
                    break;
            }
        }
        EGLContext eglCtx = EGL14.eglCreateContext(eglDisp, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttr,0);
        if (eglCtx == EGL14.EGL_NO_CONTEXT) {
            int error = EGL14.eglGetError();
            if (error == EGL14.EGL_BAD_CONFIG) {
                // Handle error and recover
                L.d("GLUtil","EGL_BAD_CONFIG");
            }
        }
        if (!EGL14.eglMakeCurrent(eglDisp, eglSurface, eglSurface, eglCtx)) {
            L.d("GLUtil","MakeCurrent failed");
        }
        L.d("GLUtil","initialize success!");
    }
    private static EGLDisplay eglDisp= EGL14.EGL_NO_DISPLAY;
    private static EGLContext eglCtx= EGL14.EGL_NO_CONTEXT;
    private static EGLSurface eglSurface= EGL14.EGL_NO_SURFACE;

    public static void destroyEGl(){
        if(eglCtx!= EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisp, eglCtx);

            EGL14.eglDestroySurface(eglDisp, eglSurface);
        }
        eglDisp= EGL14.EGL_NO_DISPLAY;
        eglCtx = EGL14.EGL_NO_CONTEXT;
        eglSurface = EGL14.EGL_NO_SURFACE;
    }


    /**
     * Creates a new program from the supplied vertex and fragment shaders.
     *
     * @return A handle to the program, or 0 on failure.
     */
    public static int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        if (vertexShader == 0) {
            return 0;
        }
        int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (pixelShader == 0) {
            return 0;
        }

        int program = GLES20.glCreateProgram();
        checkGlError("glCreateProgram");
        if (program == 0) {
            L.e(TAG, "Could not create program");
        }
        GLES20.glAttachShader(program, vertexShader);
        checkGlError("glAttachShader");
        GLES20.glAttachShader(program, pixelShader);
        checkGlError("glAttachShader");
        GLES20.glLinkProgram(program);
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            L.e(TAG, "Could not link program: ");
            L.e(TAG, GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            program = 0;
        }
        return program;
    }

    /**
     * Compiles the provided shader source.
     *
     * @return A handle to the shader, or 0 on failure.
     */
    public static int loadShader(int shaderType, String source) {
        int shader = GLES20.glCreateShader(shaderType);
        checkGlError("glCreateShader type=" + shaderType);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            L.e(TAG, "Could not compile shader " + shaderType + ":");
            L.e(TAG, " " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            shader = 0;
        }
        return shader;
    }

    /**
     * Checks to see if a GLES error has been raised.
     */
    public static void checkGlError(String op) {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            String msg = op + ": glError 0x" + Integer.toHexString(error);
            L.e(TAG, msg);
            throw new RuntimeException(msg);
        }
    }

    /**
     * Checks to see if the location we obtained is valid.  GLES returns -1 if a label
     * could not be found, but does not set the GL error.
     * <p>
     * Throws a RuntimeException if the location is invalid.
     */
    public static void checkLocation(int location, String label) {
        if (location < 0) {
            throw new RuntimeException("Unable to locate '" + label + "' in program");
        }
    }

    /**
     * Creates a texture from raw data.
     *
     * @param data   Image data, in a "direct" ByteBuffer.
     * @param width  Texture width, in pixels (not bytes).
     * @param height Texture height, in pixels.
     * @param format Image data format (use constant appropriate for glTexImage2D(), e.g. GL_RGBA).
     * @return Handle to texture.
     */
    public static int createImageTexture(ByteBuffer data, int width, int height, int format) {
        int[] textureHandles = new int[1];
        int textureHandle;

        GLES20.glGenTextures(1, textureHandles, 0);
        textureHandle = textureHandles[0];
        GlUtil.checkGlError("glGenTextures");

        // Bind the texture handle to the 2D texture target.
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle);

        // Configure min/mag filtering, i.e. what scaling method do we use if what we're rendering
        // is smaller or larger than the source image.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GlUtil.checkGlError("loadImageTexture");

        // Load the data from the buffer into the texture handle.
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, /*level*/ 0, format,
                width, height, /*border*/ 0, format, GLES20.GL_UNSIGNED_BYTE, data);
        GlUtil.checkGlError("loadImageTexture");

        return textureHandle;
    }

    public static int GenImageTexture() {
        int[] textureHandles = new int[1];
        int textureHandle;

        GLES20.glGenTextures(1, textureHandles, 0);
        textureHandle = textureHandles[0];
        GlUtil.checkGlError("glGenTextures");

        // Bind the texture handle to the 2D texture target.
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle);

        // Configure min/mag filtering, i.e. what scaling method do we use if what we're rendering
        // is smaller or larger than the source image.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GlUtil.checkGlError("loadImageTexture");

//        // Load the data from the buffer into the texture handle.
//        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, /*level*/ 0, format,
//                width, height, /*border*/ 0, format, GLES20.GL_UNSIGNED_BYTE, data);
//        GlUtil.checkGlError("loadImageTexture");

        return textureHandle;
    }

    public static int CreateOESTexture() {


        int[] texture = new int[1];
        GLES20.glGenTextures(1, texture, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture[0]);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_LINEAR);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GL10.GL_TEXTURE_WRAP_S, GL10.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GL10.GL_TEXTURE_WRAP_T, GL10.GL_CLAMP_TO_EDGE);
        return texture[0];
    }

    /**
     * Allocates a direct float buffer, and populates it with the float array data.
     */
    public static FloatBuffer createFloatBuffer(float[] coords) {
        // Allocate a direct ByteBuffer, using 4 bytes per float, and copy coords into it.
        ByteBuffer bb = ByteBuffer.allocateDirect(coords.length * SIZEOF_FLOAT);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(coords);
        fb.position(0);
        return fb;
    }

    /**
     * Writes GL version info to the log.
     */
    public static void logVersionInfo() {
        L.i(TAG, "vendor  : " + GLES20.glGetString(GLES20.GL_VENDOR));
        L.i(TAG, "renderer: " + GLES20.glGetString(GLES20.GL_RENDERER));
        L.i(TAG, "version : " + GLES20.glGetString(GLES20.GL_VERSION));

//        if (false) {
//            int[] values = new int[1];
//            GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, values, 0);
//            int majorVersion = values[0];
//            GLES30.glGetIntegerv(GLES30.GL_MINOR_VERSION, values, 0);
//            int minorVersion = values[0];
//            if (GLES30.glGetError() == GLES30.GL_NO_ERROR) {
//                L.i(TAG, "iversion: " + majorVersion + "." + minorVersion);
//            }
//        }
    }

    /**
     * ����OES����id
     *
     * @return
     */
    public static int createOESTextureID() {
        final int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);

        return tex[0];
    }


    /**
     * delete specific texture
     */
    public static void deleteTex(final int tex) {
        final int[] texArray = new int[]{tex};
        GLES20.glDeleteTextures(1, texArray, 0);
    }
}
