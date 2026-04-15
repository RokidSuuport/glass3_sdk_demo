package com.rokid.glass.view.glsurface.gles;

import android.opengl.GLES20;

import com.rokid.security.glass3.open.sdk.uitls.log.L;

import java.nio.FloatBuffer;

public class FlatPointProgram {
    private static final String VERTEX_SHADER =
    		"uniform mat4 uMVPMatrix;" +
            "attribute vec4 aPosition;" +
            "uniform float uPointSize;" +
            "void main() {" +
            "    gl_Position = uMVPMatrix * aPosition;" +
            "    gl_PointSize = uPointSize;" +
            "}";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;" +
            "uniform vec4 uColor;" +
            "void main() {" +
            "    gl_FragColor = uColor;" +
            "}";
    
    private final String TAG = this.getClass().getSimpleName();
    
    // Handles to the GL program and various components of it.
    private int mProgramHandle = -1;
    private int muColorLoc = -1;
    private int muPointSizeLoc = -1;
    private int maPositionLoc = -1;
    private int muMVPMatrixLoc = -1;
    
    public FlatPointProgram() {
        mProgramHandle = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        if (mProgramHandle == 0) {
            throw new RuntimeException("Unable to create program");
        }
        L.d(TAG, "Created program " + mProgramHandle);

        // get locations of attributes and uniforms

        maPositionLoc = GLES20.glGetAttribLocation(mProgramHandle, "aPosition");
        GlUtil.checkLocation(maPositionLoc, "aPosition");
        muPointSizeLoc = GLES20.glGetUniformLocation(mProgramHandle, "uPointSize");
        GlUtil.checkLocation(muPointSizeLoc, "uPointSize");
        muColorLoc = GLES20.glGetUniformLocation(mProgramHandle, "uColor");
        GlUtil.checkLocation(muColorLoc, "uColor");
        muMVPMatrixLoc = GLES20.glGetUniformLocation(mProgramHandle, "uMVPMatrix");
        GlUtil.checkLocation(muMVPMatrixLoc, "uMVPMatrix");
    	
    }


    /**
     * Releases the program.
     */
    public void release() {
        GLES20.glDeleteProgram(mProgramHandle);
        mProgramHandle = -1;
    }
    
    public void draw(float[] mvpMatrix, float pointSize, float[] color, FloatBuffer vertexBuffer,
            int firstVertex, int vertexCount, int coordsPerVertex, int vertexStride) {
        GlUtil.checkGlError("draw start");
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        // Select the program.
        GLES20.glUseProgram(mProgramHandle);
        GlUtil.checkGlError("glUseProgram");
        
        // Copy the model / view / projection matrix over.
        GLES20.glUniformMatrix4fv(muMVPMatrixLoc, 1, false, mvpMatrix, 0);
        GlUtil.checkGlError("glUniformMatrix4fv");

        //
        GLES20.glUniform1f(muPointSizeLoc, pointSize);
        GlUtil.checkGlError("glUniform1f");

        // Copy the color vector in.
        GLES20.glUniform4fv(muColorLoc, 1, color, 0);
        GlUtil.checkGlError("glUniform4fv ");

        // Enable the "aPosition" vertex attribute.
        GLES20.glEnableVertexAttribArray(maPositionLoc);
        GlUtil.checkGlError("glEnableVertexAttribArray");

        // Connect vertexBuffer to "aPosition".
        GLES20.glVertexAttribPointer(maPositionLoc, coordsPerVertex,
            GLES20.GL_FLOAT, false, vertexStride, vertexBuffer);
        GlUtil.checkGlError("glVertexAttribPointer");

        // Draw the rect.
        GLES20.glDrawArrays(GLES20.GL_POINTS, firstVertex, vertexCount);
        GlUtil.checkGlError("glDrawArrays");

        // Done -- disable vertex array and program.
        GLES20.glDisableVertexAttribArray(maPositionLoc);
        GLES20.glUseProgram(0);
    }

}
