package com.rokid.phone.glsurface.gles;


public class CameraOESRect {

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
    public CameraOESRect() {
        mRectDrawable = new Drawable2d(Drawable2d.Prefab.FULL_RECTANGLE);
        mProgram = new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT);
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

}
