# 完整推流接入

`GlassMediaStreamer` 是给业务应用的一键入口。它负责 Glass SDK 连接、NV21/PCM 采集、WebSocket 信令、WebRTC 协商、有限重连、状态统计和资源释放。

## 在现有源码工程中启用组件

眼镜应用模块使用以下源码组件：

```groovy
implementation project(':glass3-media-capture')
implementation project(':glass3-media-streaming')
```

这些模块已经包含在项目的 `android` 目录中。`glass3-media-streaming` 统一调用采集与传输组件，业务页面只需要使用 `GlassMediaStreamer`。建议先运行随项目提供的眼镜应用，再参考完整页面把调用接入自己的业务流程。

## Android 配置

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
```

局域网调试使用 `ws://` 时，在 `application` 增加 `android:usesCleartextTraffic="true"`。生产环境应使用 HTTPS/WSS，不需要放开明文传输。

工程使用 Java 17，最低 Android 版本 29，并需要 `arm64-v8a`。相机和录音权限既要写入 Manifest，也要在运行时申请。

## 最小调用

```kotlin
private val streamer by lazy { GlassMediaStreamer.create(applicationContext) }

fun start(url: String) {
    streamer.start(StreamingOptions(serverUrl = url, roomId = "default")) { status ->
        runOnUiThread { render(status) }
    }
}

override fun onStop() {
    streamer.stop()
    super.onStop()
}

override fun onDestroy() {
    streamer.release()
    super.onDestroy()
}
```

请直接复制可编译的完整页面，而不是从上面的片段猜测权限和生命周期：

- [Kotlin StreamingActivity](code/kotlin/StreamingActivity.kt)
- [Java StreamingActivity](code/java/StreamingActivity.java)

## 参数与结果

- `serverUrl`：浏览器接收端显示的完整 `ws://` 或 `wss://` 地址。
- `videoEnabled`、`audioEnabled`：至少启用一种媒体。
- `roomId`：发送端和接收端必须一致；当前接收服务按单房间、单发送端设计。
- `StreamingStatus.state`：用于页面显示当前阶段。
- `StreamingStatus.stats`：分辨率、FPS、音视频码率、丢包、往返延迟与 PCM 缓冲统计。
- `StreamingStatus.failure`：包含稳定错误码、用户提示和建议动作。

成功时状态到达 `STREAMING`，浏览器收到远端轨道。页面离开前调用 `stop()`，Activity 最终销毁时调用 `release()`；`release()` 后不能再次启动，应重新 `create()`。

## 混淆与发布

业务工程启用代码压缩或全模式优化后，应使用 release 构建在 Glass3 真机上验证完整推流，不要只验证 debug 包。
