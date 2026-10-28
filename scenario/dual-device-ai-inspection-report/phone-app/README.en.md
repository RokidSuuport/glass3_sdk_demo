# Phone Hazard Inspection Reports App

[中文](README.md) | English

The phone app connects to the glasses and manages inspection reports. Its home screen provides Bluetooth connection, P2P, battery status, AI Inspection, gallery, and settings controls.

## Usage

1. Open the app and grant Bluetooth permissions.
2. Tap **Change glasses** and select a device whose name starts with `Glass3`.
3. Wait for both Bluetooth and P2P to show as connected on the pairing screen. The app returns to the home screen when pairing completes.
4. Open **AI Inspection**, select an inspection record, generate a PDF, and save or open the report.

The gallery shows photos and videos received from the glasses. The gear icon at the top right of the home screen opens settings for glasses device information and management.

## Build and install

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Main SDK interfaces

| Scenario | SDK interface | Methods used |
| --- | --- | --- |
| SDK initialization | `PSecuritySDK.getMobileEngineService()` | `initSDK(EngineParam)` |
| Classic Bluetooth discovery and connection | `PSecuritySDK.getClassicBlueToothClientService()` | `addClientListener()`, `startScan()`, `connectToServer()` |
| P2P connection | `PSecuritySDK.getWifiP2PClientService()` | `addWifiP2PClientListener()`, `sendConnectP2pRequest()`, `disconnect()` |
| Receive inspection results and images | `PSecuritySDK.getMessageService()` | `addMessageListener()`; handle `IMessageListener.onClassicBTTextMessage()` and `onBTStreamDataReceived()` |
| Battery and device information | `AbsMessageService` | Send `GET_SYSTEM_INFO` and `POWER_UPDATE` messages; parse `SYSTEM_INFO_RESPONSE` |
| Gallery files | `getFileOperater()` / `getBtFileOperater()` | `addFileReceiveV2Listener()` to receive files over P2P or Bluetooth |

## Implementation locations

- `MainPhoneActivity.kt`: SDK initialization, permissions, device connections, and the AI entry point.
- `DeviceLinkerManager.kt`: Bluetooth connections and reception of recognition results and images.
- `classicbt/`: Discovery of nearby Glass3 devices.
- `GalleryActivity.kt`: Receive and display photos and videos from the glasses.
- `SettingActivity.kt`: Glasses device information and settings entry points.
- `ai/AiVisionActivity.kt`: Record list, selection, and report generation.
- `ai/AiVisionPdfGenerator.kt`: Inspection PDF layout and output.
- `ai/AiVisionStore.kt`: Local record persistence.

Image recognition runs on the glasses. The phone only receives and organizes results, so it does not need a vision model API Key.
