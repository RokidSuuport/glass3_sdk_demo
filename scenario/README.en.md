# Scenario Applications

[中文](README.md) | English

Each scenario contains Android projects that can be opened, built, and installed independently. Audio/Video Streaming and Hazard Recognition use the glasses system capabilities and Wi-Fi without a phone app. Dual-Device AI Inspection includes both a glasses app and a phone app.

| Application | Directory | Minimal scenario | SDK version |
| --- | --- | --- | --- |
| Audio/Video Streaming | [glass3-media-streaming](glass3-media-streaming/README.en.md) | Capture NV21 video and PCM audio on the glasses → Send over WebRTC to a PC browser | 2.2.0-E |
| Hazard Recognition | [hazard-recognition](hazard-recognition/README.en.md) | Live preview → Sample NV21 frames → Analyze with a vision model → Display results on the glasses | 2.2.0-E |
| Dual-Device AI Inspection | [dual-device-ai-inspection-report](dual-device-ai-inspection-report/README.en.md) | Capture the scene on the glasses → Recognize hazards → Send results and images to the phone → Generate a PDF report | 2.2.2-E |

Additional scenario directories can be added here. There is currently no shared module; extract one when multiple applications have stable reuse requirements.
