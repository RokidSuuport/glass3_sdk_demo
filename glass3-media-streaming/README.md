# Glass3 音视频采集与浏览器传输方案

本项目把 Glass3 的摄像头 NV21 原始帧和麦克风 PCM 原始帧交付给 Android 应用，也可以通过 WebRTC 单向传输到 PC 浏览器。它既是可直接运行的完整产品样例，也是可接入客户工程的组件源码和 AAR 交付工程。

## 按你的目标选择入口

1. **我只想直接使用**：从 [5 分钟上手](docs/getting-started.md) 开始，运行 PC 接收页、安装眼镜应用并看到实时音视频。
2. **我想把完整推流接入自己的 App**：阅读 [完整推流接入](docs/webrtc-streaming.md)，只调用 `GlassMediaStreamer`。
3. **我只想获取 NV21/PCM 原始数据**：阅读 [原始媒体采集接入](docs/media-capture-integration.md)，只调用 `GlassMediaCapture`。

## 数据链路

```text
Glass3 CameraShare NV21 ─┐
                         ├─ Glass3 采集组件 ─ WebRTC 传输组件 ─ PC 浏览器
Glass3 SDK PCM ──────────┘                       │
                                      Node.js 仅交换信令
```

- 视频来自 Glass3 CameraShare，默认 1280 × 720、15 FPS、NV21。
- 音频来自 Glass3 SDK，默认 16 kHz、单声道、16 bit PCM。WebRTC 自带的 Android 录音和眼镜侧播放均已关闭。
- Node.js 服务只交换会话描述和 ICE 候选，不保存、不转码音视频。
- 当前开箱即用范围是“一台眼镜、一个浏览器、同一局域网”。公网和多设备改造边界见 [生产部署](docs/production-deployment.md)。

## 工程结构

```text
android/glass3-media-capture      Glass3 NV21/PCM 采集组件
android/webrtc-transport          WebRTC 发送与信令组件
android/glass3-media-streaming    一键推流门面组件
android/glass-stream-sender       可直接安装的眼镜应用
web-stream-receiver               PC 信令服务和浏览器接收页面
verification/aar-consumer         外部工程依赖验证项目
docs/code                         Kotlin/Java 完整 Activity
scripts                           启动、构建、验收脚本
```

## 环境要求

- Glass3 设备与可用的 Glass3 开放 SDK 服务。
- JDK 17、Android SDK、ADB；Android 最低版本 29，目标版本 34。
- Node.js 18 或更高版本。
- 直接体验时，眼镜和 PC 位于可互访的局域网，PC TCP 8080 端口可访问。
- 依赖下载和首次构建需要访问已配置的 Maven 与 npm 仓库。

## 文档索引

- [5 分钟上手](docs/getting-started.md)
- [完整推流接入](docs/webrtc-streaming.md)
- [原始媒体采集接入](docs/media-capture-integration.md)
- [配置说明](docs/configuration.md)
- [错误对照与排障](docs/troubleshooting.md)
- [生产部署与扩展](docs/production-deployment.md)
- [公开 API 参考](docs/api-reference.md)

## 当前交付边界

本项目提供完整源码、可安装应用、浏览器接收端、同仓库模块依赖方式、外部 Maven/AAR 接入方式以及可编译的 Kotlin/Java 示例。它不包含公网 TURN 服务、用户账号体系、多人房间 SFU 和云端媒体存储；这些是生产系统的业务部署能力，不是原始媒体采集的前置条件。
