# Glass3 SDK Demo

[中文 README](README.zh-CN.md)

An example project for Rokid Glass3, covering the glasses SDK, phone and glasses coordination, and scenario applications.

The current `main` branch uses SDK version `2.2.2-E`.

## Glass Demo · glassdemo

Demonstrates photo and video capture, camera sharing and NV21 frame export, messaging, voice interaction, and device status queries for glasses application development.

[View the Glass Demo documentation](glassdemo/README.en.md)

## Phone Demo · glass3sdkphonedemo

Demonstrates connecting an Android phone to the glasses, two-way messaging and file transfer, real-time audio/video reception, notifications, and device management.

[View the Phone Demo documentation](glass3sdkphonedemo/README.en.md)

## Scenario Application · Audio/Video Streaming

Demonstrates capturing NV21 video frames and PCM audio from the glasses and sending them to a PC browser through WebRTC. The raw media capture implementation can also be used independently as a reference.

[View the audio/video capture and streaming documentation](scenario/glass3-media-streaming/README.md)

## Scenario Application · Hazard Recognition

Runs on the glasses and uses Wi-Fi to call a vision model for fire-safety hazard recognition, showing the live preview and recognition results without a phone app. DeepSeek is provided only as an integration example; developers can configure or integrate another vision model.

[View the Hazard Recognition Demo documentation](scenario/hazard-recognition/README.md)

## Scenario Application · Dual-Device Hazard Recognition

Combines a glasses app for capturing and recognizing on-site hazards with a phone app for receiving results and images, managing inspection records, and generating PDF reports. This example uses SDK version `2.2.2-E`.

[View the dual-device hazard recognition documentation](scenario/dual-device-ai-inspection-report/README.md)

## Example Usage Notice

Each project provides code references and usage examples only; developers are not required to follow the same approach. Adapt the implementation to your needs and refer to the README in each project for details.
