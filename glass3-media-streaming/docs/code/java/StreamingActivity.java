/*
 * 用途：在完整源码工程中接入浏览器推流；可直接复制页面代码到眼镜业务模块。
 * 放置位置：app/src/main/java/com/rokid/glass/mediastream/guide/java/StreamingActivity.java。
 * 依赖：implementation project(":glass3-media-streaming")。
 * Manifest：INTERNET、ACCESS_NETWORK_STATE、CAMERA、RECORD_AUDIO、MODIFY_AUDIO_SETTINGS；
 * 使用 ws 地址时 application 还需 android:usesCleartextTraffic="true"。
 * 必改参数：把 SERVER_URL_HINT 替换为 PC 页面显示的信令地址；按业务修改 ROOM_ID。
 * 预期结果：状态依次到 WAITING_RECEIVER、NEGOTIATING、STREAMING，浏览器收到视频和音频。
 * 排障错误：PERMISSION_REQUIRED、SERVER_UNREACHABLE、RECEIVER_NOT_READY、
 * WEBRTC_NEGOTIATION_FAILED、NETWORK_DISCONNECTED；其余设备错误见 troubleshooting.md。
 */
package com.rokid.glass.mediastream.guide.java;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.rokid.glass.mediastream.capture.MediaFailure;
import com.rokid.glass.mediastream.streaming.GlassMediaStreamer;
import com.rokid.glass.mediastream.streaming.StreamingOptions;
import com.rokid.glass.mediastream.streaming.StreamingState;
import com.rokid.glass.mediastream.streaming.StreamingStats;
import com.rokid.glass.mediastream.streaming.StreamingStatus;

import java.util.Locale;

public final class StreamingActivity extends Activity {
    private static final String SERVER_URL_HINT = "ws://<PC-IP>:8080/ws";
    private static final String ROOM_ID = "default";
    private static final int REQUEST_MEDIA = 1001;
    private static final String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    };

    private GlassMediaStreamer streamer;
    private EditText serverUrlInput;
    private TextView statusView;
    private volatile boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        streamer = GlassMediaStreamer.create(getApplicationContext());

        serverUrlInput = new EditText(this);
        serverUrlInput.setHint(SERVER_URL_HINT);
        serverUrlInput.setSingleLine(true);
        statusView = new TextView(this);
        statusView.setText("状态：" + StreamingState.IDLE);

        Button startButton = new Button(this);
        startButton.setText("开始音视频传输");
        startButton.setOnClickListener(view -> requestPermissionsOrStart());
        Button stopButton = new Button(this);
        stopButton.setText("停止");
        stopButton.setOnClickListener(view -> safelyStop());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int width = ViewGroup.LayoutParams.MATCH_PARENT;
        int height = ViewGroup.LayoutParams.WRAP_CONTENT;
        root.addView(serverUrlInput, new LinearLayout.LayoutParams(width, height));
        root.addView(startButton, new LinearLayout.LayoutParams(width, height));
        root.addView(stopButton, new LinearLayout.LayoutParams(width, height));
        root.addView(statusView, new LinearLayout.LayoutParams(width, height));
        setContentView(root);
    }

    private void requestPermissionsOrStart() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(REQUIRED_PERMISSIONS, REQUEST_MEDIA);
                return;
            }
        }
        startStreaming();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_MEDIA) return;
        boolean granted = grantResults.length > 0;
        for (int result : grantResults) granted &= result == PackageManager.PERMISSION_GRANTED;
        if (granted) startStreaming();
        else statusView.setText("PERMISSION_REQUIRED：请授予相机和录音权限后重试");
    }

    private void startStreaming() {
        String url = serverUrlInput.getText().toString().trim();
        if (url.isEmpty() || url.contains("<") || url.contains(">")) {
            statusView.setText("请输入浏览器接收页显示的完整 ws:// 或 wss:// 信令地址");
            return;
        }
        try {
            StreamingOptions options = new StreamingOptions(url, true, true, ROOM_ID);
            streamer.start(options, status -> runOnUiThread(() -> {
                if (!destroyed) render(status);
            }));
        } catch (RuntimeException error) {
            showLocalError(error);
        }
    }

    private void render(StreamingStatus status) {
        MediaFailure failure = status.getFailure();
        if (failure != null) {
            statusView.setText(failure.getCode() + "：" + failure.getUserMessage() +
                "\n" + failure.getSuggestedAction());
            return;
        }
        StreamingStats stats = status.getStats();
        String text = String.format(
            Locale.US,
            "状态：%s\n视频：%d × %d %.1f FPS\n码率：视频 %d bps，音频 %d bps",
            status.getState(), stats.getVideoWidth(), stats.getVideoHeight(), stats.getVideoFps(),
            stats.getVideoBitrateBps(), stats.getAudioBitrateBps()
        );
        statusView.setText(text);
    }

    private void safelyStop() {
        if (streamer == null) return;
        try {
            streamer.stop();
        } catch (RuntimeException error) {
            showLocalError(error);
        }
    }

    private void showLocalError(Throwable error) {
        runOnUiThread(() -> {
            if (!destroyed) statusView.setText("启动或清理失败：" + String.valueOf(error.getMessage()));
        });
    }

    @Override
    protected void onStop() {
        safelyStop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (streamer != null) {
            try {
                streamer.release();
            } catch (RuntimeException ignored) {
                // Activity 正在退出，资源释放失败只能记录到应用日志，不能再更新页面。
            }
        }
        super.onDestroy();
    }
}
