# 配置说明

本页集中列出构建、采集和传输参数。首次体验使用默认值即可，确认链路稳定后再按业务修改。

## 依赖方式

同一仓库源码工程：

```groovy
implementation project(':glass3-media-capture')
implementation project(':glass3-media-streaming')
```

客户外部工程配置 Maven 目录后，按需要选一个坐标：

```groovy
implementation 'com.rokid.glass:glass3-media-capture:1.0.0'
implementation 'com.rokid.glass:glass3-media-streaming:1.0.0'
```

完整推流只声明 `glass3-media-streaming` 即可，它会传递引入采集和 WebRTC 传输依赖。发布目录由 `./scripts/publish-android-components.sh` 生成，外部工程验证由 `./scripts/verify-aar-consumer.sh` 完成。

## Android 构建要求

| 项目 | 要求 |
| --- | --- |
| Java | 17 |
| minSdk | 29 |
| compileSdk / targetSdk | 34 |
| ABI | `arm64-v8a` |
| Kotlin JVM target | 17 |

## 权限矩阵

| 功能 | Manifest 权限 | 运行时申请 |
| --- | --- | --- |
| 只取视频 | `CAMERA` | 相机 |
| 只取音频 | `RECORD_AUDIO` | 录音 |
| 完整推流 | `INTERNET`、`ACCESS_NETWORK_STATE`、`CAMERA`、`RECORD_AUDIO`、`MODIFY_AUDIO_SETTINGS` | 相机、录音 |

使用明文 `ws://` 调试时还需允许 cleartext。公网生产部署应改为 `wss://`，不要长期保留全局明文许可。

## 原始采集默认值

```kotlin
CaptureOptions(
    video = VideoCaptureOptions(
        width = 1280,
        height = 720,
        fps = 15,
        enableMix = false,
        enableVideoStabilization = false,
    ),
    audio = AudioCaptureOptions(
        sampleRateHz = 16_000,
        channelCount = 1,
        bitsPerSample = 16,
    ),
    startupTimeoutMs = 12_000,
)
```

宽高必须是正偶数，FPS 范围为 1 到 60，音频当前只接受 16 bit PCM。配置值合法不代表设备固件一定支持该组合，应以首帧和状态回调为准。

## 推流参数

```kotlin
StreamingOptions(
    serverUrl = "wss://<YOUR-DOMAIN>/ws",
    videoEnabled = true,
    audioEnabled = true,
    roomId = "default",
)
```

- `serverUrl` 必须是完整 WebSocket 地址。
- 音视频至少启用一项。
- `roomId` 必须与浏览器一致。默认服务适合单设备体验，不具备身份认证。
- 局域网服务默认监听 TCP 8080，网页和 WebSocket 共用一个服务。

## 缓冲与性能

采集组件对 NV21 使用有界缓冲池；跨回调保存数据时用 `copyData()`，高性能链路可用 `retain()` / `close()`，但必须成对释放。WebRTC 音频桥使用有界 PCM 队列，`pcmUnderrunBytes` 或 `pcmDroppedBytes` 持续增长表示生产和消费速率不匹配。

## 混淆、签名和存储

- AAR 内置 consumer rules，普通 R8/ProGuard 构建无需追加规则。
- 应用签名由客户自己的发布流程管理，本项目不携带签名口令或私钥。
- 直接推流不落盘。原始媒体页面生成的 JPEG/WAV 仅用于人工验收，应由应用控制保存位置和清理策略。
