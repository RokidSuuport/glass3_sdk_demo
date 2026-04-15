package com.rokid.glass.utils;

import android.content.Context;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.rokid.glass.MyApplication;
import com.rokid.glesse.R;


/**
 * @author : liuweiming
 * @date : 2024/10/23
 * @description :
 * @parameter :
 */
public class SpriteToastUtil {


    public static void showSpriteToastOld(Context context, String toastContent, int image, int time, boolean playSoundEffect) {
        try {
            LayoutInflater inflater = LayoutInflater.from(context);
            View layout = inflater.inflate(R.layout.toast_sprite_layout, null);
            TextView content = layout.findViewById(R.id.sprite_toast_info);
            content.setText(toastContent);
            ImageView imageView = layout.findViewById(R.id.sprite_toast_image);
            if (image > 0) {
                imageView.setVisibility(View.VISIBLE);
                imageView.setImageResource(image);
            } else {
                imageView.setVisibility(View.GONE);
            }
            final Toast nowToast = new Toast(context);
            nowToast.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL, 0, 0);
            nowToast.setDuration(Toast.LENGTH_LONG);
            nowToast.setView(layout);
            nowToast.show();
            if (playSoundEffect) {
                // MainApplication.getFocusAudioManager().playSoundEffect(FocusAudioManager.FX_RKD_TOAST);
            }
            if (time <= 3000) {
                MyApplication.Companion.getGMainHandler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            nowToast.cancel();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }, time);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void showSpriteToast(Context context, String toastContent, int image, int time, boolean playSoundEffect) {
        try {
            final WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

            LayoutInflater inflater = LayoutInflater.from(context);
            final View layout = inflater.inflate(R.layout.toast_sprite_layout, null);
            TextView content = layout.findViewById(R.id.sprite_toast_info);
            content.setText(toastContent);
            ImageView imageView = layout.findViewById(R.id.sprite_toast_image);
            if (image > 0) {
                imageView.setVisibility(View.VISIBLE);
                imageView.setImageResource(image);
            } else {
                imageView.setVisibility(View.GONE);
            }

            final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);

            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            params.y = 0;

            windowManager.addView(layout, params);
            if (playSoundEffect) {
                // BasicApplication.getBasicInstance().playFxSound(FxSoundPlayer.FX_RKD_TOAST);
            }
            MyApplication.Companion.getGMainHandler().postDelayed(() -> {
                try {
                    if (layout.getParent() != null) {
                        windowManager.removeView(layout);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }, time);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
