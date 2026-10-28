# Dual-Device AI Inspection and Phone Reports Demo

[中文](README.md) | English

Project name: `dual-device-ai-inspection-report`

This project contains two independent apps for hazard inspection. The glasses capture the scene, identify hazards, and send the results to the phone. The phone connects to the glasses, stores inspection records, and generates PDF reports.

## Apps

| App | Purpose |
| --- | --- |
| [glass-app](glass-app/README.en.md) | Live preview, the offline voice command “识别” (shi bie, meaning “recognize”), touchpad/ring triggers, and calls to a vision model or the SDK |
| [phone-app](phone-app/README.en.md) | Bluetooth/P2P connections, battery status, AI inspection records and reports, gallery, accessory connection, and system updates |

The APK names used in `artifacts` are:

```text
dual-ai-inspection-glasses-debug.apk
dual-ai-inspection-phone-report-debug.apk
```

## Quick start

1. Install the glasses APK on the glasses and the phone APK on the phone.
2. Open the phone app, grant Bluetooth permissions, connect to a `Glass3_*` device, and wait for Bluetooth and P2P pairing to complete.
3. Open the glasses app and say “识别” (shi bie), or tap the touchpad/ring once.
4. The results are synchronized to the phone. Open **AI Inspection**, select a record, and generate a report.

The phone home screen also shows the glasses battery and P2P status. The gallery and settings use the original phone Demo implementation: the gallery displays transfer progress, supports swiping between images and double-tap zoom, and opens videos in an external player. Settings provides device information, Bluetooth ring connection, and system updates. Accessory connection requires the glasses Bluetooth connection; system updates require both Bluetooth and P2P. See the [phone app usage guide](phone-app/README.en.md).

If no vision model is configured, the glasses app uses the SDK fallback. With your own model configured, it can call an image-capable Chat Completions endpoint directly.

## Project structure

```text
dual-device-ai-inspection-report/
├── glass-app/      Independent Gradle project for the glasses
├── phone-app/      Independent Gradle project for the phone
└── artifacts/      Debug APKs and SHA-256 checksums
```

Open either project separately in Android Studio, or build it with its Gradle Wrapper:

```bash
cd glass-app
./gradlew assembleDebug

cd ../phone-app
./gradlew assembleDebug
```

## Configure the vision model

Edit [glass-app/inspection.properties](glass-app/inspection.properties), fill in the endpoint, model name, and API Key, then rebuild. Supported `engine` values:

- `sdk`: Use the SDK recognition capability.
- `api`: Call the configured vision model.
- `auto`: Try the model first and fall back to the SDK on failure; use the SDK directly if no model is configured.
