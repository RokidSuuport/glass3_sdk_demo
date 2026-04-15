package com.rokid.glass.component;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.AttributeSet;

import com.google.android.material.textview.MaterialTextView;

/**
 * @date 2019-08-14
 */

public class GradientColorTextView extends MaterialTextView {
    private LinearGradient mLinearGradient;
    private Paint mPaint;
    private int mViewHeight = 0;
    private Rect mTextBound = new Rect();

    public GradientColorTextView(Context context) {
        super(context);
    }

    public GradientColorTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public GradientColorTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        mViewHeight = getMeasuredHeight();
        mPaint = getPaint();
        String text = getText().toString();
        mPaint.getTextBounds(text, 0, text.length(), mTextBound);
        mLinearGradient = new LinearGradient(0, 0, 0, mViewHeight,
                new int[]{0xFFBBB6E1, 0xFF59599B}, null, Shader.TileMode.REPEAT);
        mPaint.setShader(mLinearGradient);
        canvas.drawText(text, getMeasuredWidth() / 2 - mTextBound.width() / 2,
                getMeasuredHeight() / 2 + mTextBound.height() / 2, mPaint);
    }
}