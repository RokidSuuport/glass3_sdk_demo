# 手机端 Demo · glass3sdkphonedemo

[返回工程总览](../README.md)

以手机与眼镜协同为主，适合开发眼镜配套 App，以及需要在手机端接收现场画面、管理设备的应用。

- **设备连接**：蓝牙连接、Wi-Fi P2P 连接及连接状态展示。
- **消息交互**：向眼镜发送消息，并接收眼镜返回的数据。
- **媒体接收**：眼镜媒体文件传输、相册浏览及实时视频接收与播放。
- **设备管理**：眼镜设置、消息通知及系统 OTA 升级示例。

眼镜端与手机端 Demo 可配合演示双端通信和媒体传输流程。

## SDK 方法与源码入口

使用手机 SDK `com.rokid.security:phone.sdk`，通过 `PSecuritySDK` 获取各项服务。

### 初始化手机 SDK

- `PSecuritySDK.getMobileEngineService()` →<br>  `initSDK(EngineParam(...), ...)`

源码：[MainPhoneActivity.kt](app/src/main/java/com/rokid/phone/ui/MainPhoneActivity.kt)

### 蓝牙连接

- `getClassicBlueToothClientService()` →<br>  `addClientListener(...)`
- `connectToServer(...)`
- `removeClientListener(...)`

源码：[DeviceLinkerManager.kt](app/src/main/java/com/rokid/phone/DeviceLinkerManager.kt)

### Wi-Fi P2P 连接

- `getWifiP2PClientService()` →<br>  `initialize(...)`
- `startDiscoverPeers(...)`
- `connectDevice(...)`
- `stopPeerDiscovery()`

源码：[DeviceLinkerManager.kt](app/src/main/java/com/rokid/phone/DeviceLinkerManager.kt)

### 文本消息

- `getMessageService()` →<br>  `sendTextMessageByClassicBT(...)`
- `sendTextMessageByP2P(...)`
- `addMessageListener(...)`
- `removeMessageListener(...)`

源码：[发送示例](app/src/main/java/com/rokid/phone/SendMessageActivity.kt)、[接收示例](app/src/main/java/com/rokid/phone/MessageReceiveActivity.kt)

### 文件传输与相册接收

- `getMessageService()` →<br>  `getFileOperater()` / `getBtFileOperater()` →<br>  `sendFile(...)`
- `addFileReceiveV2Listener(...)`
- `removeFileReceiveV2Listener(...)`

源码：[文件发送](app/src/main/java/com/rokid/phone/SendMessageActivity.kt)、[相册接收](app/src/main/java/com/rokid/phone/GalleryActivity.kt)

### 实时音视频接收

- `getAbsDeviceInfoService()` →<br>  `requestVideoStream(...)`
- `requestAudioStream(...)`
- `stopVideoStream(...)`
- `stopAudioStream(...)`
- `IMessageListener` 回调：<br>  `onNv21Data(...)`<br>  `onVideoH264Stream(...)`<br>  `onAudioStream(...)`

源码：[VideoReceiveActivity.kt](app/src/main/java/com/rokid/phone/VideoReceiveActivity.kt)

### 视频解码模式切换

- `getWifiP2PClientService()` →<br>  `setAutoDecodeH264ToNv21(...)`

选择 SDK 自动输出 NV21，或由 Demo 处理 H.264 预览。

源码：[VideoReceiveActivity.kt](app/src/main/java/com/rokid/phone/VideoReceiveActivity.kt)

### 手机通知发送到眼镜

- `getAbsNotificationService()` →<br>  `sendNotification(...)`

源码：[MessageNotificationListenerService.kt](app/src/main/java/com/rokid/phone/notification/service/MessageNotificationListenerService.kt)

接入时先完成手机 SDK 初始化和所需连接，再发送消息或请求媒体流。

消息目标客户端 ID 需要与眼镜端注册名称对应：原眼镜 Demo 注册的是 `GlassSample`，手机发送示例也使用该名称。

停止预览时结束音视频流，并移除已注册的监听。

## 示例使用声明

本 Demo 的代码、配置和使用方式仅供参考，不要求开发者采用相同实现。开发者可按实际需求调整或替换，并自行完成适配与验证。

## SDK 文档

[Rokid 开发文档](https://x-docs.rokid.com/docs/)
