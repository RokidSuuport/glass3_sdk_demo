# 配置说明

本页集中列出构建、采集和传输参数。首次体验使用默认值即可，确认链路稳定后再按业务修改。

## 源码组件关系

```groovy
implementation project(':glass3-media-capture')
implementation project(':glass3-media-streaming')
```

只读取 NV21/PCM 时使用采集组件；需要传输到浏览器时使用推流组件。完整示例已经在 `glass-stream-sender` 模块中接好这两层能力，可以直接运行并作为业务页面的实现参考。

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

宽高必须是正偶数，FPS 范围为 1 到 60。音频当前只接受 16 kHz、单声道、16 bit PCM，其他格式在创建选项时即报错。需要其他音频格式时，在获取 PCM 后自行重采样/转换。配置值合法不代表设备固件一定支持该视频组合，应以首帧和状态回调为准。

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

## 采集尺寸与编码尺寸

这三项不要混为一谈：

| 指标 | 从哪里读取 | 含义 |
| --- | --- | --- |
| 请求采集尺寸 | `CaptureOptions.video` / `StreamingOptions.videoCapture` | 希望设备提供的 NV21 格式 |
| 实际采集尺寸 | `Nv21Frame.width/height`；推流 `stats.videoWidth/videoHeight/videoFps` | CameraShare 实际交付的帧 |
| 实际编码/接收尺寸 | 眼镜 `stats.encodedVideoWidth/encodedVideoHeight/encodedVideoFps`；浏览器统计区 | WebRTC 编码、接收和解码的图像 |

例如请求 720p 并设置视频码率上限：

```kotlin
StreamingOptions(
    serverUrl = "ws://<PC-IP>:8080/ws",
    videoCapture = VideoCaptureOptions(width = 1280, height = 720, fps = 15),
    maxVideoBitrateBps = 3_000_000,
)
```

只取流时，将同样的 `VideoCaptureOptions` 作为 `CaptureOptions` 的 `video` 参数。要尝试 1080p，改为 `width = 1920, height = 1080`；是否支持仍由固件、CameraShare 和当时资源决定，必须检查实际首帧尺寸和持续帧率，不能只看请求值。

`maxVideoBitrateBps` 必须为正数，是编码器的上限，不是最低保证值；不填写时保持默认策略。WebRTC 仍可根据网络与负载降低码率、帧率或分辨率，本入口不承诺锁定输出尺寸。`videoQualityLimitationReason` 可用于排查发送端的带宽或 CPU 限制，未取得数据时为 `unknown`。

## 缓冲与性能

采集组件对 NV21 使用有界缓冲池；跨回调保存数据时用 `copyData()`，高性能链路可用 `retain()` / `close()`，但必须成对释放。WebRTC 音频桥使用有界 PCM 队列，`pcmUnderrunBytes` 或 `pcmDroppedBytes` 持续增长表示生产和消费速率不匹配。

## 混淆、签名和存储

- 启用 R8/ProGuard 后，需要使用 release 构建在 Glass3 真机上验证采集和推流链路。
- 应用签名由客户自己的发布流程管理，本项目不携带签名口令或私钥。
- 直接推流不落盘。原始媒体页面生成的 JPEG/WAV 仅用于人工验收，应由应用控制保存位置和清理策略。
