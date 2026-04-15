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

package com.rokid.glass.view.glsurface.gles;

import android.graphics.Rect;

import java.nio.FloatBuffer;


/**
 * This class essentially represents a viewport-sized sprite that will be rendered with
 * a texture, usually from an external source like the camera or video decoder.
 */
public class FullFrameRect {
	private static final int SIZEOF_FLOAT = 4;
    private Drawable2d mRectDrawable;
    private Texture2dProgram mProgram;

    private FlatPointProgram mPointProgram = null;
    
    private RectProgram rectProgram=null;
    /**
     * Prepares the object.
     *

     *     the program when no longer needed.
     */
    public FullFrameRect() {
    	mRectDrawable = new Drawable2d(Drawable2d.Prefab.FULL_RECTANGLE);
        mProgram = new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_2D);
    }

    /**
     * Releases resources.
     * <p>
     * This must be called with the appropriate EGL context current (i.e. the one that was
     * current when the constructor was called).  If we're about to destroy the EGL context,
     * there's no value in having the caller make it current just to do this cleanup, so you
     * can pass a flag that will tell this function to skip any EGL-context-specific cleanup.
     */
    public void release(boolean doEglCleanup) {
        if (mProgram != null) {
            if (doEglCleanup) {
                mProgram.release();
            }
            mProgram = null;
        }
        
        if (mPointProgram != null) {
            if (doEglCleanup) {
            	mPointProgram.release();
            }
            mPointProgram = null;
        }
        
        if(rectProgram!=null){
        	if (doEglCleanup) {
        		rectProgram.release();
            }
        	rectProgram = null;
        }
    }

    /**
     * Returns the program currently in use.
     */
    public Texture2dProgram getTextureProgram() {
        return mProgram;
    }


    /**
     * Creates a texture object suitable for use with drawFrame().
     */
    public int createTextureObject() {
        return mProgram.createTextureObject();
    }

    /**
     * Draws a viewport-filling rect, texturing it with the specified texture object.
     */
    public void drawFrame(int textureId, float[] texMatrix) {
        // Use the identity matrix for MVP so our 2x2 FULL_RECTANGLE covers the viewport.
 
    	
        mProgram.draw(GlUtil.IDENTITY_MATRIX, mRectDrawable.getVertexArray(), 0,
                mRectDrawable.getVertexCount(), mRectDrawable.getCoordsPerVertex(),
                mRectDrawable.getVertexStride(),
                texMatrix, mRectDrawable.getTexCoordArray(), textureId,
                mRectDrawable.getTexCoordStride());
    }
    public void drawRect(Rect rect, int ptSrcTargetWidth, int ptSrcTargetHeight, boolean isMirror, int orientation){
    	if(rectProgram==null){
    		rectProgram=new RectProgram();
    	}
    	final int coordsPerVertex = 2;
    	final int drawLineCount= 4;
    	float[] rectVertexArr = new float[drawLineCount * coordsPerVertex];
    	int width=ptSrcTargetWidth;
    	int height=ptSrcTargetHeight;
    	
    	rectVertexArr[0]=(float)(1.0-rect.top*2.0/height);
    	rectVertexArr[1]=(float)(rect.left*2.0/width-1);
    	
    	rectVertexArr[2]=(float)(1.0-rect.top*2.0/height);
    	rectVertexArr[3]=(float)((rect.right)*2.0/width-1);
    	
    	rectVertexArr[4]=(float)(1.0-(rect.bottom)*2.0/height);
    	rectVertexArr[5]=(float)((rect.right)*2.0/width-1);
    	
    	rectVertexArr[6]=(float)(1.0-(rect.bottom)*2.0/height);
    	rectVertexArr[7]=(float)(rect.left*2.0/width-1);
    	
    	FloatBuffer vertexBuffer = GlUtil.createFloatBuffer(rectVertexArr);
  
    	float[] mvpMatrix = null;
		switch (orientation) {
		case 0:
			mvpMatrix = GlUtil.IDENTITY_MATRIX;
			break;
		case 90:
			mvpMatrix = new float[]{0.0f, -1.0f, 0.0f, 0.0f,  1.0f, 0.0f, 0.0f, 0.0f,  0.0f, 0.0f, 1.0f, 0.0f,  0.0f, 0.0f, 0.0f, 1.0f};
			break;
		case 180:
			mvpMatrix = new float[]{-1.0f, 0, 0, 0, 0,-1, 0, 0 , 0, 0, 1.0f, 0, 0, 0, 0, 1.0f};
			break;
		case 270:
			mvpMatrix = new float[] { 0, 1.0f, 0, 0, -1, 0, 0, 0, 0, 0, 1.0f, 0, 0, 0, 0, 1.0f };
			break;
		default:
			break;
		}
		rectProgram.draw(mvpMatrix, 20.0f, new float[]{1.0f, 0.0f, 0.0f, 1.0f}, vertexBuffer, 0,drawLineCount , coordsPerVertex, 0);
    	
    }



	public void drawRect(Rect rect, int ptSrcTargetWidth, int ptSrcTargetHeight, boolean isMirror, int orientation, float[] color){
		if(rectProgram==null){
			rectProgram=new RectProgram();
		}
		final int coordsPerVertex = 2;
		final int drawLineCount= 4;
		float[] rectVertexArr = new float[drawLineCount * coordsPerVertex];
		int width=ptSrcTargetWidth;
		int height=ptSrcTargetHeight;

		rectVertexArr[0]=(float)(rect.left*2.0/width-1);
		rectVertexArr[1]=(float)(1-rect.top*2.0/height);

		rectVertexArr[2]=(float)((rect.right)*2.0/width-1);
		rectVertexArr[3]=(float)(1-rect.top*2.0/height);


		rectVertexArr[4]=(float)((rect.right)*2.0/width-1);
		rectVertexArr[5]=(float)(1-(rect.bottom*2.0/height));

		rectVertexArr[6]=(float)(rect.left*2.0/width-1);
		rectVertexArr[7]=(float)(1-(rect.bottom*2.0/height));


		FloatBuffer vertexBuffer = GlUtil.createFloatBuffer(rectVertexArr);

		float[] mvpMatrix = null;
		switch (orientation) {
			case 0:
				mvpMatrix = GlUtil.IDENTITY_MATRIX;
				break;
			case 90:
				mvpMatrix = new float[]{0.0f, -1.0f, 0.0f, 0.0f,  1.0f, 0.0f, 0.0f, 0.0f,  0.0f, 0.0f, 1.0f, 0.0f,  0.0f, 0.0f, 0.0f, 1.0f};
				break;
			case 180:
				mvpMatrix = new float[]{-1.0f, 0, 0, 0, 0,-1, 0, 0 , 0, 0, 1.0f, 0, 0, 0, 0, 1.0f};
				break;
			case 270:
				mvpMatrix = new float[] { 0, 1.0f, 0, 0, -1, 0, 0, 0, 0, 0, 1.0f, 0, 0, 0, 0, 1.0f };
				break;
			default:
				break;
		}
		rectProgram.draw(mvpMatrix, 20.0f, color, vertexBuffer, 0,drawLineCount , coordsPerVertex, 0);

	}

    public void drawPoints(android.graphics.Point[] points, int pointOffset, int drawPointCount, 
    		int ptSrcTargetWidth, int ptSrcTargetHeight, float drawSizeOfPoint, boolean isMirror, int orientation){
    	if(mPointProgram == null)
    		mPointProgram = new FlatPointProgram();
    	final int coordsPerVertex = 2;
    	float[] pointVertexArr = new float[drawPointCount * coordsPerVertex];
    	for (int i = pointOffset; i < drawPointCount; i++) {
    		float x  = points[i].x;
    		if(isMirror) {
    			x = ptSrcTargetWidth - points[i].x;
    		}
    		pointVertexArr[i*coordsPerVertex] = (float)(x  * 2.0 / ptSrcTargetWidth - 1.0);
    		pointVertexArr[i*coordsPerVertex+1] =(float)( 1.0 - points[i].y * 2.0 / ptSrcTargetHeight);
		}
    	FloatBuffer vertexBuffer = GlUtil.createFloatBuffer(pointVertexArr);
    	float[] mvpMatrix = null;
		switch (orientation) {
		case 0:
			mvpMatrix = GlUtil.IDENTITY_MATRIX;
			break;
		case 90:
			mvpMatrix = new float[]{0.0f, -1.0f, 0.0f, 0.0f,  1.0f, 0.0f, 0.0f, 0.0f,  0.0f, 0.0f, 1.0f, 0.0f,  0.0f, 0.0f, 0.0f, 1.0f};
			break;
		case 180:
			mvpMatrix = new float[]{-1.0f, 0, 0, 0, 0,-1, 0, 0 , 0, 0, 1.0f, 0, 0, 0, 0, 1.0f};
			break;
		case 270:
			mvpMatrix = new float[] { 0, 1.0f, 0, 0, -1, 0, 0, 0, 0, 0, 1.0f, 0, 0, 0, 0, 1.0f };
			break;
		default:
			break;
		}
    	mPointProgram.draw(mvpMatrix,drawSizeOfPoint, new float[]{1.0f, 0.0f, 0.0f, 1.0f}, vertexBuffer,
                0, drawPointCount, coordsPerVertex, coordsPerVertex*SIZEOF_FLOAT);
    }
}
