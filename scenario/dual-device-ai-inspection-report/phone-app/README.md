# 手机端隐患识别报告

手机端负责连接眼镜并管理巡检报告。主页面提供蓝牙连接、P2P、电量、AI 查验、相册和设置入口。

## 使用方式

1. 打开应用并允许蓝牙权限。
2. 点击“更换眼镜”，选择名称以 `Glass3` 开头的设备。
3. 在配对页等待蓝牙和 P2P 都显示已连接，配对完成后会自动回到主页。
4. 点击“AI 查验”，选择一条巡检记录生成 PDF，然后保存或打开报告。

“相册”会展示眼镜传到手机的照片和视频。主页右上角的螺丝帽进入设置，页面按原白板结构展示设备信息、连接状态和版本信息。

## 构建与安装

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 主要 SDK 接口

| 场景 | SDK 接口 | 使用的方法 |
|---|---|---|
| SDK 初始化 | `PSecuritySDK.getMobileEngineService()` | `initSDK(EngineParam)` |
| 经典蓝牙扫描与连接 | `PSecuritySDK.getClassicBlueToothClientService()` | `addClientListener()`、`startScan()`、`connectToServer()` |
| P2P 连接 | `PSecuritySDK.getWifiP2PClientService()` | `addWifiP2PClientListener()`、`sendConnectP2pRequest()`、`disconnect()` |
| 接收巡检结果和图片 | `PSecuritySDK.getMessageService()` | `addMessageListener()`，处理 `IMessageListener.onClassicBTTextMessage()` 和 `onBTStreamDataReceived()` |
| 电量与设备信息 | `AbsMessageService` | 发送 `GET_SYSTEM_INFO`、`POWER_UPDATE` 消息，解析 `SYSTEM_INFO_RESPONSE` |
| 相册文件 | `getFileOperater()` / `getBtFileOperater()` | `addFileReceiveV2Listener()` 接收 P2P 或蓝牙文件 |

## 实现位置

- `MainPhoneActivity.kt`：SDK 初始化、权限、设备连接和 AI 入口。
- `DeviceLinkerManager.kt`：蓝牙连接以及识图结果、图片接收。
- `classicbt/`：附近的 Glass3 设备扫描。
- `GalleryActivity.kt`：接收和展示眼镜传来的照片、视频。
- `SettingActivity.kt`：设备信息、连接状态和电量。
- `ai/AiVisionActivity.kt`：记录列表、选择和报告生成。
- `ai/AiVisionPdfGenerator.kt`：巡检 PDF 排版与输出。
- `ai/AiVisionStore.kt`：本地记录持久化。

识图过程发生在眼镜端，手机端只负责接收和整理结果，因此不需要配置视觉模型 API Key。
