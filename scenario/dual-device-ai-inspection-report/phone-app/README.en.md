# Dual-Device AI Inspection — Phone App

[中文](README.md) | English

The phone app connects to the glasses and manages inspection reports. Its home screen provides Bluetooth connection, P2P, battery status, AI Inspection, gallery, and settings controls.

## Usage

1. Open the app and grant Bluetooth permissions.
2. Tap **Change glasses** and select a device whose name starts with `Glass3`.
3. Wait for both Bluetooth and P2P to show as connected on the pairing screen. The app returns to the home screen when pairing completes.
4. Open **AI Inspection**, select an inspection record, generate a PDF, and save or open the report.

## Gallery and settings

The gallery and settings use the implementation from the original phone Demo in this repository (`glass3sdkphonedemo`).

- **Gallery**: Shows photos and videos received from the glasses over P2P or Bluetooth, displays transfer progress, and updates the list when a transfer completes. Files are sorted by modification time, newest first.
- **Image preview**: Tap a photo to preview it inside the app. Swipe to switch images and double-tap to zoom.
- **Video playback**: Video thumbnails have a badge. Tapping one opens an available video player on the phone; the app shows a message if no player is available.
- **Settings**: Tap the gear icon at the top right of the home screen to view the glasses name, device SN, and system version, or open accessory connection and system update screens.
- **Accessory connection**: Connect the glasses over Bluetooth first, then open the accessory connection screen in settings to scan for and connect to a Bluetooth ring. Turning the connection switch off stops scanning and disconnects the connected ring.
- **System update (OTA)**: Uses the original Demo's system update flow. Both Bluetooth and P2P must be connected before opening this screen.

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
| Bluetooth ring | `PSecuritySDK.getBluetoothRingService()` | `addClientListener()`, `startScan()`, `stopScan()`, `connectToServer()`, `disconnect()` |

## Implementation locations

- `MainPhoneActivity.kt`: SDK initialization, permissions, device connections, and the AI entry point.
- `DeviceLinkerManager.kt`: Bluetooth connections and reception of recognition results and images.
- `classicbt/`: Discovery of nearby Glass3 devices.
- `GalleryActivity.kt`: Receive and display photos and videos from the glasses.
- `ImagePreviewActivity.kt`: Swipe between images and double-tap to zoom.
- `SettingActivity.kt`: Glasses device information, accessory connection, and system update entry points.
- `ui/DeviceLinerActivity.kt`: Bluetooth ring discovery, connection, and status display.
- `system/ui/SystemOtaActivity.kt`: System update screen.
- `system/viewmodel/SystemViewModel.kt`, `system/OtaManager.kt`: Update state, notifications, and P2P recovery.
- `ai/AiVisionActivity.kt`: Record list, selection, and report generation.
- `ai/AiVisionPdfGenerator.kt`: Inspection PDF layout and output.
- `ai/AiVisionStore.kt`: Local record persistence.

Image recognition runs on the glasses. The phone only receives and organizes results, so it does not need a vision model API Key.
