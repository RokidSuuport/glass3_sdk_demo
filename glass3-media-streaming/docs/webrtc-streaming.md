# 完整推流接入

`GlassMediaStreamer` 是给业务应用的一键入口。它负责 Glass SDK 连接、NV21/PCM 采集、WebSocket 信令、WebRTC 协商、有限重连、状态统计和资源释放。

## 先判断代码放在哪里

### 与本项目处于同一个 Gradle 工程

直接依赖源码模块，不需要 AAR：

```groovy
implementation project(':glass3-media-capture')
implementation project(':glass3-media-streaming')
```

`glass3-media-streaming` 已通过 `api` 依赖采集与传输组件。上面同时列出采集模块，是为了让模块关系一眼可见；业务页面只需使用 `GlassMediaStreamer`。

### 放入客户自己的另一个 Android 工程

外部工程不能复制 `implementation project(模块名)`，因为客户工程里不存在这些 Gradle 子模块。应先把本项目发布出的 Maven 目录放到可访问位置，再配置仓库和坐标：

```groovy
repositories {
    google()
    mavenCentral()
    maven { url uri('<MAVEN_REPOSITORY_PATH>') }
}

dependencies {
    implementation 'com.rokid.glass:glass3-media-streaming:1.0.0'
}
```

顶层坐标会传递引入 `glass3-media-capture` 和 `webrtc-transport`，不要重复复制内部源码。若客户只拿到单个 AAR，还必须同时拿到它的传递依赖；建议始终交付完整 Maven 目录。

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

组件随 AAR 提供 consumer rules，通常不需要业务工程额外添加规则。若业务工程启用更激进的全模式优化，应以 release 构建和真机推流作为最终验证，不要只验证 debug 包。
