# 完整推流接入

`GlassMediaStreamer` 是给业务应用的一键入口。它负责 Glass SDK 连接、NV21/PCM 采集、WebSocket 信令、WebRTC 协商、有限重连、状态统计和资源释放。

## 在现有源码工程中启用组件

眼镜应用模块使用以下源码组件：

```groovy
implementation project(':glass3-media-capture')
implementation project(':glass3-media-streaming')
```

这些模块已经包含在项目的 `android` 目录中。`glass3-media-streaming` 统一调用采集与传输组件，业务页面只需要使用 `GlassMediaStreamer`，不用先自行启动 `GlassMediaCapture`，否则会重复占用媒体源。接入另一个 Android 工程时，先按 [源码组件接入](source-integration.md) 复制并注册三个组件。

应用构建与网络配置集中见 [源码组件接入](source-integration.md)。先运行随项目提供的眼镜应用与接收端，再把完整页面接入自己的业务流程。

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
- `videoCapture`：请求的 NV21 宽高、FPS 等配置，默认 1280 × 720 / 15 FPS。
- `maxVideoBitrateBps`：可选视频编码码率上限；不填时保持 WebRTC 默认行为。
- `StreamingStatus.state`：用于页面显示当前阶段。
- `StreamingStatus.stats`：分辨率、FPS、音视频码率、丢包、往返延迟与 PCM 缓冲统计。
- `StreamingStatus.failure`：包含稳定错误码、用户提示和建议动作。

`STREAMING` 表示发送端媒体连接已建立，不等于浏览器已经显示首帧。浏览器只有确认实际画面/音频数据到达后才显示接收状态；只接收音频时会明确提示没有发送视频。

页面离开前调用 `stop()`，Activity 最终销毁时调用 `release()`；`release()` 后不能再次启动，应重新 `create()`。停止后马上开始会等待硬件清理与恢复窗口。当前实现保留 1、2、4 秒有限重连，协商超过 15 秒明确报错，不无限停留在协商中。

## 参考完整传输流程

1. PC 启动 Node.js 服务，浏览器点击“开始接收”，加入对应房间。
2. 眼镜调用 `GlassMediaStreamer.start()`，连接 SDK 并获取原始帧，加入同一房间。
3. 双方通过 WebSocket 交换 Offer / Answer 和 ICE 候选，建立 WebRTC 连接。
4. NV21 帧进入视频编码器，PCM 进入音频桥；音视频通过 WebRTC 直接到浏览器，Node.js 不接收媒体帧。
5. 浏览器显示画面，用户点击“开启声音”；刷新接收页或替换接收端时会先结束旧会话再协商。

阅读顺序：Android 的 `StreamingCoordinator`（流程）→ `WebRtcPublisher` / `NativePublisherSession`（发送）→ `web-stream-receiver/src/server.js`（信令转发）→ `public/app.js`（浏览器接收）。旧连接的异步回调不会修改新连接的页面状态。

请求尺寸与实际编码输出不是同一概念，调参及统计字段见 [配置说明](configuration.md#采集尺寸与编码尺寸)。需要公网和多设备时按 [生产部署](production-deployment.md) 扩展，不要直接把默认局域网服务暴露到公网。

## 混淆与发布

业务工程启用代码压缩或全模式优化后，应使用 release 构建在 Glass3 真机上验证完整推流，不要只验证 debug 包。
