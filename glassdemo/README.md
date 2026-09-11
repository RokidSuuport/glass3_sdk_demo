# 眼镜端 Demo · glassdemo

[返回工程总览](../README.md)

以单项 SDK 能力为主，适合了解眼镜接口用法、验证硬件能力，或开发自定义眼镜应用。

- **图像采集**：SDK 拍照、录像，以及相机共享、NV21 帧导出等示例。
- **双端通信**：与手机端配合，演示消息发送和接收。
- **语音交互**：离线语音指令，以及在线 ASR 语音识别、TTS 语音合成示例。
- **设备交互**：触摸板、按键、头部姿态及设备状态读取等用法。

## SDK 方法与源码入口

使用眼镜 SDK `com.rokid.security:glass3.open.sdk`；在线 ASR/TTS 示例另外使用 `com.rokid.security.sdk:online-speech`。以下按功能列出本仓库实际调用的方法，源码链接放在各用例下方。

### 绑定服务、注册客户端

- `GlassSdk.isReady()`
- `bindSecurityService(...)`
- `registerClient(...)`
- `release()`

源码：[GlassSdkUtils.kt](app/src/main/java/com/rokid/glass/utils/GlassSdkUtils.kt)

### 拍照、录像、录音

- `GlassSdk.getGlassMediaService()` →<br>  `addPhotoCallback(...)`
- `takePhoto(...)`
- `startRecord(...)`
- `stopRecord()`
- `startAudioRecord(...)`
- `stopAudioRecord(...)`

源码：[SdkMediaActivity.kt](app/src/main/java/com/rokid/glass/SdkMediaActivity.kt)

### 共享相机 NV21 帧

- `CameraShareHelper.getSupportedPreviewSizes()`
- `initNv21ExportWithConfig(...)`
- `Nv21Callback.onNv21Frame(...)`
- `releaseNv21Export()`

源码：[Nv21ExportFragment.kt](app/src/main/java/com/rokid/glass/camera/Nv21ExportFragment.kt)

### 共享相机 Surface

- `CameraShareHelper.initSurfaceWithConfig(...)`
- `releaseSurface()`

源码：[SurfaceShareFragment.kt](app/src/main/java/com/rokid/glass/camera/SurfaceShareFragment.kt)

### 蓝牙/P2P 消息与文件

- `GlassSdk.getGlassMessageService()` →<br>  `sendTextMessageByClassicBT(...)`
- `sendTextMessageByP2P(...)`
- `setMessageListener(...)`
- 文件操作器的 `sendFile(...)`

源码：[发送示例](app/src/main/java/com/rokid/glass/SendMessageActivity.kt)、[接收示例](app/src/main/java/com/rokid/glass/MessageReceiveActivity.kt)

### 离线语音指令、设备状态

- `GlassSdk.getGlassOfflineCmdService()` →<br>  `add(VoiceAction(...))`
- `getGlassDeviceService()`：读取 `serialNumber`、`deviceStatusInfo`

源码：[HomeActivity.kt](app/src/main/java/com/rokid/glass/HomeActivity.kt)

### 在线 ASR/TTS

- `OnlineSpeechSdk(OnlineSpeechSdkConfig(...))` →<br>  `createAsrClient()` →<br>  `attachAudioSource(...)`
- `createTtsClient()` →<br>  `attachStreamPlayer(...)`
- 连接后使用 `startAsrWithMic()`
- `stopAsrWithMic()`
- `speak(...)`，结束时 `stop()` / `close()`

源码：[PrivateSpeechActivity.kt](app/src/main/java/com/rokid/glass/speech/private/PrivateSpeechActivity.kt)

眼镜端的基本顺序：

```text
bindSecurityService
  → onServiceConnected
  → registerClient
  → IClientCallback.onReady
  → 使用 SDK 功能
```

绑定调用返回不代表客户端已经就绪；对应监听注册和释放方式请参考上述源码。

在线语音的 AK/SK 属于语音服务配置，不要与客户端名称混淆。

## 示例使用声明

本 Demo 的代码、配置和使用方式仅供参考，不要求开发者采用相同实现。开发者可按实际需求调整或替换，并自行完成适配与验证。

## SDK 文档

[Rokid 开发文档](https://x-docs.rokid.com/docs/)
