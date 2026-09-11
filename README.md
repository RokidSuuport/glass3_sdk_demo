# Glass3 Demo

面向 Rokid Glass3 的示例应用，包含眼镜端 SDK 能力演示、手机与眼镜协同演示，以及面向实际业务的行业场景应用。

## 示例使用声明

本仓库的各项 Demo、配置和说明仅用于提供代码参考与使用示例，不构成对开发者技术选型或业务实现方式的强制要求。开发者可根据实际需求选择、调整或替换相关实现，并自行完成适配与验证。

## 工程概览

| 工程 | 运行端 | 主要用途 |
| --- | --- | --- |
| [glassdemo](glassdemo/) | 眼镜 | 了解和验证眼镜 SDK 的基础能力，作为眼镜应用开发参考。 |
| [glass3sdkphonedemo](glass3sdkphonedemo/) | Android 手机 | 演示手机连接眼镜、双端通信、媒体接收和设备管理。 |
| [industry/hazard-recognition](industry/hazard-recognition/) | 眼镜 | “隐患识别”应用，演示通过 Wi-Fi 直连视觉大模型进行消防隐患识别。 |

## 眼镜端 Demo · glassdemo

以单项 SDK 能力为主，适合了解眼镜接口用法、验证硬件能力，或开发自定义眼镜应用。

- **图像采集**：SDK 拍照、录像，以及相机共享、NV21 帧导出等示例。
- **双端通信**：与手机端配合，演示消息发送和接收。
- **语音交互**：离线语音指令，以及在线 ASR 语音识别、TTS 语音合成示例。
- **设备交互**：触摸板、按键、头部姿态及设备状态读取等用法。

### SDK 方法与源码入口

使用眼镜 SDK `com.rokid.security:glass3.open.sdk`；在线 ASR/TTS 示例另外使用 `com.rokid.security.sdk:online-speech`。以下按功能列出本仓库实际调用的方法，源码链接放在各用例下方。

#### 绑定服务、注册客户端

- `GlassSdk.isReady()`
- `bindSecurityService(...)`
- `registerClient(...)`
- `release()`

源码：[GlassSdkUtils.kt](glassdemo/app/src/main/java/com/rokid/glass/utils/GlassSdkUtils.kt)

#### 拍照、录像、录音

- `GlassSdk.getGlassMediaService()` →<br>  `addPhotoCallback(...)`
- `takePhoto(...)`
- `startRecord(...)`
- `stopRecord()`
- `startAudioRecord(...)`
- `stopAudioRecord(...)`

源码：[SdkMediaActivity.kt](glassdemo/app/src/main/java/com/rokid/glass/SdkMediaActivity.kt)

#### 共享相机 NV21 帧

- `CameraShareHelper.getSupportedPreviewSizes()`
- `initNv21ExportWithConfig(...)`
- `Nv21Callback.onNv21Frame(...)`
- `releaseNv21Export()`

源码：[Nv21ExportFragment.kt](glassdemo/app/src/main/java/com/rokid/glass/camera/Nv21ExportFragment.kt)

#### 共享相机 Surface

- `CameraShareHelper.initSurfaceWithConfig(...)`
- `releaseSurface()`

源码：[SurfaceShareFragment.kt](glassdemo/app/src/main/java/com/rokid/glass/camera/SurfaceShareFragment.kt)

#### 蓝牙/P2P 消息与文件

- `GlassSdk.getGlassMessageService()` →<br>  `sendTextMessageByClassicBT(...)`
- `sendTextMessageByP2P(...)`
- `setMessageListener(...)`
- 文件操作器的 `sendFile(...)`

源码：[发送示例](glassdemo/app/src/main/java/com/rokid/glass/SendMessageActivity.kt)、[接收示例](glassdemo/app/src/main/java/com/rokid/glass/MessageReceiveActivity.kt)

#### 离线语音指令、设备状态

- `GlassSdk.getGlassOfflineCmdService()` →<br>  `add(VoiceAction(...))`
- `getGlassDeviceService()`：读取 `serialNumber`、`deviceStatusInfo`

源码：[HomeActivity.kt](glassdemo/app/src/main/java/com/rokid/glass/HomeActivity.kt)

#### 在线 ASR/TTS

- `OnlineSpeechSdk(OnlineSpeechSdkConfig(...))` →<br>  `createAsrClient()` →<br>  `attachAudioSource(...)`
- `createTtsClient()` →<br>  `attachStreamPlayer(...)`
- 连接后使用 `startAsrWithMic()`
- `stopAsrWithMic()`
- `speak(...)`，结束时 `stop()` / `close()`

源码：[PrivateSpeechActivity.kt](glassdemo/app/src/main/java/com/rokid/glass/speech/private/PrivateSpeechActivity.kt)

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

