# 眼镜端隐患识别

眼镜端打开后直接进入识别页，左侧是相机实时预览，右侧提示操作方式。说“识别”或单击触摸板/指环即可截取当前画面进行一次巡检分析。

识别结果包含报告标题、巡检摘要、隐患问题、法律依据和整改建议。眼镜会把结果和现场图片一起同步给已连接的手机。

## 构建与安装

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 配置模型

不配置时使用 SDK 兜底。接入自己的视觉模型时，直接修改当前目录下的 `inspection.properties`：

```properties
endpoint=
model=
apiKey=
engine=auto
```

## 主要 SDK 接口

| 场景 | SDK 接口 | 使用的方法 |
|---|---|---|
| 共享相机预览 | `CameraShareHelper` | `initNv21ExportWithConfig()`，通过 `Nv21Callback.onNv21Frame()` 持续接收 NV21 帧 |
| 截取识别画面 | 本地帧缓存 | 复制最新 NV21 帧，再用 `YuvImage.compressToJpeg()` 生成 JPEG |
| SDK 识图兜底 | `IAiChatService` | `startAiChat(true)`、`toAiChatWithIntent()`，结果由 `AiChatListener.onAiChatAnswer()` 返回 |
| 离线语音指令 | `IOfflineCmdService` | `add(VoiceAction("识别", "shi bie", IVoiceCallback))`，页面退出时调用 `remove()` |
| 同步结果和图片到手机 | `IMessageServer` | `sendTextMessageByClassicBTWithClient()` 发送巡检结果，`sendStreamData()` 发送 JPEG |

自有视觉模型走 HTTPS 图片接口，不经过 AI 问答 SDK。SDK 兜底时才使用 `IAiChatService` 的视觉意图。

## 实现位置

- `IdentificationActivity.kt`：识别页、触发方式、结果展示和手机同步。
- `CameraSurfacePreviewView.kt`：共享相机预览与当前帧 JPEG 提取。
- `IndustrialInspectionProtocol.kt`：巡检提示词和视觉模型请求。
- `VisualInspectionClient.kt`：模型接口调用与流式结果。
- `GlassSdkUtils.kt`：Glass3 SDK 初始化。

预览和识别使用同一路 `1280×720 / 15 FPS` 的 NV21 相机流。按下触发后复制最新帧并编码为 JPEG，不另开高分辨率快门。
