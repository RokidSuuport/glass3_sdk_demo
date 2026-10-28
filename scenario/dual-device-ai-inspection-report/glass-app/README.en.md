# Glasses Hazard Inspection App

[中文](README.md) | English

The app opens directly to the inspection screen. A live camera preview appears on the left, with operating instructions on the right. Say “识别” (shi bie, meaning “recognize”), or tap the touchpad/ring once to capture the current scene for inspection.

The results include a report title, inspection summary, hazards, regulatory references, and corrective actions. The glasses synchronize the results and scene images to the connected phone.

## Build and install

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Configure the model

Without a configured model, the app uses the SDK fallback. To connect your own vision model, edit [inspection.properties](inspection.properties) in this directory:

```properties
endpoint=
model=
apiKey=
engine=auto
```

## Main SDK interfaces

| Scenario | SDK interface | Methods used |
| --- | --- | --- |
| Shared camera preview | `CameraShareHelper` | `initNv21ExportWithConfig()`; receive continuous NV21 frames through `Nv21Callback.onNv21Frame()` |
| Capture an inspection image | Local frame buffer | Copy the latest NV21 frame, then generate a JPEG with `YuvImage.compressToJpeg()` |
| SDK image recognition fallback | `IAiChatService` | `startAiChat(true)`, `toAiChatWithIntent()`; receive results through `AiChatListener.onAiChatAnswer()` |
| Offline voice command | `IOfflineCmdService` | `add(VoiceAction("识别", "shi bie", IVoiceCallback))`; call `remove()` when leaving the screen |
| Send results and images to the phone | `IMessageServer` | `sendTextMessageByClassicBTWithClient()` for results and `sendStreamData()` for JPEG data |

A custom vision model uses an HTTPS image endpoint without going through the AI chat SDK. The `IAiChatService` vision intent is used only for the SDK fallback.

## Implementation locations

- `IdentificationActivity.kt`: Inspection screen, triggers, result display, and phone synchronization.
- `CameraSurfacePreviewView.kt`: Shared camera preview and JPEG extraction from the current frame.
- `IndustrialInspectionProtocol.kt`: Inspection prompt and vision model request.
- `VisualInspectionClient.kt`: Model endpoint calls and streamed results.
- `GlassSdkUtils.kt`: Glass3 SDK initialization.

Preview and recognition share one `1280×720 / 15 FPS` NV21 camera stream. Triggering inspection copies the latest frame and encodes it as JPEG; it does not take a separate high-resolution photo.