## 手机端 Demo · glass3sdkphonedemo

以手机与眼镜协同为主，适合开发眼镜配套 App，以及需要在手机端接收现场画面、管理设备的应用。

- **设备连接**：蓝牙连接、Wi-Fi P2P 连接及连接状态展示。
- **消息交互**：向眼镜发送消息，并接收眼镜返回的数据。
- **媒体接收**：眼镜媒体文件传输、相册浏览及实时视频接收与播放。
- **设备管理**：眼镜设置、消息通知及系统 OTA 升级示例。

眼镜端与手机端 Demo 可配合演示双端通信和媒体传输流程。

### SDK 方法与源码入口

使用手机 SDK `com.rokid.security:phone.sdk`，通过 `PSecuritySDK` 获取各项服务。

#### 初始化手机 SDK

- `PSecuritySDK.getMobileEngineService()` →<br>  `initSDK(EngineParam(...), ...)`

源码：[MainPhoneActivity.kt](glass3sdkphonedemo/app/src/main/java/com/rokid/phone/ui/MainPhoneActivity.kt)

#### 蓝牙连接

- `getClassicBlueToothClientService()` →<br>  `addClientListener(...)`
- `connectToServer(...)`
- `removeClientListener(...)`

源码：[DeviceLinkerManager.kt](glass3sdkphonedemo/app/src/main/java/com/rokid/phone/DeviceLinkerManager.kt)

#### Wi-Fi P2P 连接

- `getWifiP2PClientService()` →<br>  `initialize(...)`
- `startDiscoverPeers(...)`
- `connectDevice(...)`
- `stopPeerDiscovery()`

源码：[DeviceLinkerManager.kt](glass3sdkphonedemo/app/src/main/java/com/rokid/phone/DeviceLinkerManager.kt)

#### 文本消息

- `getMessageService()` →<br>  `sendTextMessageByClassicBT(...)`
- `sendTextMessageByP2P(...)`
- `addMessageListener(...)`
- `removeMessageListener(...)`

源码：[发送示例](glass3sdkphonedemo/app/src/main/java/com/rokid/phone/SendMessageActivity.kt)、[接收示例](glass3sdkphonedemo/app/src/main/java/com/rokid/phone/MessageReceiveActivity.kt)

#### 文件传输与相册接收

- `getMessageService()` →<br>  `getFileOperater()` / `getBtFileOperater()` →<br>  `sendFile(...)`
- `addFileReceiveV2Listener(...)`
- `removeFileReceiveV2Listener(...)`

源码：[文件发送](glass3sdkphonedemo/app/src/main/java/com/rokid/phone/SendMessageActivity.kt)、[相册接收](glass3sdkphonedemo/app/src/main/java/com/rokid/phone/GalleryActivity.kt)

#### 实时音视频接收

- `getAbsDeviceInfoService()` →<br>  `requestVideoStream(...)`
- `requestAudioStream(...)`
- `stopVideoStream(...)`
- `stopAudioStream(...)`
- `IMessageListener` 回调：<br>  `onNv21Data(...)`<br>  `onVideoH264Stream(...)`<br>  `onAudioStream(...)`

源码：[VideoReceiveActivity.kt](glass3sdkphonedemo/app/src/main/java/com/rokid/phone/VideoReceiveActivity.kt)

#### 视频解码模式切换

- `getWifiP2PClientService()` →<br>  `setAutoDecodeH264ToNv21(...)`

选择 SDK 自动输出 NV21，或由 Demo 处理 H.264 预览。

源码：[VideoReceiveActivity.kt](glass3sdkphonedemo/app/src/main/java/com/rokid/phone/VideoReceiveActivity.kt)

#### 手机通知发送到眼镜

- `getAbsNotificationService()` →<br>  `sendNotification(...)`

源码：[MessageNotificationListenerService.kt](glass3sdkphonedemo/app/src/main/java/com/rokid/phone/notification/service/MessageNotificationListenerService.kt)

接入时先完成手机 SDK 初始化和所需连接，再发送消息或请求媒体流。

消息目标客户端 ID 需要与眼镜端注册名称对应：原眼镜 Demo 注册的是 `GlassSample`，手机发送示例也使用该名称。

停止预览时结束音视频流，并移除已注册的监听。

## 行业场景 Demo · 隐患识别

目录：[industry/hazard-recognition](industry/hazard-recognition/)。面向九小场所消防隐患识别，演示从现场采集到结果展示的完整业务流程。应用直接运行在眼镜上，通过 Wi-Fi 调用视觉大模型，无需手机端 App。

**识别流程：现场预览 → NV21 取帧 → 画面去重 → 大模型分析 → 眼镜展示结果。**

