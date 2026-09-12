# 原始媒体采集接入

`GlassMediaCapture` 只负责从 Glass3 获取 NV21 视频帧和 PCM 音频帧，不包含浏览器、信令和 WebRTC。适用于算法输入、录像、识别、私有编码器或客户已有的传输链路。

## 在现有源码工程中启用组件

眼镜应用模块通过下面的源码模块获取原始数据：

```groovy
implementation project(':glass3-media-capture')
```

本项目已经注册该模块，所以可直接使用。接入另一个 Android 工程时，需先完成 [复制目录、注册模块和应用配置](source-integration.md)，不能只复制上面这一行。只取流不依赖传输组件、Node.js 或浏览器。

## 最小调用

下面展示核心调用关系，`consumeNv21` / `consumePcm` 是你的业务处理函数，不是 SDK 方法。完整可编译页面在下方链接中。

```kotlin
private val capture by lazy { GlassMediaCapture.create(applicationContext) }

fun start() {
    capture.start(
        options = CaptureOptions(),
        videoListener = { frame -> consumeNv21(frame.copyData(), frame.width, frame.height) },
        audioListener = { frame -> consumePcm(frame.data, frame.sampleRateHz) },
        statusListener = { status ->
            // 启动和运行错误从这里返回，不能只靠 start() 没抛异常判断成功。
            status.failure?.let { failure ->
                android.util.Log.e("MediaCapture", "${failure.code}: ${failure.technicalMessage}")
            }
        },
    )
}

override fun onStop() {
    capture.stop()
    super.onStop()
}

override fun onDestroy() {
    capture.release()
    super.onDestroy()
}
```

可直接复制的完整页面：

- [Kotlin MediaCaptureActivity](code/kotlin/MediaCaptureActivity.kt)
- [Java MediaCaptureActivity](code/java/MediaCaptureActivity.java)

原始采集页面还需一并复制 [LatestVideoWorker](code/shared/LatestVideoWorker.java)。它只负责示例的后台视频处理与最新结果保存，不改变 `GlassMediaCapture` 接口。NV21 与 PCM 在页面上分别显示，错误状态不会被逐帧刷新覆盖。

## 只取视频或只取音频

`videoListener`、`audioListener` 传入哪一个，就启用哪一个源；另一个设为 `null`。两者不能同时为空。

```kotlin
// 只获取视频；consumeNv21、showStatus 替换成业务处理。
capture.start(
    options = CaptureOptions(),
    videoListener = { frame -> consumeNv21(frame.copyData(), frame.width, frame.height) },
    audioListener = null,
    statusListener = { status -> showStatus(status) },
)
```

只取音频时反过来设置，并读取 `frame.data`、`sampleRateHz`、`channelCount`、`bitsPerSample`。完整页面默认同时验证两种媒体；单媒体接入的应用配置见 [配置说明](configuration.md)。

## NV21 所有权规则

视频回调中的 `Nv21Frame.data` 来自复用缓冲池。只在回调同步期间处理时可以直接读取；要交给线程池、编码器队列或保存任务，必须在回调返回前调用 `copyData()`，或者显式 `retain()` 并在完成后 `close()`。

推荐给首次接入者使用 `copyData()`。它多一次内存复制，但所有权最清晰。音频 `PcmFrame.data` 是该帧独立数组，可以在回调后使用；长时间持有仍应控制队列长度，避免内存增长。

SDK 回调应快速返回，不在回调里写大文件或执行耗时推理。完整页面的视频示例最多保留“正在处理的一帧 + 待处理的最新一帧”；消费慢时丢弃过期视频，并每 500 ms 刷新显示。连续录像应使用自己的有界编码队列并统计丢帧；PCM 不能照搬视频的丢旧帧策略，否则会造成声音断续。

## 默认格式

- 视频：1280 × 720、15 FPS、NV21，宽高必须为正偶数。
- 音频：当前仅支持 16 kHz、单声道、16 bit PCM，其他格式会在创建选项时直接报错。
- 启动超时：12000 ms（覆盖系统录音器冷启动时可能发生的内部重建）。

可以通过 `CaptureOptions.video` 修改设备支持范围内的分辨率和 FPS，具体见 [采集尺寸与编码尺寸](configuration.md#采集尺寸与编码尺寸)。SDK 未能连接、相机启动后无首帧、音频无数据都有超时错误，组件会通过 `CaptureStatus.failure` 通知并清理资源。

## 生命周期

页面不可见时调用 `stop()`，永久销毁时调用 `release()`。这些方法提交生命周期请求后返回，耗时的 SDK 调用在组件线程执行；看到 `STOPPING` 时，硬件可能还在清理，不能把方法返回当作相机已经可供其他应用使用。

再次开始会等待上一轮清理及 5 秒设备恢复窗口；等待期间调用停止/释放会取消尚未开始的采集。错误先报告，清理随后完成，重试仍需等待硬件释放。已在运行的客户算法任务要自行取消；完整页面会丢弃旧会话的结果。

不要同时让扫码、录像、其他 Camera2 页面和本组件占用同一个相机。调用 `release()` 后再次使用需要重新创建对象。
