package com.rokid.glass.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import com.rokid.utils.ContextUtil;

import java.lang.reflect.Method;

public class PowerManagerUtils {
    private static final String TAG = PowerManagerUtils.class.getSimpleName();
    public static void wakeUp() {
        Log.d(TAG,"wakeUp");
        try {
            // 获取 PowerManager 对象
            PowerManager powerManager = (PowerManager) ContextUtil.getApplicationContext().getSystemService(Context.POWER_SERVICE);
            if (powerManager == null) {
                throw new NullPointerException("PowerManager is null");
            }

            // 获取 wakeUp 方法
            Method wakeUpMethod = PowerManager.class.getDeclaredMethod("wakeUp", long.class);
            wakeUpMethod.setAccessible(true); // 设置为可访问

            // 调用 wakeUp 方法，传入当前的系统时间
            wakeUpMethod.invoke(powerManager, SystemClock.uptimeMillis());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void goToSleep() {
        Log.d(TAG,"goToSleep");
        try {
            // 获取 PowerManager 实例
            PowerManager powerManager = (PowerManager) ContextUtil.getApplicationContext().getSystemService(Context.POWER_SERVICE);
            if (powerManager == null) {
                throw new NullPointerException("PowerManager is null");
            }

            // 获取 goToSleep 方法
            Method goToSleepMethod = PowerManager.class.getDeclaredMethod("goToSleep", long.class);
            goToSleepMethod.setAccessible(true); // 设置为可访问

            // 调用 goToSleep 方法，传入当前时间
            goToSleepMethod.invoke(powerManager, SystemClock.uptimeMillis());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void setScreenAutoSleepTime(long time) {
        //Settings.System.putLong(BasicApplication.getBasicInstance().getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT,  12 * 60 * 60 * 1000L);
        Settings.System.putLong(ContextUtil.getApplicationContext().getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, time);
    }

    public static void updateUserActivity() {
        Log.d(TAG,"goToSleep");
        try {
            // 获取 PowerManager 实例
            PowerManager powerManager = (PowerManager) ContextUtil.getApplicationContext().getSystemService(Context.POWER_SERVICE);
            if (powerManager == null) {
                throw new NullPointerException("PowerManager is null");
            }
            // 获取 goToSleep 方法
            @SuppressLint("DiscouragedPrivateApi") Method userActivityMethod = PowerManager.class.getDeclaredMethod("userActivity", long.class, boolean.class);
            userActivityMethod.setAccessible(true); // 设置为可访问

            // 调用 goToSleep 方法，传入当前时间
            userActivityMethod.invoke(powerManager, SystemClock.uptimeMillis(), true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
