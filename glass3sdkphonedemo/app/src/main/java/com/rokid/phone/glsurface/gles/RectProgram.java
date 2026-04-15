package com.rokid.phone.glsurface.gles;

import android.opengl.GLES20;

import com.rokid.security.phone.sdk.base.utils.log.L;

import java.nio.FloatBuffer;

public class RectProgram {
	 private static final String VERTEX_SHADER =
	    		//"uniform mat4 uMVPMatrix;" +
	            "attribute vec4 aPosition;" +
	            "uniform vec4 uColor;" +
	            "varying vec4 aColor;\n" +
	            //"uniform float uPointSize;" +
	            "void main() {" +
	            "    gl_Position =  aPosition;" +
	            //"    gl_PointSize = uPointSize;" +
	            "    aColor =  uColor;" +
	            "}";

	    private static final String FRAGMENT_SHADER =
	            "precision mediump float;" +
	            "varying vec4 aColor;" +
	            "void main() {" +
	            "    gl_FragColor = aColor;" +
	            "}";
	    
	    private final String TAG = this.getClass().getSimpleName();
	    
	    // Handles to the GL program and various components of it.
	    private int mProgramHandle = -1;
	    private int muColorLoc = -1;
	    private int muPointSizeLoc = -1;
	    private int mPositionLoc = -1;
	    private int muMVPMatrixLoc = -1;
	    
	    public RectProgram() {
	        mProgramHandle = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
	        if (mProgramHandle == 0) {
	            throw new RuntimeException("Unable to create program");
	        }
	        L.d(TAG, "Created program " + mProgramHandle);

	        // get locations of attributes and uniforms

	        mPositionLoc = GLES20.glGetAttribLocation(mProgramHandle, "aPosition");
	        GlUtil.checkLocation(mPositionLoc, "aPosition");
	       /* muPointSizeLoc = GLES20.glGetUniformLocation(mProgramHandle, "uPointSize");
	        GlUtil.checkLocation(muPointSizeLoc, "uPointSize");*/
	        muColorLoc = GLES20.glGetUniformLocation(mProgramHandle, "uColor");
	        GlUtil.checkLocation(muColorLoc, "uColor");
	        /*muMVPMatrixLoc = GLES20.glGetUniformLocation(mProgramHandle, "uMVPMatrix");
	        GlUtil.checkLocation(muMVPMatrixLoc, "uMVPMatrix");*/
	    	
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
	       /* GLES20.glUniformMatrix4fv(muMVPMatrixLoc, 1, false, mvpMatrix, 0);
	        GlUtil.checkGlError("glUniformMatrix4fv");*/

	        //
	       /* GLES20.glUniform1f(muPointSizeLoc, pointSize);
	        GlUtil.checkGlError("glUniform1f");*/

	        // Copy the color vector in.
	        GLES20.glUniform4fv(muColorLoc, 1, color, 0);
	        GlUtil.checkGlError("glUniform4fv ");

	        // Enable the "aPosition" vertex attribute.
	        GLES20.glEnableVertexAttribArray(mPositionLoc);
	        GlUtil.checkGlError("glEnableVertexAttribArray");

	        // Connect vertexBuffer to "aPosition".
	        GLES20.glVertexAttribPointer(mPositionLoc, coordsPerVertex, GLES20.GL_FLOAT, false, 0, vertexBuffer);
	        GlUtil.checkGlError("glVertexAttribPointer");
	        GLES20.glLineWidth(4);
	        // Draw the rect.
	        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, firstVertex,vertexCount );
	        GlUtil.checkGlError("glDrawArrays");

	        // Done -- disable vertex array and program.
	        GLES20.glDisableVertexAttribArray(mPositionLoc);
	        GLES20.glUseProgram(0);
	        
	        GLES20.glFinish();
	    }
}
