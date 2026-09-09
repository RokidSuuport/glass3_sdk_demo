# 原始媒体采集接入

`GlassMediaCapture` 只负责从 Glass3 获取 NV21 视频帧和 PCM 音频帧，不包含浏览器、信令和 WebRTC。适用于算法输入、录像、识别、私有编码器或客户已有的传输链路。

## 先判断代码放在哪里

### 与本项目处于同一个 Gradle 工程

直接依赖源码模块，不需要 AAR：

```groovy
implementation project(':glass3-media-capture')
```

如果同一个应用还需要完整 WebRTC 推流，也可以同时写：

```groovy
implementation project(':glass3-media-streaming')
```

### 放入客户自己的另一个 Android 工程

外部工程不能复制 `implementation project(模块名)`，因为客户工程没有本仓库的模块。配置交付的 Maven 目录后使用坐标：

```groovy
repositories {
    google()
    mavenCentral()
    maven { url uri('<MAVEN_REPOSITORY_PATH>') }
}

dependencies {
    implementation 'com.rokid.glass:glass3-media-capture:1.0.0'
}
```

只获取原始帧时不需要引入 `glass3-media-streaming` 和 WebRTC。完整 Maven 目录会同时保留 POM 和依赖信息，比单独复制 AAR 更不容易漏项。

## 权限与环境

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

使用哪一种媒体就申请对应运行时权限。工程使用 Java 17，最低 Android 版本 29，目标设备为 Glass3 `arm64-v8a`。

## 最小调用

```kotlin
private val capture by lazy { GlassMediaCapture.create(applicationContext) }

fun start() {
    capture.start(
        videoListener = { frame -> consumeNv21(frame.copyData(), frame.width, frame.height) },
        audioListener = { frame -> consumePcm(frame.data, frame.sampleRateHz) },
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

## NV21 所有权规则

视频回调中的 `Nv21Frame.data` 来自复用缓冲池。只在回调同步期间处理时可以直接读取；要交给线程池、编码器队列或保存任务，必须在回调返回前调用 `copyData()`，或者显式 `retain()` 并在完成后 `close()`。

推荐给首次接入者使用 `copyData()`。它多一次内存复制，但所有权最清晰。音频 `PcmFrame.data` 是该帧独立数组，可以在回调后使用；长时间持有仍应控制队列长度，避免内存增长。

## 默认格式

- 视频：1280 × 720、15 FPS、NV21，宽高必须为正偶数。
- 音频：16 kHz、单声道、16 bit PCM。
- 启动超时：12000 ms（覆盖系统录音器冷启动时可能发生的内部重建）。

可以通过 `CaptureOptions` 修改设备支持范围内的配置。没有收到首帧不代表应无限等待；组件会通过 `CaptureStatus.failure` 返回稳定错误并清理资源。

## 生命周期

页面不可见时调用 `stop()`，释放当前采集但保留对象；页面永久销毁时调用 `release()`。不要同时让扫码、录像、其他 Camera2 页面和本组件占用同一个相机。出现错误后先停止并确认占用解除，再重新创建或启动。
