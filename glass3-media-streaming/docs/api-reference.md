# 公开 API 参考

业务代码应优先使用两个门面：完整推流使用 `GlassMediaStreamer`，只取原始帧使用 `GlassMediaCapture`。`internal` 包不是兼容性承诺的一部分，不应由客户直接调用。

## GlassMediaStreamer

```kotlin
val streamer = GlassMediaStreamer.create(applicationContext)
streamer.start(options, listener)
streamer.stop()
streamer.release()
val status = streamer.currentStatus()
```

- `create(Context)`：使用 Application Context 创建独立实例。
- `start(String)`：以默认音视频和默认房间快速启动。
- `start(StreamingOptions, StreamingStatusListener?)`：完整配置并监听状态。
- `stop()`：停止当前会话并释放采集与传输资源，实例仍可再次启动。
- `release()`：永久释放实例；之后重新调用需先创建新实例。
- `currentStatus()`：取得最近一次线程安全状态快照。

### StreamingOptions

| 字段 | 默认值 | 含义 |
| --- | --- | --- |
| `serverUrl` | 无 | 完整 `ws://` 或 `wss://` 信令地址 |
| `videoEnabled` | `true` | 是否发送 NV21 视频 |
| `audioEnabled` | `true` | 是否发送 PCM 音频 |
| `roomId` | `default` | 与浏览器匹配的房间标识 |

### StreamingState

`IDLE` 表示未启动；`PREPARING` 表示准备 SDK 和媒体；`WAITING_RECEIVER` 表示信令已连接并等待浏览器；`NEGOTIATING` 表示交换 SDP/ICE；`STREAMING` 表示媒体正在发送；`STOPPING` 表示清理中；`ERROR` 表示失败；`RELEASED` 表示实例不可再用。

### StreamingStats

提供 `videoWidth`、`videoHeight`、`videoFps`、`videoBitrateBps`、`audioBitrateBps`、`packetsLost`、`roundTripTimeMs`、`pcmUnderrunBytes` 和 `pcmDroppedBytes`。统计是运行状态快照，不应作为计费数据。

## GlassMediaCapture

```kotlin
val capture = GlassMediaCapture.create(applicationContext)
capture.start(options, videoListener, audioListener, statusListener)
capture.stop()
capture.release()
val status = capture.currentStatus()
```

- `videoListener` 为 `null` 时不启动视频源。
- `audioListener` 为 `null` 时不启动音频源。
- 两者不能同时为 `null`。
- `statusListener` 可为空，页面也可主动读取 `currentStatus()`。

### CaptureOptions

`video` 是 `VideoCaptureOptions`，默认 1280 × 720、15 FPS；`audio` 是 `AudioCaptureOptions`，默认 16 kHz、单声道、16 bit；`startupTimeoutMs` 默认 12000 ms。这个时间覆盖 Glass3 系统录音器冷启动时可能发生的内部重建，并且仍会在硬件长期无数据时明确失败。

### CaptureState

`IDLE`、`PREPARING`、`CAPTURING`、`STOPPING`、`ERROR`、`RELEASED` 分别对应空闲、准备、采集中、清理中、失败和永久释放。

### Nv21Frame

- `data`：NV21 缓冲区，只保证在当前租约有效期内可读。
- `width`、`height`：该帧实际宽高。
- `timestampNs`：单调时钟时间戳，单位纳秒。
- `copyData()`：复制有效 NV21 字节，最适合跨线程或异步保存。
- `retain()`：创建共享租约；处理完成必须对返回对象调用 `close()`。
- `close()`：释放当前租约。回调收到的原始帧由组件管理，客户通常不需要手动关闭；只有显式 `retain()` 的帧需要关闭。

### PcmFrame

包含 `data`、`sampleRateHz`、`channelCount`、`bitsPerSample` 和 `timestampNs`。默认是 16 kHz、单声道、16 bit little-endian PCM。

### CaptureStatus

包含 `state`、`videoMetrics`、`audioMetrics` 与可空的 `failure`。视频指标包括实际宽高、帧数、丢帧、字节数和 FPS；音频指标包括格式、帧数和字节数。

## MediaFailure

所有可预期运行失败统一为：

- `code`：稳定的 `MediaErrorCode`，用于程序分支和统计。
- `userMessage`：可直接展示给用户的中文说明。
- `suggestedAction`：推荐处理动作。
- `technicalMessage`：面向日志的技术细节。
- `cause`：底层异常，可能为空。

12 个稳定错误码及逐项处理方式见 [错误对照与排障](troubleshooting.md)。业务代码应判断 `code`，不要依赖提示文字完全一致。

## 线程与生命周期约定

状态和媒体回调不保证在主线程，更新 Android UI 必须切换到主线程。`start()`、`stop()` 和 `release()` 由组件串行保护；耗时的 Glass 媒体服务调用在组件专用线程执行，不会要求客户自己创建工作线程。组件还会在一次完整停止后留出 5 秒设备恢复窗口，再开始下一次采集。业务仍应避免多个页面同时控制同一个实例。推荐在页面离开时 `stop()`，最终销毁时 `release()`。
