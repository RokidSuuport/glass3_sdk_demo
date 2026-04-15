package com.rokid.phone.glsurface.gles;

import android.opengl.GLES20;

import com.rokid.security.phone.sdk.base.utils.log.L;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

public class ImageTextureProgram {
	private String TAG = getClass().getSimpleName();
	private static String VertexShaderTexture =
			"attribute vec4 aPosition;" +
			"attribute vec2 aTexCoord;" +
			"varying highp vec2 vTexCoord;" +
			"void main()" +
			"{" +
			"     gl_Position = aPosition;" +
			"     vTexCoord = aTexCoord;" +
			"}"; 

	private static String FragmentShaderTextureNV21 =
			"precision highp float;" +
			"uniform sampler2D yTexture;" +
			"uniform sampler2D uvTexture;" +
			"varying highp vec2 vTexCoord;" +
			"void main()" +
			"{" +
			"    mediump vec3 yuv;" +
			"    highp vec3 rgb; " +
			"    yuv.x = texture2D(yTexture, vTexCoord).r;  " +
			"    yuv.y = texture2D(uvTexture, vTexCoord).a-0.5;" +
			"   yuv.z = texture2D(uvTexture, vTexCoord).r-0.5;" +
			"   rgb = mat3(      1,       1,       1," +
			"              0, -0.344, 1.770," +
			"              1.403, -0.714,       0) * yuv;" +
			"   gl_FragColor = vec4(rgb, 1);" +
			"}";
	
	private int mProgramHandle = -1;
	private int maPositionLoc = -1;
	private int myTextureLoc = -1;
	private int muvTextureLoc = -1;
	private int maTexCoord = -1;
	private int[] mTextureID = new int[2];
	
	public ImageTextureProgram() {
        mProgramHandle = GlUtil.createProgram(VertexShaderTexture, FragmentShaderTextureNV21);
        if (mProgramHandle == 0) {
            throw new RuntimeException("Unable to create program");
        }
        L.d(TAG, "Created program " + mProgramHandle);

        // get locations of attributes and uniforms

        maPositionLoc = GLES20.glGetAttribLocation(mProgramHandle, "aPosition");
        GlUtil.checkLocation(maPositionLoc, "aPosition");
        maTexCoord = GLES20.glGetAttribLocation(mProgramHandle, "aTexCoord");
        GlUtil.checkLocation(maTexCoord, "aTexCoord");
        myTextureLoc = GLES20.glGetUniformLocation(mProgramHandle,  "yTexture");
        GlUtil.checkLocation(myTextureLoc, "yTexture");
        muvTextureLoc = GLES20.glGetUniformLocation(mProgramHandle,  "uvTexture");
        GlUtil.checkLocation(muvTextureLoc, "uvTexture");
    }
	

	public void drawNV21ImageData(int width, int height, ByteBuffer YBuffer, ByteBuffer uvFloatBuffer, FloatBuffer vertexCoordsBuffer, FloatBuffer textureCoordsCoords) {
		if(mTextureID[0] == 0 || mTextureID[1] == 0) {
			mTextureID[0] = GlUtil.GenImageTexture();
			mTextureID[1] = GlUtil.GenImageTexture();
		}
		
		GLES20.glUseProgram(mProgramHandle);
        //GlUtil.checkGlError("glUseProgram");
        
		GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureID[0]);
		GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, width, height, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, YBuffer);
		GLES20.glUniform1i(myTextureLoc, 0);
		  
		GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureID[1]);
		GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE_ALPHA, width / 2, height / 2, 0, GLES20.GL_LUMINANCE_ALPHA, GLES20.GL_UNSIGNED_BYTE, uvFloatBuffer);
		GLES20.glUniform1i(muvTextureLoc, 1);
		
	        
		GLES20.glEnableVertexAttribArray(maPositionLoc);
		GLES20.glEnableVertexAttribArray(maTexCoord);
		
		GLES20.glVertexAttribPointer(maPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexCoordsBuffer);
		GLES20.glVertexAttribPointer(maTexCoord, 2, GLES20.GL_FLOAT, false, 0, textureCoordsCoords);
	        
		GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
	        
		GLES20.glDisableVertexAttribArray(maPositionLoc);
		GLES20.glDisableVertexAttribArray(maTexCoord);
		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
		GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
	}

    /**
     * Releases the program.
     */
    public void release() {
    	for(int i=0;i<2;i++)
        {
            if(mTextureID[i] != 0)
            {
            	GLES20.glDeleteTextures(1, mTextureID, i);
                mTextureID[i] = 0;
            }
        }
    	
        GLES20.glDeleteProgram(mProgramHandle);
        mProgramHandle = -1;
    }

}