- **现场预览**：进入应用即可在左上角查看实时画面，其余区域展示识别结果。
- **消防隐患识别**：关注有可见证据的通道或出口堵塞、消火栓遮挡、用电异常、危险充电及烟火等问题。
- **减少重复检查**：眼镜端筛选相似画面，并结合模型结果识别重复隐患。
- **简洁结果展示**：纵向表格展示“隐患内容、法规依据、整改建议”；只显示最近一次结果，10 秒无新结果时清空。
- **模型接入示例**：通过可配置的 Chat Completions 兼容接口接入视觉模型，包含 DeepSeek 接入示例。

本地 DeepSeek 接入仅用于演示，不提供正式业务的大模型服务或账号密钥。正式项目中的大模型接入和业务功能由开发者自行实现和维护。

识别结果和法规参考用于辅助现场复核，不作为自动违法认定。详细流程和代码用例见[隐患识别 Demo 说明](industry/hazard-recognition/README.md)。

[industry](industry/) 用于持续扩展眼镜端行业场景应用，每个场景围绕一个具体业务流程提供可参考的 Demo。

### 隐患识别具体使用了哪些 SDK 能力

本工程只使用眼镜 SDK 的服务连接、客户端注册和共享相机 NV21 能力。完整入口见 [InspectionActivity.kt](industry/hazard-recognition/app/src/main/java/com/rokid/industry/hazardrecognition/InspectionActivity.kt)。

#### 1. 判断 SDK 是否就绪

`GlassSdk.isReady()`

已就绪时进入客户端注册；否则先绑定服务。

#### 2. 绑定眼镜系统服务

`GlassSdk.bindSecurityService(...)`

在 `IServiceConnectionCallback.onServiceConnected()` 中继续初始化。

#### 3. 注册业务客户端

`GlassSdk.registerClient("HazardRecognition", ...)`

收到 `IClientCallback.onReady()` 后才启动取帧；字符串是客户端名称，不是 AK/SK。

#### 4. 查询可用预览尺寸

`CameraShareHelper().getSupportedPreviewSizes()`

选择长边不超过 1280、短边不超过 720 的可用尺寸，保留 SDK 返回的宽高方向；没有合适选项时使用 SDK 默认尺寸。

#### 5. 启动 NV21 输出

`initNv21ExportWithConfig(false, config, callback)`

`false` 使用纯相机画面；请求 15 FPS，由应用另行筛选待分析帧。

#### 6. 接收画面和状态

`Nv21Callback.onNv21Frame(...)`<br>
`onCameraOpened(...)`<br>
`onCameraClosed()`<br>
`onError(...)`

每帧先复制缓冲区，再交给预览及后续编码；关闭和错误通过状态提示处理。

#### 7. 停止取帧、释放 SDK

`CameraShareHelper.releaseNv21Export()`<br>
`GlassSdk.release()`

退出前台停止 NV21 输出，页面销毁时释放 SDK，并处理迟到的连接回调。

**SDK 与业务代码的分工**

SDK 提供现场画面，以下业务能力均由 Demo 实现：

- [FrameQueue](industry/hazard-recognition/app/src/main/java/com/rokid/industry/hazardrecognition/FrameQueue.kt) 和 [HazardLedger](industry/hazard-recognition/app/src/main/java/com/rokid/industry/hazardrecognition/HazardLedger.kt) 负责本地去重。
- [InspectionClient](industry/hazard-recognition/app/src/main/java/com/rokid/industry/hazardrecognition/InspectionClient.kt) 使用 OkHttp 请求视觉模型。
- [RegulationCatalog](industry/hazard-recognition/app/src/main/java/com/rokid/industry/hazardrecognition/RegulationCatalog.kt) 提供法规映射。
- [HazardTableView](industry/hazard-recognition/app/src/main/java/com/rokid/industry/hazardrecognition/HazardTableView.kt) 负责结果展示。

本例通过眼镜系统服务接收共享帧，应用不声明或申请相机权限，也未调用 ASR/TTS。DeepSeek API Key 仅用于视觉模型请求。

需要复制接入流程时，见[隐患识别 SDK 接入用例](industry/hazard-recognition/README.md#sdk-接入用例)。

开发者接入自有视觉模型或网关，可参考[大模型接入与开发者配置](industry/hazard-recognition/README.md#大模型接入与开发者配置)，其中提供通用接口与 DeepSeek 的配置示例。

正式业务建议由开发者在服务端完成去重，见[去重接入建议](industry/hazard-recognition/README.md#开发者接入建议在服务端去重)。

## SDK 文档

[Rokid 开发文档](https://x-docs.rokid.com/docs/)
