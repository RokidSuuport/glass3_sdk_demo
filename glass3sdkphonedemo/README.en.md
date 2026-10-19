# Phone Demo · glass3sdkphonedemo

[中文](README.zh-CN.md) | [English](README.en.md)

[Back to project overview](../README.md)

This demo focuses on phone–glasses collaboration. It is suitable for companion apps that support the glasses and for apps that receive live views and manage devices on the phone.

- **Device connection**: Bluetooth and Wi-Fi P2P connections, with connection status display.
- **Message interaction**: Send messages to the glasses and receive data returned by the glasses.
- **Media reception**: Transfer media files from the glasses, browse the gallery, and receive and play live video.
- **Device management**: Examples for glasses settings, message notifications, and system OTA updates.

The glasses and phone demos can be used together to demonstrate communication and media transfer between both ends.

## SDK Methods and Source Entry Points

Use the phone SDK `com.rokid.security:phone.sdk` and obtain its services through `PSecuritySDK`.

### Initialize the Phone SDK

- `PSecuritySDK.getMobileEngineService()` →<br>  `initSDK(EngineParam(...), ...)`

Source: [MainPhoneActivity.kt](app/src/main/java/com/rokid/phone/ui/MainPhoneActivity.kt)

### Bluetooth Connection

- `getClassicBlueToothClientService()` →<br>  `addClientListener(...)`
- `connectToServer(...)`
- `removeClientListener(...)`

Source: [DeviceLinkerManager.kt](app/src/main/java/com/rokid/phone/DeviceLinkerManager.kt)

### Wi-Fi P2P Connection

- `getWifiP2PClientService()` →<br>  `initialize(...)`
- `startDiscoverPeers(...)`
- `connectDevice(...)`
- `stopPeerDiscovery()`

Source: [DeviceLinkerManager.kt](app/src/main/java/com/rokid/phone/DeviceLinkerManager.kt)

### Text Messages

- `getMessageService()` →<br>  `sendTextMessageByClassicBT(...)`
- `sendTextMessageByP2P(...)`
- `addMessageListener(...)`
- `removeMessageListener(...)`

Source: [Send example](app/src/main/java/com/rokid/phone/SendMessageActivity.kt), [receive example](app/src/main/java/com/rokid/phone/MessageReceiveActivity.kt)

### File Transfer and Gallery Reception

- `getMessageService()` →<br>  `getFileOperater()` / `getBtFileOperater()` →<br>  `sendFile(...)`
- `addFileReceiveV2Listener(...)`
- `removeFileReceiveV2Listener(...)`

The `onProgressChanged(progress, transferredBytes, totalBytes)` overload is available from phone-sdk 2.2.2-E; earlier versions only report a percentage.

Source: [File sending](app/src/main/java/com/rokid/phone/SendMessageActivity.kt), [gallery reception](app/src/main/java/com/rokid/phone/GalleryActivity.kt)

### Live Audio and Video Reception

- `getAbsDeviceInfoService()` →<br>  `requestVideoStream(...)`
- `requestAudioStream(...)`
- `stopVideoStream(...)`
- `stopAudioStream(...)`
- `IMessageListener` callbacks:<br>  `onNv21Data(...)`<br>  `onVideoH264Stream(...)`<br>  `onAudioStream(...)`

`GlassVideoStreamParam.zoomRatio` requires phone-sdk 2.2.2-E; older glasses ignore the field and report decimal zoom as unsupported. The preview overlays the requested size together with the actual decoded size, and the requested zoom together with the ratio last reported by the glasses, so a size or ratio the glasses did not honour stays visible.

Source: [VideoReceiveActivity.kt](app/src/main/java/com/rokid/phone/VideoReceiveActivity.kt)

### Switch Video Decoding Modes

- `getWifiP2PClientService()` →<br>  `setAutoDecodeH264ToNv21(...)`

Choose whether the SDK automatically outputs NV21 or the demo handles H.264 preview.

Source: [VideoReceiveActivity.kt](app/src/main/java/com/rokid/phone/VideoReceiveActivity.kt)

### Send Phone Notifications to the Glasses

- `getAbsNotificationService()` →<br>  `sendNotification(...)`

Source: [MessageNotificationListenerService.kt](app/src/main/java/com/rokid/phone/notification/service/MessageNotificationListenerService.kt)

When integrating, initialize the phone SDK and establish the required connections before sending messages or requesting media streams.

The target client ID for messages must match the name registered on the glasses. The original glasses demo registers `GlassSample`, and the phone sending example uses the same name.

When stopping the preview, stop the audio and video streams and remove the registered listeners.

## Example Usage Notice

The code, configuration, and usage of this demo are provided for reference only. Developers may adjust or replace them as needed and are responsible for completing their own adaptation and verification.

## SDK Documentation

[Rokid Developer Documentation](https://x-docs.rokid.com/docs/)
