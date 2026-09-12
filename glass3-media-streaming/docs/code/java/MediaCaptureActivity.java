/*
 * 用途：在完整源码工程中只获取 Glass3 NV21/PCM；可直接复制页面代码到眼镜业务模块。
 * 放置位置：app/src/main/java/com/rokid/glass/mediastream/guide/java/MediaCaptureActivity.java。
 * 依赖：implementation project(":glass3-media-capture")；另一个工程先按 source-integration.md 注册模块。
 * 一并复制 docs/code/shared/LatestVideoWorker.java（示例后台队列与显示快照）。
 * Manifest：CAMERA、RECORD_AUDIO；只启用一种媒体时可以只申请对应运行时权限。
 * 服务参数：原始采集不使用信令 URL 和 roomId；需要传浏览器时改用 GlassMediaStreamer。
 * 预期结果：页面持续显示真实 NV21 宽高/帧大小和 PCM 格式/帧大小。
 * 排障错误：PERMISSION_REQUIRED、SDK_NOT_READY、SDK_DISCONNECTED、CAMERA_IN_USE、
 * CAMERA_START_TIMEOUT、VIDEO_FRAME_TIMEOUT、AUDIO_START_FAILED、AUDIO_DATA_TIMEOUT。
 */
package com.rokid.glass.mediastream.guide.java;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.rokid.glass.mediastream.capture.CaptureOptions;
import com.rokid.glass.mediastream.capture.CaptureStatus;
import com.rokid.glass.mediastream.capture.CaptureState;
import com.rokid.glass.mediastream.capture.GlassMediaCapture;
import com.rokid.glass.mediastream.capture.MediaFailure;

import com.rokid.glass.mediastream.guide.common.LatestVideoWorker;

public final class MediaCaptureActivity extends Activity {
    private static final int REQUEST_MEDIA = 1002;
    private static final String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    };

    private final LatestVideoWorker frameWorker = new LatestVideoWorker();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private GlassMediaCapture capture;
    private TextView statusView;
    private TextView videoView;
    private TextView audioView;
    private long activeToken;
    private boolean foreground;
    private volatile boolean destroyed;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!foreground || destroyed) return;
            render(capture.currentStatus());
            videoView.setText(frameWorker.latest().isEmpty() ? "NV21：等待数据" : frameWorker.latest());
            audioView.setText(frameWorker.latestAudio().isEmpty() ? "PCM：等待数据" : frameWorker.latestAudio());
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        capture = GlassMediaCapture.create(getApplicationContext());
        statusView = new TextView(this);
        statusView.setText("等待开始原始媒体采集");
        videoView = new TextView(this);
        audioView = new TextView(this);

        Button startButton = new Button(this);
        startButton.setText("开始获取 NV21/PCM");
        startButton.setOnClickListener(view -> requestPermissionsOrStart());
        Button stopButton = new Button(this);
        stopButton.setText("停止");
        stopButton.setOnClickListener(view -> safelyStop());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int width = ViewGroup.LayoutParams.MATCH_PARENT;
        int height = ViewGroup.LayoutParams.WRAP_CONTENT;
        root.addView(startButton, new LinearLayout.LayoutParams(width, height));
        root.addView(stopButton, new LinearLayout.LayoutParams(width, height));
        root.addView(statusView, new LinearLayout.LayoutParams(width, height));
        root.addView(videoView, new LinearLayout.LayoutParams(width, height));
        root.addView(audioView, new LinearLayout.LayoutParams(width, height));
        setContentView(root);
    }

    private void requestPermissionsOrStart() {
        if (!foreground || destroyed) return;
        for (String permission : REQUIRED_PERMISSIONS) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(REQUIRED_PERMISSIONS, REQUEST_MEDIA);
                return;
            }
        }
        startCapture();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_MEDIA) return;
        if (!foreground || destroyed) return; // 页面离开后的授权结果不自动开启采集。
        boolean granted = grantResults.length > 0;
        for (int result : grantResults) granted &= result == PackageManager.PERMISSION_GRANTED;
        if (granted) startCapture();
        else statusView.setText("PERMISSION_REQUIRED：请授予相机和录音权限后重试");
    }

    private void startCapture() {
        if (!foreground || destroyed) return;
        // 重复点击不替换正在使用的帧回调和工作队列令牌。
        CaptureState state = capture.currentStatus().getState();
        if (state != CaptureState.IDLE && state != CaptureState.ERROR) return;
        final long token = frameWorker.start();
        activeToken = token;
        try {
            capture.start(
                new CaptureOptions(),
                frame -> {
                    // SDK 回调结束后缓冲区会复用；先复制并快照元数据，再交给工作线程。
                    int width = frame.getWidth();
                    int height = frame.getHeight();
                    byte[] ownedNv21 = frame.copyData();
                    // 可在此接入耗时算法，勿用此丢旧帧队列录制连续视频。
                    frameWorker.offer(token, () ->
                        "NV21：" + width + " × " + height + "，本帧 " + ownedNv21.length + " bytes"
                    );
                },
                frame -> frameWorker.publishAudio(token,
                    "PCM：" + frame.getSampleRateHz() + " Hz / " + frame.getChannelCount() +
                        " 声道 / " + frame.getBitsPerSample() + " bit，本帧 " +
                        frame.getData().length + " bytes"
                ),
                status -> runOnUiThread(() -> {
                    if (!destroyed && foreground && token == activeToken) render(status);
                })
            );
        } catch (RuntimeException error) {
            showLocalError(error);
        }
    }

    private void render(CaptureStatus status) {
        MediaFailure failure = status.getFailure();
        if (failure != null) {
            statusView.setText(failure.getCode() + "：" + failure.getUserMessage() +
                "\n" + failure.getSuggestedAction());
        } else statusView.setText("状态：" + status.getState());
    }

    private void showStatus(String text) {
        runOnUiThread(() -> {
            if (!destroyed) statusView.setText(text);
        });
    }

    private void safelyStop() {
        frameWorker.stop();
        // 保留状态订阅以接收异步 STOPPING -> IDLE；只停止旧媒体任务。
        if (capture == null) return;
        try {
            capture.stop();
        } catch (RuntimeException error) {
            showLocalError(error);
        }
    }

    private void showLocalError(Throwable error) {
        showStatus("启动或清理失败：" + String.valueOf(error.getMessage()));
    }

    @Override
    protected void onStart() {
        super.onStart();
        foreground = true;
        render(capture.currentStatus());
        ui.post(refresh);
    }

    @Override
    protected void onStop() {
        foreground = false;
        ui.removeCallbacks(refresh);
        safelyStop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (capture != null) {
            try {
                capture.release();
            } catch (RuntimeException ignored) {
                // Activity 正在退出，资源释放失败只能记录到应用日志，不能再更新页面。
            }
        }
        frameWorker.close();
        super.onDestroy();
    }
}
